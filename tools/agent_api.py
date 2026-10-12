#!/usr/bin/env python3
"""Локальный HTTP REST агент-сервера (День 31+). stdlib, без зависимостей.

Запуск:
    python tools/agent_api.py                 # http://127.0.0.1:8090
    AGENT_HOST=0.0.0.0 AGENT_API_TOKEN=secret python tools/agent_api.py   # как API

Эндпоинты:
    GET  /health
    GET  /projects
    POST /index   {"path": "/abs/other/project", "project": "name", "strategy": "structure"}
    GET  /search  ?project=name&q=...&top_k=5&min_score=0.0&rerank=true
    POST /ask     {"project": "name", "question": "..."}

Авторизация: если задан AGENT_API_TOKEN, нужен заголовок
`Authorization: Bearer <token>` или `X-API-Key: <token>`. Пусто — открыт (локальный режим).
Локальная LLM: AGENT_LLM_ENDPOINT / AGENT_LLM_MODEL / AGENT_LLM_API_KEY (см. agent_core).
"""

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import agent_core as core

HOST = os.environ.get("AGENT_HOST", "127.0.0.1")
PORT = int(os.environ.get("AGENT_PORT", "8090"))
API_TOKEN = os.environ.get("AGENT_API_TOKEN", "")


def _log(msg: str) -> None:
    print(f"[api] {msg}", flush=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "LocalAgentAPI/1.0"

    # --- helpers ---
    def _json(self, code: int, payload) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self) -> bool:
        if not API_TOKEN:
            return True
        got = self.headers.get("X-API-Key", "")
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            got = auth[len("Bearer ") :]
        return got == API_TOKEN

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def log_message(self, fmt, *args):  # тише стандартного лога
        _log(fmt % args)

    def _guard(self, method: str) -> bool:
        if not self._auth_ok():
            self._json(401, {"ok": False, "error": "unauthorized"})
            _log(f"{method} {self.path} -> 401")
            return False
        return True

    # --- routes ---
    def do_GET(self):
        if not self._guard("GET"):
            return
        parsed = urlparse(self.path)
        path, qs = parsed.path, parse_qs(parsed.query)
        if path == "/health":
            return self._json(200, {
                "ok": True,
                "model": core.LLM_MODEL,
                "endpoint": core.LLM_ENDPOINT,
                "projects": len(core.list_projects()),
            })
        if path == "/projects":
            return self._json(200, {"ok": True, "projects": core.list_projects()})
        if path == "/search":
            project = (qs.get("project") or [""])[0]
            query = (qs.get("q") or [""])[0]
            if not project or not query:
                return self._json(400, {"ok": False, "error": "нужны project и q"})
            res = core.search_project(
                project, query,
                top_k=int((qs.get("top_k") or ["5"])[0]),
                min_score=float((qs.get("min_score") or ["0"])[0]),
                rerank=(qs.get("rerank") or ["true"])[0].lower() != "false",
            )
            return self._json(200 if res.get("ok") else 404, res)
        return self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._guard("POST"):
            return
        path = urlparse(self.path).path
        data = self._body()
        if path == "/index":
            src = data.get("path", "").strip()
            if not src:
                return self._json(400, {"ok": False, "error": "нужен path"})
            res = core.index_project(
                src,
                project=data.get("project"),
                strategy=data.get("strategy", "structure"),
                embedding=data.get("embedding", "ollama"),
                chunk_size=int(data.get("chunk_size", 150)),
                overlap=int(data.get("overlap", 30)),
            )
            return self._json(200 if res.get("ok") else 400, res)
        if path == "/ask":
            project = data.get("project", "").strip()
            question = data.get("question", "").strip()
            if not project or not question:
                return self._json(400, {"ok": False, "error": "нужны project и question"})
            res = core.answer(
                project, question,
                top_k=int(data.get("top_k", 5)),
                min_score=float(data.get("min_score", 0.0)),
                rerank=bool(data.get("rerank", True)),
            )
            return self._json(200 if res.get("ok") else 404, res)
        return self._json(404, {"ok": False, "error": "not found"})


def main() -> None:
    _log(f"агент-сервер: http://{HOST}:{PORT} | модель {core.LLM_MODEL} @ {core.LLM_ENDPOINT} | auth={bool(API_TOKEN)}")
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        _log("остановлено")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
