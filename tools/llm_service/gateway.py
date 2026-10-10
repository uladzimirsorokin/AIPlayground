#!/usr/bin/env python3
"""День 30: приватный AI-сервис — HTTP-шлюз поверх локальной LLM (Ollama).

Даёт OpenAI-совместимый `/v1/chat/completions`, но с базовыми ограничениями
приватного сервиса:

  * аутентификация по API-ключу (Bearer / X-API-Key);
  * rate limit (запросов в минуту на ключ/IP) → 429 + Retry-After;
  * лимит контекста (оценка prompt-токенов) → 413;
  * лимит выходных токенов (clamp max_tokens) → усечение до MAX_OUTPUT_TOKENS;
  * health/models эндпоинты и заголовки X-RateLimit-*.

Слушает 0.0.0.0 — доступен по сети (VPS / домашний сервер).

Конфигурация через env:
  UPSTREAM          URL Ollama (default http://127.0.0.1:11434)
  GATEWAY_HOST      default 0.0.0.0
  GATEWAY_PORT      default 8080
  GATEWAY_API_KEY   ключ доступа (если не задан — сгенерируется и напечатается)
  RATE_LIMIT_PER_MIN default 20
  MAX_CONTEXT_TOKENS default 8192
  MAX_OUTPUT_TOKENS  default 1024
  DEFAULT_MODEL      default gpt-oss:20b

Запуск:
  GATEWAY_API_KEY=secret .venv/bin/python tools/llm_service/gateway.py
"""

import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

UPSTREAM = os.environ.get("UPSTREAM", "http://127.0.0.1:11434").rstrip("/")
HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")
PORT = int(os.environ.get("GATEWAY_PORT", "8080"))
API_KEY = os.environ.get("GATEWAY_API_KEY") or secrets.token_urlsafe(24)
RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_MIN", "20"))
MAX_CONTEXT = int(os.environ.get("MAX_CONTEXT_TOKENS", "8192"))
MAX_OUTPUT = int(os.environ.get("MAX_OUTPUT_TOKENS", "1024"))
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "gpt-oss:20b")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


class RateLimiter:
    """Скользящее окно 60с на ключ (или IP)."""

    def __init__(self, limit: int):
        self.limit = limit
        self.hits: dict[str, list] = {}
        self.lock = threading.Lock()

    def check(self, key: str):
        now = time.time()
        with self.lock:
            dq = self.hits.setdefault(key, [])
            while dq and dq[0] <= now - 60:
                dq.pop(0)
            if len(dq) >= self.limit:
                return False, 0, int(60 - (now - dq[0])) + 1
            dq.append(now)
            return True, self.limit - len(dq), 0


limiter = RateLimiter(RATE_LIMIT)


def estimate_tokens(messages) -> int:
    chars = sum(len(str(m.get("content", ""))) for m in messages if isinstance(m, dict))
    return chars // 4


def upstream(path: str, method: str = "GET", body: dict = None, timeout: int = 300):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        UPSTREAM + path, data=data, method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PrivateLLMGateway/1.0"

    def log_message(self, fmt, *args):  # noqa: A003
        log(f"{self.address_string()} {fmt % args}")

    def _send(self, code: int, obj: dict, headers: dict = None) -> None:
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        for k, v in (headers or {}).items():
            self.send_header(k, str(v))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _auth(self) -> bool:
        token = ""
        auth = self.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            token = auth[7:].strip()
        token = token or self.headers.get("X-API-Key", "").strip()
        return secrets.compare_digest(token, API_KEY)

    def _client_key(self) -> str:
        auth = self.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            return auth[7:].strip() or self.address_string()
        return self.address_string()

    def do_GET(self):  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            try:
                tags = upstream("/api/tags", timeout=5)
                models = [m["name"] for m in tags.get("models", [])]
                self._send(200, {"status": "ok", "upstream": UPSTREAM, "models": models,
                                 "rate_limit_per_min": RATE_LIMIT,
                                 "max_context_tokens": MAX_CONTEXT,
                                 "max_output_tokens": MAX_OUTPUT})
            except Exception as e:
                self._send(503, {"status": "upstream_unavailable", "error": str(e)})
            return
        if path in ("/v1/models", "/models"):
            try:
                tags = upstream("/api/tags", timeout=10)
                data = [{"id": m["name"], "object": "model", "owned_by": "local"}
                        for m in tags.get("models", [])]
                self._send(200, {"object": "list", "data": data})
            except Exception as e:
                self._send(503, {"error": {"message": str(e)}})
            return
        self._send(404, {"error": {"message": f"not found: {path}"}})

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        if path != "/v1/chat/completions":
            self._send(404, {"error": {"message": f"not found: {path}"}})
            return
        if not self._auth():
            self._send(401, {"error": {"message": "invalid or missing API key"}},
                       {"WWW-Authenticate": "Bearer"})
            return
        ok, remaining, retry = limiter.check(self._client_key())
        if not ok:
            log(f"rate limit hit for {self._client_key()} (retry {retry}s)")
            self._send(429, {"error": {"message": "rate limit exceeded",
                                       "retry_after_seconds": retry}},
                       {"Retry-After": retry, "X-RateLimit-Limit": RATE_LIMIT,
                        "X-RateLimit-Remaining": 0})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            self._send(400, {"error": {"message": "invalid JSON body"}})
            return

        body.setdefault("model", DEFAULT_MODEL)
        messages = body.get("messages", [])
        est = estimate_tokens(messages)
        if est > MAX_CONTEXT:
            self._send(413, {"error": {"message": f"prompt ~{est} tokens exceeds "
                                                  f"max_context_tokens={MAX_CONTEXT}"},
                             "estimated_prompt_tokens": est})
            return

        wanted = body.get("max_tokens")
        clamped = wanted is None or wanted <= 0 or wanted > MAX_OUTPUT
        body["max_tokens"] = MAX_OUTPUT
        if body.get("stream"):
            body["stream"] = False  # шлюз отдаёт не-стриминговый ответ

        try:
            result = upstream("/v1/chat/completions", "POST", body)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")
            self._send(e.code, {"error": {"message": detail or str(e)}})
            return
        except Exception as e:
            self._send(502, {"error": {"message": f"upstream error: {e}"}})
            return

        log(f"chat model={body['model']} prompt~{est}tok out<={MAX_OUTPUT} "
            f"remaining={remaining-1}")
        self._send(200, result, {"X-RateLimit-Limit": RATE_LIMIT,
                                 "X-RateLimit-Remaining": max(remaining - 1, 0),
                                 "X-Max-Context-Tokens": MAX_CONTEXT,
                                 "X-Max-Output-Tokens": MAX_OUTPUT,
                                 "X-Max-Tokens-Clamped": str(clamped).lower()})


def main():
    log("=== Private LLM gateway ===")
    log(f"upstream={UPSTREAM} listen={HOST}:{PORT}")
    log(f"rate_limit={RATE_LIMIT}/min max_context={MAX_CONTEXT}tok max_output={MAX_OUTPUT}tok")
    log(f"API key: {API_KEY}")
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("stopping…")


if __name__ == "__main__":
    main()
