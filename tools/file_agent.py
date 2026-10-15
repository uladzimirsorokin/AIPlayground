#!/usr/bin/env python3
"""День 34: ассистент для работы с файлами проекта (goal-driven, function-calling).

Даёте ЦЕЛЬ («найди все места, где используется X», «сгенерируй ADR», «обнови README»),
а ассистент сам решает, какие файлы читать/искать/менять — через инструменты:
list_files / read_file / search / write_file / replace_in_file / git_diff.

По умолчанию — dry-run: изменения НЕ пишутся на диск, а выводятся как unified diff.
С --apply изменения сохраняются, а в конце печатается `git diff`. Все вызовы инструментов
логируются (воспроизводимость).

Запуск:
    python tools/file_agent.py --repo /path/to/project "Найди все места, где используется ChatAgent"
    python tools/file_agent.py --repo /path/to/project --apply \
        "Создай docs/adr/0001-mcp.md с кратким ADR про использование MCP"

LLM: env AGENT_LLM_ENDPOINT/MODEL/API_KEY или флаги --endpoint/--model/--api-key
(дефолт — локальный Ollama llama3.2:3b).
"""

import argparse
import difflib
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.environ.get("FS_ROOT", os.getcwd())
MAX_FILE_BYTES = 400_000
MAX_READ_CHARS = 12_000
MAX_SEARCH_HITS = 60
MAX_SEARCH_CHARS = 6_000
SKIP_DIRS = {
    ".git", ".hg", ".svn", "node_modules", "build", ".gradle", ".idea", ".vscode",
    "venv", ".venv", "__pycache__", "dist", "out", ".kotlin", ".next", "target",
    ".dart_tool", "Pods", ".terraform", "coverage",
}
SKIP_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".pdf", ".zip", ".jar", ".apk",
    ".aar", ".so", ".dylib", ".dll", ".bin", ".woff", ".woff2", ".ttf", ".mp4", ".mov",
)

SYSTEM = (
    "Ты — ассистент по работе с файлами проекта. Достигни ЦЕЛИ, вызывая инструменты. "
    "Сначала исследуй (list_files/search/read_file), затем действуй (write_file/replace_in_file). "
    "Работай сам, не проси пользователя «открыть файл». Пути — относительные, от корня проекта. "
    "Когда цель достигнута — дай краткий итоговый отчёт: что сделано, какие файлы затронуты и почему. "
    "Не выдумывай содержимое файлов — читай их."
)

TOOLS = [
    {"type": "function", "function": {
        "name": "list_files", "description": "Список файлов проекта (можно ограничить подпапкой)",
        "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": []}}},
    {"type": "function", "function": {
        "name": "read_file", "description": "Прочитать содержимое файла",
        "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "search", "description": "Regex-поиск по файлам проекта; возвращает совпадения file:line: текст",
        "parameters": {"type": "object", "properties": {
            "pattern": {"type": "string"}, "path": {"type": "string"},
            "glob": {"type": "string", "description": "фильтр по расширению, напр. .py или .kt"}},
            "required": ["pattern"]}}},
    {"type": "function", "function": {
        "name": "write_file", "description": "Создать или перезаписать файл целиком",
        "parameters": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
                       "required": ["path", "content"]}}},
    {"type": "function", "function": {
        "name": "replace_in_file", "description": "Заменить первое вхождение old на new в файле",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}},
            "required": ["path", "old", "new"]}}},
    {"type": "function", "function": {
        "name": "prepend_to_files",
        "description": "Добавить текст в НАЧАЛО каждого файла по glob (например шапку/комментарий/лицензию). Массовая правка за один вызов.",
        "parameters": {"type": "object", "properties": {
            "glob": {"type": "string", "description": "напр. **/*ViewModel.kt или .kt"},
            "text": {"type": "string"}, "path": {"type": "string"}},
            "required": ["glob", "text"]}}},
    {"type": "function", "function": {
        "name": "append_to_files",
        "description": "Добавить текст в КОНЕЦ каждого файла по glob.",
        "parameters": {"type": "object", "properties": {
            "glob": {"type": "string"}, "text": {"type": "string"}, "path": {"type": "string"}},
            "required": ["glob", "text"]}}},
    {"type": "function", "function": {
        "name": "replace_in_files",
        "description": "Заменить old на new во ВСЕХ файлах по glob, где встречается old (массовая замена).",
        "parameters": {"type": "object", "properties": {
            "glob": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}, "path": {"type": "string"}},
            "required": ["glob", "old", "new"]}}},
    {"type": "function", "function": {
        "name": "insert_before_in_files",
        "description": "Вставить текст ПЕРЕД первой строкой, совпавшей с regex pattern, в каждом файле по glob. Для шапки/комментария прямо перед классом/функцией (а не в начало файла).",
        "parameters": {"type": "object", "properties": {
            "glob": {"type": "string", "description": "напр. **/*ViewModel.kt"},
            "pattern": {"type": "string", "description": "regex строки, напр. class\\s+\\w*ViewModel"},
            "text": {"type": "string"}, "path": {"type": "string"}},
            "required": ["glob", "pattern", "text"]}}},
    {"type": "function", "function": {
        "name": "git_diff",
        "description": "Текущий git diff проекта (unified)",
        "parameters": {"type": "object", "properties": {}},
    }},
]


