#!/usr/bin/env python3
"""День 25: мини-чат с RAG + источниками + памятью задачи (CLI).

Каждый ход: вопрос → (опц.) rewrite → поиск в индексе (filter/rerank) → контекст →
LLM отвечает в строгом JSON {answer, sources, quotes} (с валидацией и одним retry) →
печать «Ответ → Источники → Цитаты». История диалога и память задачи (goal / clarified /
constraints / terms) сохраняются в tools/rag_chat/state_<session>.json.

Проверка длинных сценариев: --script tools/rag_chat/scenarios/<file>.json — прогоняет
диалог из 10–15 сообщений и пишет отчёт (есть ли источники каждый ход, сохраняется ли цель).

Режимы «не знаю»: если ни один чанк не прошёл порог релевантности — ассистент отвечает
«Не знаю» и просит уточнение (без вызова LLM).

Конфигурация LLM (env; ключ НЕ в репозитории): LLM_API_KEY, LLM_ENDPOINT/LLM_BASE_URL, LLM_MODEL.

Запуск:
  .venv/bin/python tools/rag_chat.py --source docs/DnD --strategy structure
  .venv/bin/python tools/rag_chat.py --local            # полностью локально (День 28): ollama, без ключа
  .venv/bin/python tools/rag_chat.py --script tools/rag_chat/scenarios/dnd_character.json
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)

import document_indexer as di  # noqa: E402
import rag_eval as re_  # noqa: E402  (complete, llm_config, rewrite_query, is_faithful)

CHAT_DIR = os.path.join(TOOLS, "rag_chat")
REPORTS_DIR = os.path.join(CHAT_DIR, "reports")
HISTORY_WINDOW = 8

ANSWER_SYS = (
    "Ты — ассистент по базе знаний. Отвечай, опираясь ТОЛЬКО на приведённый контекст. "
    "Не добавляй факты вне контекста. Если ответа в контексте нет — ответь «Не знаю» и попроси уточнение."
)
ANSWER_JSON = (
    "Верни ТОЛЬКО JSON: {\"answer\": \"...\", "
    "\"sources\": [{\"n\":1,\"source\":\"...\",\"section\":\"...\",\"chunk_id\":\"...\"}], "
    "\"quotes\": [{\"text\":\"дословный фрагмент из контекста\",\"n\":1}]}. "
    "sources — использованные чанки (n — номер из контекста), quotes — 1–2 дословных фрагмента. "
    "Если ответа нет: {\"answer\":\"Не знаю\",\"sources\":[],\"quotes\":[]}."
)
TASK_SYS = (
    "Ты ведёшь ПАМЯТЬ ЗАДАЧИ диалога. По истории обнови строго JSON: "
    "{\"goal\":\"цель диалога\",\"clarified\":[\"что пользователь уже уточнил\"],"
    "\"constraints\":[\"ограничения\"],\"terms\":[\"зафиксированные термины/названия\"]}. "
    "Сохраняй уже накопленное, добавляй новое, не выдумывай. Только JSON."
)


def strip_json(s: str):
    s = s.strip().removeprefix("```json").removesuffix("```").strip()
    a, b = s.find("{"), s.rfind("}")
    if a < 0 or b <= a:
        return None
    try:
        return json.loads(s[a:b + 1])
    except Exception:
        return None


class RagChat:
    def __init__(self, cfg: dict, index: dict, endpoint: str, model: str, key: str):
        self.cfg = cfg
        self.index = index
        self.endpoint = endpoint
        self.model = model
        self.key = key
        self.history = []  # [{"role","content"}]
        self.task_state = {"goal": "", "clarified": [], "constraints": [], "terms": []}

    # --- RAG ---
    def retrieve(self, question: str):
        query = question
        if self.cfg["rewrite"]:
            query = re_.rewrite_query(self.endpoint, self.model, self.key, question)
        results = di.search(
            self.index, query, self.cfg["top_k"],
            fetch_k=self.cfg["fetch_k"], min_score=self.cfg["min_score"], rerank=self.cfg["rerank"],
        )
        return query, results

    def _context(self, results):
        if not results:
            return "Контекст из базы знаний: релевантных данных не найдено."
        parts = ["Контекст из базы знаний:"]
        for i, (score, m) in enumerate(results, 1):
            parts.append(f"[{i}] {m['source']} :: {m['section']} (chunk {m['chunk_id']}, score {score:.3f})\n{m['snippet']}")
        return "\n".join(parts)

    def _task_block(self):
        ts = self.task_state
        return (
            "Память задачи:\n"
            f"- Цель: {ts.get('goal','') or '—'}\n"
            f"- Уточнено: {', '.join(ts.get('clarified',[])) or '—'}\n"
            f"- Ограничения: {', '.join(ts.get('constraints',[])) or '—'}\n"
            f"- Термины: {', '.join(ts.get('terms',[])) or '—'}"
        )

    def _messages(self, results):
        msgs = [{"role": "system", "content": ANSWER_SYS}, {"role": "system", "content": self._task_block()},
                {"role": "system", "content": self._context(results)},
                {"role": "system", "content": ANSWER_JSON}]
        for m in self.history[-HISTORY_WINDOW:]:
            msgs.append(m)
        return msgs

    def _answer_json(self, results):
        msgs = self._messages(results)
        raw = re_.complete(self.endpoint, self.model, self.key, msgs)
        data = strip_json(raw)
        if not self._valid(data, results):
            retry = msgs + [{"role": "assistant", "content": raw},
                            {"role": "user", "content": "Формат неверный или цитаты не из контекста. Исправь строго по схеме."}]
            raw2 = re_.complete(self.endpoint, self.model, self.key, retry)
            data2 = strip_json(raw2)
            if self._valid(data2, results):
                return data2
            return data if data else {"answer": raw.strip(), "sources": [], "quotes": []}
        return data

    def _valid(self, data, results):
        if not data or not str(data.get("answer", "")).strip():
            return False
        if "не знаю" in str(data["answer"]).lower():
            return True
        if not data.get("sources") or not data.get("quotes"):
            return False
        ctx = re.sub(r"\s+", " ", " ".join(m["snippet"] for _, m in results)).lower()
        for q in data["quotes"]:
            t = re.sub(r"\s+", " ", str(q.get("text", "")).strip()).lower()
            if not t or t not in ctx:
                return False
        return True

    def render(self, data, results):
        ans = str(data.get("answer", "")).strip()
        src = data.get("sources", []) or []
        quotes = data.get("quotes", []) or []
        # «всегда источники»: если модель не дала, показываем найденные чанки.
        if not src and results and "не знаю" not in ans.lower():
            src = [{"n": i + 1, "source": m["source"], "section": m["section"], "chunk_id": m["chunk_id"]}
                   for i, (_, m) in enumerate(results[:self.cfg["top_k"]])]
        out = [ans]
        if src:
            out.append("\nИсточники:")
            for s in src:
                out.append(f"[{s.get('n','')}] {s.get('source','')} :: {s.get('section','')} ({s.get('chunk_id','')})")
        if quotes:
            out.append("\nЦитаты:")
            for q in quotes:
                out.append(f"«{str(q.get('text','')).strip()}» [{q.get('n','')}]")
        return "\n".join(out)

    def update_task_state(self):
        hist = "\n".join(f"{m['role']}: {m['content']}" for m in self.history[-HISTORY_WINDOW:])
        msgs = [{"role": "system", "content": TASK_SYS},
                {"role": "user", "content": f"Текущая память: {json.dumps(self.task_state, ensure_ascii=False)}\n\nИстория:\n{hist}"}]
        try:
            data = strip_json(re_.complete(self.endpoint, self.model, self.key, msgs))
            if data:
                self.task_state = {
                    "goal": data.get("goal", self.task_state.get("goal", "")),
                    "clarified": data.get("clarified", self.task_state.get("clarified", [])),
                    "constraints": data.get("constraints", self.task_state.get("constraints", [])),
                    "terms": data.get("terms", self.task_state.get("terms", [])),
                }
        except Exception:
            pass

    def ask(self, question: str) -> dict:
        self.history.append({"role": "user", "content": question})
        query, results = self.retrieve(question)
        if not results:
            data = {"answer": "Не знаю. В базе нет релевантной информации — уточните вопрос.",
                    "sources": [], "quotes": []}
            used = False
        else:
            data = self._answer_json(results)
            used = True
        text = self.render(data, results)
        self.history.append({"role": "assistant", "content": text})
        self.update_task_state()
        return {"question": question, "rewritten": query if query != question else None,
                "answer": str(data.get("answer", "")).strip(), "rendered": text,
                "sources": data.get("sources", []), "quotes": data.get("quotes", []),
                "chunks": [{"source": m["source"], "section": m["section"], "chunk_id": m["chunk_id"], "score": s}
                           for s, m in results],
                "used_context": used, "task_state": self.task_state}

    def save_state(self, session: str):
        os.makedirs(CHAT_DIR, exist_ok=True)
        with open(os.path.join(CHAT_DIR, f"state_{session}.json"), "w", encoding="utf-8") as f:
            json.dump({"history": self.history, "task_state": self.task_state}, f, ensure_ascii=False, indent=1)

    def load_state(self, session: str):
        path = os.path.join(CHAT_DIR, f"state_{session}.json")
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                st = json.load(f)
            self.history = st.get("history", [])
            self.task_state = st.get("task_state", self.task_state)


def build_cfg(args):
    return {
        "top_k": args.top_k, "fetch_k": args.fetch_k, "min_score": args.min_score,
        "rerank": args.rerank, "rewrite": args.rewrite,
    }


def run_script(chat: RagChat, script_path: str) -> int:
    with open(script_path, encoding="utf-8") as f:
        script = json.load(f)
    turns = script["turns"] if isinstance(script, dict) else script
    name = script.get("name", os.path.basename(script_path)) if isinstance(script, dict) else os.path.basename(script_path)
    records = []
    print(f"=== Сценарий: {name} ({len(turns)} ходов) ===")
    for i, turn in enumerate(turns, 1):
        rec = chat.ask(turn)
        has_src = bool(rec["sources"]) or bool(rec["chunks"])
        rec["has_sources"] = has_src
        records.append(rec)
        print(f"\n[{i}] Пользователь: {turn}")
        print(rec["rendered"][:600])
        print(f"    (цель: {rec['task_state'].get('goal','—')})")

    with_src = sum(1 for r in records if r["has_sources"])
    goal = records[-1]["task_state"].get("goal", "") if records else ""
    print(f"\n=== Итог: источники {with_src}/{len(records)} ходов; цель: {goal!r}")

    os.makedirs(REPORTS_DIR, exist_ok=True)
    lines = [f"# Сценарий: {name}", "", f"Ходов: {len(turns)} · ходов с источниками: {with_src}/{len(records)}",
             f"Итоговая цель: {goal}", f"Итоговая память: {json.dumps(records[-1]['task_state'], ensure_ascii=False) if records else ''}", ""]
    for i, r in enumerate(records, 1):
        lines += [f"## {i}. {r['question']}", f"- Источники: {'да' if r['has_sources'] else 'нет'}", "",
                  "```", r["rendered"], "```", ""]
    with open(os.path.join(REPORTS_DIR, f"{name}.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"Отчёт: {os.path.join(REPORTS_DIR, name + '.md')}")
    return 0 if with_src == len(records) else 2


def main() -> int:
    ap = argparse.ArgumentParser(description="Мини-чат с RAG + памятью задачи (День 25)")
    ap.add_argument("--source", default="docs/DnD")
    ap.add_argument("--strategy", choices=["fixed", "structure"], default="structure")
    ap.add_argument("--embedding", choices=["ollama", "tfidf"], default="ollama")
    ap.add_argument("--top-k", type=int, default=4)
    ap.add_argument("--fetch-k", type=int, default=20)
    ap.add_argument("--min-score", type=float, default=0.0)
    ap.add_argument("--rerank", action="store_true")
    ap.add_argument("--rewrite", action="store_true")
    ap.add_argument("--script", help="JSON-сценарий (10–15 сообщений) для прогона")
    ap.add_argument("--session", default="default")
    ap.add_argument("--local", action="store_true",
                    help="Полностью локально (День 28): генерация через ollama, API-ключ не нужен")
    ap.add_argument("--local-endpoint", default="http://localhost:11434/v1/chat/completions")
    ap.add_argument("--local-model", default="llama3.2:3b")
    args = ap.parse_args()

    index_path = di.index_path(args.source, args.strategy, args.embedding)
    if not os.path.exists(index_path):
        print(f"Индекс не найден: {index_path}. Соберите: "
              f"python tools/document_indexer.py --source {args.source} --strategy {args.strategy}")
        return 1
    index = di.load_index(args.source, args.strategy, args.embedding)
    endpoint, model, key = re_.llm_config()
    if args.local:
        # Локальная генерация: эндпоинт Ollama, пустой ключ (локальные серверы его не требуют).
        endpoint, model, key = args.local_endpoint, args.local_model, ""
    if not key and not args.local:
        print("Нет LLM_API_KEY (env). Ключ не хранится в репозитории.")
        return 1
    where = "локально (ollama)" if args.local else "облако"
    print(f"Индекс: {index_path} ({len(index['chunks'])} чанков) · генерация: {where} · модель {model}")

    chat = RagChat(build_cfg(args), index, endpoint, model, key)

    if args.script:
        return run_script(chat, args.script)

    print("Мини-чат (RAG + память задачи). Команды: /state /sources /clear /exit")
    while True:
        try:
            line = input("\nВы: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue
        if line in ("/exit", "/quit"):
            break
        if line == "/state":
            print(json.dumps(chat.task_state, ensure_ascii=False, indent=1))
            continue
        if line == "/clear":
            chat.history.clear()
            chat.task_state = {"goal": "", "clarified": [], "constraints": [], "terms": []}
            print("История и память очищены.")
            continue
        rec = chat.ask(line)
        print("\nАгент:", rec["rendered"])
        chat.save_state(args.session)
    return 0


if __name__ == "__main__":
    sys.exit(main())