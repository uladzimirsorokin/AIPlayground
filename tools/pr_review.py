#!/usr/bin/env python3
"""День 32: автоматическое AI-ревью PR — САМОДОСТАТОЧНЫЙ скрипт (только stdlib).

Кладётся в любой репозиторий вместе с .github/workflows/ai-review.yml. Делает:
  1) diff и изменённые файлы PR (git diff base...head);
  2) RAG-контекст по проекту (документация + код): обход файлов → чанки → tfidf
     (для CI, без внешних сервисов) или ollama-эмбеддинги;
  3) ревью локальной/облачной LLM (OpenAI-совместимый API);
  4) опционально — комментарий в PR через GitHub API.

Локально (dry-run, печатает ревью):
    python tools/pr_review.py --base origin/main --head HEAD
    python tools/pr_review.py --base HEAD~1 --embedding ollama

В CI:
    python tools/pr_review.py --embedding tfidf --post
    env: LLM_ENDPOINT, LLM_MODEL, LLM_API_KEY, GITHUB_TOKEN, GITHUB_REPOSITORY,
         GITHUB_EVENT_PATH (номер PR) или PR_NUMBER

Настройки LLM (приоритет PR_REVIEW_*): PR_REVIEW/LLM_ENDPOINT|MODEL|API_KEY.
Дефолт — локальный Ollama (http://127.0.0.1:11434).
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import urllib.request
from collections import Counter

DEFAULT_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
DEFAULT_MODEL = "llama3.2:3b"
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")
OLLAMA_EMBED_MODEL = os.environ.get("OLLAMA_EMBED_MODEL", "nomic-embed-text")

TEXT_EXTS = {
    ".md", ".mdx", ".txt", ".rst", ".adoc",
    ".kt", ".kts", ".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs",
    ".c", ".h", ".cpp", ".hpp", ".cc", ".cs", ".rb", ".php", ".swift", ".scala",
    ".sql", ".json", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".gradle",
    ".xml", ".html", ".css", ".scss", ".sh", ".bash", ".zsh", ".proto", ".tf",
}
SKIP_DIRS = {
    ".git", ".hg", ".svn", "node_modules", "build", ".gradle", ".idea", ".vscode",
    "venv", ".venv", "__pycache__", "dist", "out", ".kotlin", ".next", "target",
    ".dart_tool", "Pods", ".terraform", "coverage", ".pytest_cache", ".mypy_cache",
}
SKIP_SUFFIXES = (
    ".lock", ".min.js", ".min.css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
    ".pdf", ".zip", ".jar", ".apk", ".aar", ".so", ".dylib", ".dll", ".bin", ".woff", ".woff2",
)
SKIP_NAMES = {
    "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "Pipfile.lock",
    "gradle.lockfile", "flake.lock", "Cargo.lock",
}
MAX_FILE_BYTES = 200_000
MAX_FILES = 3000
MAX_TOTAL_CHARS = 5_000_000
TOKEN_RE = re.compile(r"[a-zа-яё0-9_]+")
STOPWORDS = {
    "и", "в", "во", "не", "на", "я", "бы", "он", "что", "это", "с", "она", "как", "а", "по",
    "то", "но", "они", "к", "из", "у", "же", "мы", "за", "его", "для", "или", "до", "о", "так",
    "при", "её", "быть", "этот", "который", "уже", "был", "только", "ещё", "вот", "от", "все",
    "если", "где", "теперь", "чем", "да", "ли", "тогда", "можно", "нужно", "надо", "очень",
    "также", "всё", "каждый", "через", "после", "между", "эти", "там", "здесь", "the", "a",
    "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "be", "as",
    "at", "by", "from", "it", "this", "that", "these", "those", "will", "can", "may", "also",
    "but", "not", "all", "into", "about", "after", "over",
}

REVIEW_SYSTEM = (
    "Ты — опытный senior-разработчик и ревьюер. Проанализируй diff пул-реквеста и дай "
    "конструктивное ревью. Опирайся в первую очередь на сам diff; контекст проекта (RAG) "
    "используй для проверки соответствия архитектуре, документации и стилю. Не выдумывай "
    "факты: если не уверен — помечай как вопрос/предположение.\n\n"
    "Формат ответа (markdown):\n"
    "### Кратко\n(1–3 предложения о сути изменений и общее впечатление)\n\n"
    "### Потенциальные баги\n- список; для каждого — файл и суть проблемы; если нет — «не обнаружено»\n\n"
    "### Архитектурные проблемы\n- список (связность, дублирование, слои, соответствие документации); если нет — «не обнаружено»\n\n"
    "### Рекомендации\n- конкретные улучшения (нейминг, тесты, обработка ошибок, производительность)\n\n"
    "Пиши по-русски, конкретно, со ссылками на файлы. Код не переписывай целиком — только предложения."
)


# --- git --------------------------------------------------------------------


def _git(repo: str, args: list) -> str:
    proc = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {proc.stderr.strip()}")
    return proc.stdout


def changed_files(repo: str, base: str, head: str) -> list:
    out = _git(repo, ["diff", "--name-status", f"{base}...{head}"])
    files = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            files.append((parts[0].strip(), parts[-1].strip()))
    return files


def get_diff(repo: str, base: str, head: str, limit: int) -> tuple:
    raw = _git(repo, ["diff", "--unified=3", f"{base}...{head}"])
    if len(raw) > limit:
        return raw[:limit] + "\n... [diff усечён]\n", True
    return raw, False


def is_reviewable(path: str) -> bool:
    name = os.path.basename(path)
    if name in SKIP_NAMES or path.endswith(SKIP_SUFFIXES):
        return False
    return not any(seg in {".git", "node_modules", "build", "dist", "vendor", "__pycache__"} for seg in path.split("/"))


# --- мини-RAG ---------------------------------------------------------------


def collect_files(root: str) -> list:
    docs, total = [], 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in sorted(filenames):
            if os.path.splitext(fn)[1].lower() not in TEXT_EXTS:
                continue
            p = os.path.join(dirpath, fn)
            try:
                if os.path.getsize(p) > MAX_FILE_BYTES:
                    continue
                with open(p, encoding="utf-8", errors="ignore") as f:
                    text = f.read()
            except OSError:
                continue
            if text.strip():
                docs.append((os.path.relpath(p, root), text))
                total += len(text)
                if len(docs) >= MAX_FILES or total >= MAX_TOTAL_CHARS:
                    return docs
    return docs


def _chunk(text: str, size: int = 120, overlap: int = 20) -> list:
    words = text.split()
    out, start = [], 0
    while start < len(words):
        end = min(start + size, len(words))
        out.append(" ".join(words[start:end]))
        if end == len(words):
            break
        start += size - overlap
    return out


def chunk_document(rel: str, text: str) -> list:
    """Для .md — по разделам (заголовки), длинные — дорезаем; иначе фиксированные чанки."""
    if rel.endswith(".md"):
        sections, title, buf = [], "", []
        for line in text.splitlines():
            if re.match(r"^#{1,6}\s+", line):
                if title or buf:
                    sections.append((title or "doc", "\n".join(buf).strip()))
                title, buf = line.lstrip("# ").strip(), []
            else:
                buf.append(line)
        if title or buf:
            sections.append((title or "doc", "\n".join(buf).strip()))
        chunks = []
        for sec, body in sections:
            if not body:
                continue
            if len(body.split()) <= 200:
                chunks.append(f"# {sec}\n{body}")
            else:
                chunks += _chunk(body)
        return chunks or [text]
    return _chunk(text) or [text]


def tokenize(s: str) -> list:
    return [t for t in TOKEN_RE.findall(s.lower()) if t not in STOPWORDS]


def build_index(root: str, embedding: str) -> dict:
    chunks = []
    for rel, text in collect_files(root):
        for part in chunk_document(rel, text):
            if part.strip():
                chunks.append({"source": rel, "text": part})
    if not chunks:
        return {"chunks": [], "vectors": [], "idf": {}, "embedding": embedding}
    if embedding == "ollama":
        vectors = _ollama_embed([c["text"] for c in chunks])
        return {"chunks": chunks, "vectors": vectors, "idf": {}, "embedding": "ollama"}
    df = Counter()
    token_sets = []
    for c in chunks:
        toks = set(tokenize(c["text"]))
        token_sets.append(toks)
        for w in toks:
            df[w] += 1
    n = len(chunks)
    idf = {w: math.log((n + 1) / (df[w] + 1)) + 1.0 for w in df}
    vectors = []
    for c, toks in zip(chunks, token_sets):
        freq = Counter(tokenize(c["text"]))
        total = sum(freq.values()) or 1
        raw = {w: (freq[w] / total) * idf[w] for w in toks}
        norm = math.sqrt(sum(v * v for v in raw.values()))
        vectors.append({w: v / norm for w, v in raw.items()} if norm else {})
    return {"chunks": chunks, "vectors": vectors, "idf": idf, "embedding": "tfidf"}


def _ollama_embed(texts: list, batch: int = 32) -> list:
    vectors = []
    for i in range(0, len(texts), batch):
        body = json.dumps({"model": OLLAMA_EMBED_MODEL, "input": texts[i : i + batch]}).encode("utf-8")
        req = urllib.request.Request(OLLAMA_URL + "/api/embed", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            vectors.extend(json.loads(resp.read().decode("utf-8"))["embeddings"])
    return vectors


def _cosine(a, b) -> float:
    if not a or not b:
        return 0.0
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return sum(x * y for x, y in zip(a, b)) / (na * nb) if na and nb else 0.0


def search(index: dict, query: str, top_k: int) -> list:
    chunks = index["chunks"]
    if not chunks:
        return []
    if index["embedding"] == "ollama":
        qv = _ollama_embed([query])[0]
        scores = [_cosine(qv, vec) for vec in index["vectors"]]
    else:
        freq = Counter(tokenize(query))
        total = sum(freq.values()) or 1
        qv = {}
        for w in freq:
            qv[w] = (freq[w] / total) * index["idf"].get(w, 0.0)
        norm = math.sqrt(sum(v * v for v in qv.values()))
        qv = {w: v / norm for w, v in qv.items()} if norm else {}
        scores = [sum(qv.get(w, 0.0) * vec.get(w, 0.0) for w in qv) for vec in index["vectors"]]
    order = sorted(range(len(chunks)), key=lambda i: scores[i], reverse=True)
    return [
        {"source": chunks[i]["source"], "text": chunks[i]["text"][:800], "score": round(scores[i], 4)}
        for i in order[:top_k]
    ]


def build_context(index: dict, files: list, diff: str, top_k: int) -> str:
    paths = [p for _, p in files if is_reviewable(p)]
    terms = list(dict.fromkeys(paths + [os.path.splitext(os.path.basename(p))[0] for p in paths]))
    added = "\n".join(l for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++"))
    query = (" ".join(terms) + " " + added[:1500]).strip()
    results = search(index, query, top_k) if query else []
    return "\n".join(f"[{r['source']}]\n{r['text']}\n" for r in results)


# --- LLM --------------------------------------------------------------------


def chat(messages: list, cfg: dict) -> str:
    body = {"model": cfg["model"], "messages": messages, "temperature": cfg.get("temperature", 0.2)}
    if cfg.get("reasoning_effort"):
        body["reasoning_effort"] = cfg["reasoning_effort"]
    req = urllib.request.Request(
        cfg["endpoint"], data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    if cfg.get("api_key"):
        req.add_header("Authorization", f"Bearer {cfg['api_key']}")
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]


def review(files: list, diff: str, context: str, cfg: dict) -> str:
    files_txt = "\n".join(f"- {st} {p}" for st, p in files) or "(нет)"
    user = (
        f"Изменённые файлы:\n{files_txt}\n\nDiff:\n```diff\n{diff}\n```\n\n"
        f"Контекст проекта (RAG, документация и код):\n{context or '(нет)'}\n\n"
        "Дай ревью строго в заданном формате."
    )
    return chat([{"role": "system", "content": REVIEW_SYSTEM}, {"role": "user", "content": user}], cfg)


def post_comment(text: str) -> None:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    pr = os.environ.get("PR_NUMBER", "")
    if not pr:
        event_path = os.environ.get("GITHUB_EVENT_PATH", "")
        if event_path and os.path.exists(event_path):
            try:
                with open(event_path, encoding="utf-8") as f:
                    ev = json.load(f)
                pr = str(ev.get("pull_request", {}).get("number") or ev.get("number") or "")
            except Exception:
                pr = ""
    if not (repo and token and pr):
        print("[review] --post пропущен: нет GITHUB_REPOSITORY/GITHUB_TOKEN/номера PR", file=sys.stderr)
        return
    body = json.dumps({"body": "## 🤖 AI Code Review\n\n" + text}).encode("utf-8")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/issues/{pr}/comments", data=body, method="POST",
        headers={
            "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        print(f"[review] комментарий опубликован (HTTP {resp.status})", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description="AI-ревью PR (День 32, самодостаточный)")
    ap.add_argument("--base", default=os.environ.get("GITHUB_BASE_SHA") or "HEAD~1")
    ap.add_argument("--head", default=os.environ.get("GITHUB_SHA") or "HEAD")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--embedding", choices=["tfidf", "ollama"], default="tfidf")
    ap.add_argument("--top-k", type=int, default=6)
    ap.add_argument("--max-diff-chars", type=int, default=20000)
    ap.add_argument("--endpoint", default=os.environ.get("PR_REVIEW_ENDPOINT") or os.environ.get("LLM_ENDPOINT") or DEFAULT_ENDPOINT)
    ap.add_argument("--model", default=os.environ.get("PR_REVIEW_MODEL") or os.environ.get("LLM_MODEL") or DEFAULT_MODEL)
    ap.add_argument("--api-key", default=os.environ.get("PR_REVIEW_API_KEY") or os.environ.get("LLM_API_KEY") or "")
    ap.add_argument("--reasoning-effort", default=os.environ.get("PR_REVIEW_REASONING_EFFORT", ""),
                    help="reasoning_effort для reasoning-моделей (gpt-oss): low|medium|high; пусто — не слать")
    ap.add_argument("--out", default="")
    ap.add_argument("--post", action="store_true")
    args = ap.parse_args()

    repo = os.path.abspath(args.repo)
    cfg = {"endpoint": args.endpoint, "model": args.model, "api_key": args.api_key,
           "reasoning_effort": args.reasoning_effort}

    try:
        files = changed_files(repo, args.base, args.head)
    except Exception as exc:
        print(f"[review] не удалось получить diff: {exc}", file=sys.stderr)
        return 1
    reviewable = [f for f in files if is_reviewable(f[1])]
    if not reviewable:
        print("[review] нет изменений для ревью.")
        return 0

    diff, truncated = get_diff(repo, args.base, args.head, args.max_diff_chars)
    print(f"[review] файлов: {len(reviewable)} | diff {len(diff)} симв.{' (усечён)' if truncated else ''}", file=sys.stderr)

    index = build_index(repo, args.embedding)
    print(f"[review] RAG: {len(index['chunks'])} чанков ({index['embedding']})", file=sys.stderr)
    context = build_context(index, reviewable, diff, args.top_k)

    try:
        text = review(reviewable, diff, context, cfg)
    except Exception as exc:
        print(f"[review] LLM-вызов не удался ({exc}); повтор с усечённым контекстом", file=sys.stderr)
        try:
            text = review(reviewable, diff[:3000], context[:2000], cfg)
        except Exception as exc2:
            print(f"[review] ревью не получено: {exc2}", file=sys.stderr)
            return 0
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"[review] сохранено: {args.out}", file=sys.stderr)
    print(text)
    if args.post:
        post_comment(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