# --- низкоуровневые файловые операции (root-guarded) ------------------------


def _resolve(path: str) -> str:
    p = os.path.abspath(os.path.join(ROOT, path))
    if p != ROOT and not p.startswith(ROOT + os.sep):
        raise ValueError(f"путь вне проекта: {path}")
    return p


def fs_list_files(path: str = "") -> str:
    base = _resolve(path) if path else ROOT
    if not os.path.isdir(base):
        return f"не директория: {path}"
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in sorted(filenames):
            out.append(os.path.relpath(os.path.join(dirpath, fn), ROOT))
    return "\n".join(out[:1000]) or "(пусто)"


def fs_read_file(path: str) -> str:
    p = _resolve(path)
    if not os.path.isfile(p):
        return f"файл не найден: {path}"
    if os.path.getsize(p) > MAX_FILE_BYTES:
        return f"файл слишком большой: {path}"
    with open(p, encoding="utf-8", errors="ignore") as f:
        text = f.read()
    if len(text) > MAX_READ_CHARS:
        return text[:MAX_READ_CHARS] + f"\n... [файл усечён, всего {len(text)} симв.]"
    return text


def fs_search(pattern: str, path: str = "", glob: str = "") -> str:
    try:
        rx = re.compile(pattern)
    except re.error as exc:
        return f"некорректный regex: {exc}"
    base = _resolve(path) if path else ROOT
    hits = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in sorted(filenames):
            if fn.endswith(SKIP_SUFFIXES):
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), ROOT)
            if glob:
                if any(ch in glob for ch in "*?/"):
                    if not (fnmatch.fnmatch(rel, glob) or fnmatch.fnmatch(fn, glob)):
                        continue
                elif not fn.endswith(glob):
                    continue
            p = os.path.join(dirpath, fn)
            try:
                if os.path.getsize(p) > MAX_FILE_BYTES:
                    continue
                with open(p, encoding="utf-8", errors="ignore") as f:
                    for i, line in enumerate(f, 1):
                        if rx.search(line):
                            hits.append(f"{rel}:{i}: {line.strip()[:200]}")
                            if len(hits) >= MAX_SEARCH_HITS:
                                break
                if len(hits) >= MAX_SEARCH_HITS:
                    break
            except OSError:
                continue
        if len(hits) >= MAX_SEARCH_HITS:
            break
    text = "\n".join(hits) or "(совпадений нет)"
    if len(text) > MAX_SEARCH_CHARS:
        text = text[:MAX_SEARCH_CHARS] + "\n... [обрезано]"
    return text


