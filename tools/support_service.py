#!/usr/bin/env python3
"""День 33: мини-сервис поддержки пользователей.

Отвечает на вопросы о продукте, опираясь на:
  * RAG по базе знаний (FAQ/документация) — `tools/support/kb/`;
  * контекст пользователя/тикета из CRM (JSON) — `tools/support/crm.json`.

Режимы:
  CLI:        python tools/support_service.py --ticket T-1001 "Почему не работает авторизация?"
  HTTP:       python tools/support_service.py --serve           # http://127.0.0.1:8095
  MCP:        python tools/support_mcp_server.py                 # инструменты CRM + RAG

Конфиг LLM (env): AGENT_LLM_ENDPOINT / AGENT_LLM_MODEL / AGENT_LLM_API_KEY
(или LLM_*). Эмбеддинги: SUPPORT_EMBEDDING=tfidf|ollama (дефолт tfidf — без внешних сервисов).
"""

import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import agent_core as core

ROOT = os.path.dirname(os.path.abspath(__file__))
SUPPORT_DIR = os.path.join(ROOT, "support")
CRM_PATH = os.path.join(SUPPORT_DIR, "crm.json")
KB_DIR = os.path.join(SUPPORT_DIR, "kb")
PROJECT = "support"
EMBEDDING = os.environ.get("SUPPORT_EMBEDDING", "ollama")
STRATEGY = "structure"

SYSTEM = (
    "Ты — ассистент поддержки продукта. Отвечай вежливо, кратко и по делу, опираясь ТОЛЬКО на "
    "базу знаний (FAQ/документация) и данные обращения (тикет/пользователь). Если в базе знаний "
    "есть подходящая инструкция — приведи её конкретные шаги решения (нумерованным списком). "
    "Учитывай контекст тикета: версию приложения, устройство, прошлые сообщения. Если данных "
    "реально не хватает — в конце задай ОДИН уточняющий вопрос. Не выдумывай функции продукта."
)


# --- CRM (JSON) -------------------------------------------------------------


def load_crm(path: str = CRM_PATH) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def get_user(user_id: str):
    if not user_id:
        return None
    for u in load_crm().get("users", []):
        if u.get("id") == user_id:
            return u
    return None


def get_ticket(ticket_id: str):
    if not ticket_id:
        return None
    for t in load_crm().get("tickets", []):
        if t.get("id") == ticket_id:
            return t
    return None


def list_tickets(user_id: str = "", status: str = "") -> list:
    out = []
    for t in load_crm().get("tickets", []):
        if user_id and t.get("user_id") != user_id:
            continue
        if status and t.get("status") != status:
            continue
        out.append(t)
    return out


def _ticket_context(ticket: dict, user: dict) -> str:
    lines = []
    if user:
        lines.append(
            f"Пользователь: {user.get('name')} ({user.get('email')}), тариф {user.get('plan')}, "
            f"устройство {user.get('device')}, версия {user.get('app_version')}, с {user.get('since')}"
        )
    if ticket:
        lines.append(
            f"Тикет {ticket.get('id')}: статус {ticket.get('status')}, приоритет {ticket.get('priority')}, "
            f"продукт {ticket.get('product')}, версия {ticket.get('app_version')}, "
            f"создан {ticket.get('created_at')}, теги {ticket.get('tags')}"
        )
        lines.append(f"Тема: {ticket.get('subject')}")
        for m in ticket.get("messages", []):
            lines.append(f"  [{m.get('role')}] {m.get('text')}")
    return "\n".join(lines)


# --- RAG по базе знаний -----------------------------------------------------


def ensure_index(reindex: bool = False) -> dict:
    """Строит индекс базы знаний (семантические ollama-эмбеддинги; фолбэк — tfidf)."""
    global EMBEDDING
    if not reindex and core.load_project_index(PROJECT, STRATEGY, EMBEDDING) is not None:
        return {"ok": True, "cached": True, "embedding": EMBEDDING}
    res = core.index_project(KB_DIR, project=PROJECT, strategy=STRATEGY, embedding=EMBEDDING)
    if not res.get("ok") and EMBEDDING != "tfidf":
        print(f"[support] ollama-эмбеддинги недоступны ({res.get('reason')}); fallback tfidf", file=sys.stderr)
        EMBEDDING = "tfidf"
        res = core.index_project(KB_DIR, project=PROJECT, strategy=STRATEGY, embedding="tfidf")
    return res


def search_faq(query: str, top_k: int = 5) -> list:
    ensure_index()
    res = core.search_project(PROJECT, query, top_k=top_k, rerank=True, strategy=STRATEGY, embedding=EMBEDDING)
    return res.get("results", []) if res.get("ok") else []


# --- Ответ ------------------------------------------------------------------