def _match_files(glob: str, path: str = "") -> list:
    """Абсолютные пути текстовых файлов проекта, подходящих под glob (fnmatch или суффикс)."""
    base = _resolve(path) if path else ROOT
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in sorted(filenames):
            if fn.endswith(SKIP_SUFFIXES):
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), ROOT)
            if glob:
                if any(ch in glob for ch in "*?/"):
                    if not (fnmatch.fnmatch(rel, glob) or fnmatch.fnmatch(fn, glob)):
                        continue
                elif not fn.endswith(glob):
                    continue
            out.append(os.path.join(dirpath, fn))
    return out


def _transform(cur: str, mode: str, text: str, old: str, new: str):
    if mode == "prepend":
        return cur if cur.lstrip().startswith(text.strip()) else text.rstrip() + "\n" + cur
    if mode == "append":
        return cur if cur.rstrip().endswith(text.rstrip()) else cur.rstrip() + "\n" + text.rstrip() + "\n"
    if mode == "replace":
        return cur.replace(old, new) if old and old in cur else None
    return None


def _batch(mode: str, glob: str, path: str = "", text: str = "", old: str = "", new: str = "",
           read=None, write=None) -> str:
    read = read or fs_read_file
    write = write or fs_write_file
    files = _match_files(glob, path)
    changed = []
    for p in files:
        rel = os.path.relpath(p, ROOT)
        cur = read(rel)
        if cur.startswith("файл не найден") or cur.startswith("файл слишком большой"):
            continue
        updated = _transform(cur, mode, text, old, new)
        if updated is None or updated == cur:
            continue
        write(rel, updated)
        changed.append(rel)
    return f"изменено файлов: {len(changed)} (совпало по glob={glob!r}: {len(files)})\n" + "\n".join(changed)


def fs_prepend_to_files(glob: str, text: str, path: str = "") -> str:
    return _batch("prepend", glob, path, text=text)


def fs_append_to_files(glob: str, text: str, path: str = "") -> str:
    return _batch("append", glob, path, text=text)


def fs_replace_in_files(glob: str, old: str, new: str, path: str = "") -> str:
    return _batch("replace", glob, path, old=old, new=new)


def _insert_before(cur: str, pattern: str, text: str):
    rx = re.compile(pattern)
    lines = cur.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if rx.search(line):
            if i > 0 and lines[i - 1].strip() == text.strip():
                return None  # уже вставлено
            return "".join(lines[:i]) + text.rstrip("\n") + "\n" + "".join(lines[i:])
    return None


def _batch_insert(glob: str, pattern: str, text: str, path: str = "", read=None, write=None) -> str:
    read = read or fs_read_file
    write = write or fs_write_file
    try:
        re.compile(pattern)
    except re.error as exc:
        return f"некорректный regex: {exc}"
    files = _match_files(glob, path)
    changed = []
    for p in files:
        rel = os.path.relpath(p, ROOT)
        cur = read(rel)
        if cur.startswith("файл не найден") or cur.startswith("файл слишком большой"):
            continue
        updated = _insert_before(cur, pattern, text)
        if updated is None or updated == cur:
            continue
        write(rel, updated)
        changed.append(rel)
    return (f"вставлено перед совпадением в {len(changed)} файл(ов) "
            f"(glob={glob!r}, pattern={pattern!r}):\n" + "\n".join(changed))


def fs_insert_before_in_files(glob: str, pattern: str, text: str, path: str = "") -> str:
    return _batch_insert(glob, pattern, text, path)


def fs_write_file(path: str, content: str) -> str:
    p = _resolve(path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(content)
    return f"записано: {path} ({len(content)} симв.)"


def fs_replace_in_file(path: str, old: str, new: str) -> str:
    p = _resolve(path)
    if not os.path.isfile(p):
        return f"файл не найден: {path}"
    with open(p, encoding="utf-8", errors="ignore") as f:
        cur = f.read()
    if old not in cur:
        return f"фрагмент не найден в {path}"
    with open(p, "w", encoding="utf-8") as f:
        f.write(cur.replace(old, new, 1))
    return f"заменено в {path}"


def fs_git_diff() -> str:
    out = subprocess.run(["git", "-C", ROOT, "diff"], capture_output=True, text=True).stdout.strip()
    status = subprocess.run(["git", "-C", ROOT, "status", "--porcelain"], capture_output=True, text=True).stdout.strip()
    untracked = [line for line in status.splitlines() if line.startswith("??")]
    parts = []
    if out:
        parts.append(out)
    if untracked:
        parts.append("Новые (untracked) файлы:\n" + "\n".join(untracked))
    return "\n".join(parts) or "(изменений нет)"


# --- LLM с tools ------------------------------------------------------------


def chat_tools(messages: list, cfg: dict) -> dict:
    """Вызов LLM с tools. Retry с чуть большей температурой — против транзиентных 500
    (Ollama «token repeat limit reached»)."""
    last = ""
    for attempt in range(3):
        body = {"model": cfg["model"], "messages": messages,
                "temperature": 0.2 + 0.15 * attempt, "tools": TOOLS}
        if cfg.get("reasoning_effort"):
            body["reasoning_effort"] = cfg["reasoning_effort"]
        req = urllib.request.Request(
            cfg["endpoint"], data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        if cfg.get("api_key"):
            req.add_header("Authorization", f"Bearer {cfg['api_key']}")
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                msg = json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]
            return {"content": msg.get("content") or "", "tool_calls": msg.get("tool_calls") or []}
        except urllib.error.HTTPError as exc:
            last = f"HTTP {exc.code}: {exc.read().decode('utf-8', 'ignore')[:200]}"
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
        if attempt < 2:
            print(f"[agent] LLM сбой ({last}); повтор {attempt + 2}/3", file=sys.stderr)
            time.sleep(1.5)
    raise RuntimeError(last)


# --- агент с виртуальной ФС (dry-run) ---------------------------------------


class VirtualFS:
    def __init__(self):
        self.pending = {}  # path -> новое содержимое

    def read(self, path: str) -> str:
        return self.pending.get(path) if path in self.pending else fs_read_file(path)

    def write(self, path: str, content: str) -> str:
        self.pending[path] = content
        return (f"[dry-run] предложено записать: {path} ({len(content)} симв.). "
                "Если цель достигнута — заверши без новых вызовов инструментов.")

    def replace(self, path: str, old: str, new: str) -> str:
        cur = self.read(path)
        if cur.startswith("файл не найден"):
            return cur
        if old not in cur:
            return f"фрагмент не найден в {path}"
        self.pending[path] = cur.replace(old, new, 1)
        return (f"[dry-run] предложено изменить: {path}. "
                "Если цель достигнута — заверши без новых вызовов инструментов.")

    def diff(self) -> str:
        out = []
        for path, new in self.pending.items():
            p = _resolve(path)
            old = ""
            if os.path.isfile(p):
                with open(p, encoding="utf-8", errors="ignore") as f:
                    old = f.read()
            d = difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True),
                                     fromfile=f"a/{path}", tofile=f"b/{path}")
            out.append("".join(d) or f"(без изменений: {path})")
        return "\n".join(out)

    def apply(self) -> None:
        for path, new in self.pending.items():
            fs_write_file(path, new)


def execute(name: str, args: dict, vfs: VirtualFS, apply_changes: bool) -> str:
    if name == "list_files":
        return fs_list_files(args.get("path", ""))
    if name == "read_file":
        return vfs.read(args.get("path", ""))
    if name == "search":
        return fs_search(args.get("pattern", ""), args.get("path", ""), args.get("glob", ""))
    if name == "git_diff":
        return fs_git_diff()
    if name == "write_file":
        if apply_changes:
            return fs_write_file(args.get("path", ""), args.get("content", ""))
        return vfs.write(args.get("path", ""), args.get("content", ""))
    if name == "replace_in_file":
        if apply_changes:
            return fs_replace_in_file(args.get("path", ""), args.get("old", ""), args.get("new", ""))
        return vfs.replace(args.get("path", ""), args.get("old", ""), args.get("new", ""))
    if name in ("prepend_to_files", "append_to_files", "replace_in_files"):
        r, w = (fs_read_file, fs_write_file) if apply_changes else (vfs.read, vfs.write)
        mode = {"prepend_to_files": "prepend", "append_to_files": "append", "replace_in_files": "replace"}[name]
        return _batch(mode, args.get("glob", ""), args.get("path", ""),
                      text=args.get("text", ""), old=args.get("old", ""), new=args.get("new", ""),
                      read=r, write=w)
    if name == "insert_before_in_files":
        r, w = (fs_read_file, fs_write_file) if apply_changes else (vfs.read, vfs.write)
        return _batch_insert(args.get("glob", ""), args.get("pattern", ""), args.get("text", ""),
                             args.get("path", ""), read=r, write=w)
    return f"неизвестный инструмент: {name}"