def answer(question: str, user_id: str = "", ticket_id: str = "", top_k: int = 5) -> dict:
    ticket = get_ticket(ticket_id)
    if ticket and not user_id:
        user_id = ticket.get("user_id", "")
    user = get_user(user_id)

    chunks = search_faq(question, top_k)
    kb = "\n\n".join(f"[{c['source']} :: {c.get('section', '')}]\n{c.get('snippet', '')}" for c in chunks)
    ticket_ctx = _ticket_context(ticket, user) or "(обращение не указано)"

    user_msg = (
        f"Данные обращения:\n{ticket_ctx}\n\n"
        f"Релевантные фрагменты базы знаний:\n{kb or '(ничего не найдено)'}\n\n"
        f"Вопрос пользователя: {question}\n\n"
        "Ответь пользователю. Если в базе знаний есть подходящая инструкция — приведи её шаги "
        "нумерованным списком. Учитывай контекст обращения (версия, устройство)."
    )
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": user_msg},
    ]
    text = core.chat(messages, temperature=0.2)
    return {
        "answer": text,
        "sources": [{"source": c["source"], "section": c.get("section", "")} for c in chunks],
        "user": user,
        "ticket": {"id": ticket.get("id"), "subject": ticket.get("subject"), "status": ticket.get("status")} if ticket else None,
    }


# --- HTTP -------------------------------------------------------------------


def _serve(host: str, port: int) -> None:
    class Handler(BaseHTTPRequestHandler):
        server_version = "SupportService/1.0"

        def _json(self, code, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            print(f"[support] {fmt % args}", flush=True)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/health":
                return self._json(200, {"ok": True, "model": core.LLM_MODEL, "kb": PROJECT})
            if path.startswith("/ticket/"):
                t = get_ticket(path.rsplit("/", 1)[-1])
                return self._json(200 if t else 404, t or {"error": "not found"})
            return self._json(404, {"error": "not found"})

        def do_POST(self):
            if self.path.split("?")[0] != "/ask":
                return self._json(404, {"error": "not found"})
            length = int(self.headers.get("Content-Length", "0") or 0)
            try:
                data = json.loads(self.rfile.read(length) or b"{}")
            except Exception:
                return self._json(400, {"error": "invalid JSON body"})
            question = str(data.get("question", "")).strip()
            if not question:
                return self._json(400, {"error": "нужен question"})
            try:
                res = answer(question, str(data.get("user_id", "")), str(data.get("ticket_id", "")))
            except Exception as exc:
                return self._json(502, {"error": str(exc)})
            return self._json(200, res)

    print(f"[support] сервис поддержки: http://{host}:{port} | модель {core.LLM_MODEL} | KB {PROJECT}")
    srv = ThreadingHTTPServer((host, port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


def main() -> int:
    ap = argparse.ArgumentParser(description="Мини-сервис поддержки (День 33)")
    ap.add_argument("question", nargs="?", default="", help="вопрос пользователя")
    ap.add_argument("--user", default="", help="id пользователя")
    ap.add_argument("--ticket", default="", help="id тикета")
    ap.add_argument("--top-k", type=int, default=5)
    ap.add_argument("--reindex", action="store_true", help="перестроить индекс базы знаний")
    ap.add_argument("--serve", action="store_true", help="запустить HTTP-сервис")
    ap.add_argument("--host", default=os.environ.get("SUPPORT_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("SUPPORT_PORT", "8095")))
    ap.add_argument("--model", default="", help="модель LLM (иначе AGENT_LLM_MODEL, дефолт llama3.2:3b)")
    ap.add_argument("--endpoint", default="", help="OpenAI-совместимый endpoint (иначе AGENT_LLM_ENDPOINT)")
    ap.add_argument("--api-key", default="", help="API-ключ (иначе AGENT_LLM_API_KEY)")
    args = ap.parse_args()

    # Переопределяем LLM-конфиг agent_core (по умолчанию — из env).
    if args.model:
        core.LLM_MODEL = args.model
    if args.endpoint:
        core.LLM_ENDPOINT = args.endpoint
    if args.api_key:
        core.LLM_API_KEY = args.api_key

    if args.serve:
        ensure_index(args.reindex)
        _serve(args.host, args.port)
        return 0

    if args.reindex:
        print("[support] индекс:", ensure_index(True), file=sys.stderr)
    if not args.question:
        print("Укажите вопрос: python tools/support_service.py --ticket T-1001 \"...\"", file=sys.stderr)
        return 2
    res = answer(args.question, args.user, args.ticket, args.top_k)
    print(res["answer"])
    if res["sources"]:
        print("\nИсточники:")
        for s in res["sources"]:
            print(f"- {s['source']} :: {s['section']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