def run_goal(goal: str, cfg: dict, apply_changes: bool, max_rounds: int) -> str:
    vfs = VirtualFS()
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": goal}]
    seen = set()
    final = "Достигнут лимит раундов."
    for rnd in range(max_rounds):
        try:
            resp = chat_tools(messages, cfg)
        except Exception as exc:
            print(f"[agent] ошибка LLM: {exc}", file=sys.stderr)
            final = f"Остановлено из-за ошибки LLM: {exc}"
            break
        if not resp["tool_calls"]:
            print(f"[agent] готово за {rnd + 1} раунд(ов)", file=sys.stderr)
            final = resp["content"]
            break
        messages.append({"role": "assistant", "content": resp["content"], "tool_calls": resp["tool_calls"]})
        all_dup = True
        for tc in resp["tool_calls"]:
            name = tc["function"]["name"]
            try:
                args = json.loads(tc["function"].get("arguments") or "{}")
            except Exception:
                args = {}
            key = (name, json.dumps(args, sort_keys=True, ensure_ascii=False))
            if key in seen:
                result = "уже выполнено ранее; если цель достигнута — заверши без вызовов инструментов и дай отчёт."
            else:
                seen.add(key)
                all_dup = False
                result = execute(name, args, vfs, apply_changes)
            print(f"[agent] {name}({json.dumps(args, ensure_ascii=False)[:160]}) -> {result[:160]}", file=sys.stderr)
            messages.append({"role": "tool", "tool_call_id": tc.get("id", ""), "content": result})
        if all_dup:
            final = "Готово (повторные вызовы остановлены)."
            break
    if not apply_changes and vfs.pending:
        print("\n=== Предлагаемые изменения (diff) ===")
        print(vfs.diff())
    return final


def main() -> int:
    ap = argparse.ArgumentParser(description="Файловый ассистент (День 34)")
    ap.add_argument("goal", help="цель на уровне задачи (что сделать с файлами)")
    ap.add_argument("--repo", default=".", help="корень проекта")
    ap.add_argument("--apply", action="store_true", help="применять изменения (иначе dry-run + diff)")
    ap.add_argument("--max-rounds", type=int, default=12)
    ap.add_argument("--endpoint", default=os.environ.get("AGENT_LLM_ENDPOINT") or os.environ.get("LLM_ENDPOINT") or "http://127.0.0.1:11434/v1/chat/completions")
    ap.add_argument("--model", default=os.environ.get("AGENT_LLM_MODEL") or os.environ.get("LLM_MODEL") or "llama3.2:3b")
    ap.add_argument("--api-key", default=os.environ.get("AGENT_LLM_API_KEY") or os.environ.get("LLM_API_KEY") or "")
    ap.add_argument("--reasoning-effort", default=os.environ.get("AGENT_REASONING_EFFORT", ""))
    args = ap.parse_args()

    global ROOT
    ROOT = os.path.abspath(os.path.expanduser(args.repo))
    cfg = {"endpoint": args.endpoint, "model": args.model, "api_key": args.api_key,
           "reasoning_effort": args.reasoning_effort}

    print(f"[agent] цель: {args.goal}", file=sys.stderr)
    print(f"[agent] repo: {ROOT} | модель: {cfg['model']} | режим: {'apply' if args.apply else 'dry-run'}", file=sys.stderr)
    answer = run_goal(args.goal, cfg, args.apply, args.max_rounds)
    print("\n=== Итог ===")
    print(answer)
    if args.apply:
        print("\n=== git diff ===")
        print(fs_git_diff())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
