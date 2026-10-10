#!/usr/bin/env python3
"""День 30: проверка приватного AI-сервиса (llm_service/gateway.py).

Проверяет:
  * доступ к модели по сети (health + список моделей);
  * чат через HTTP API (один ответ);
  * аутентификацию (без ключа → 401);
  * лимит контекста (огромный промпт → 413);
  * rate limit (всплеск запросов → 429 + Retry-After);
  * стабильность при нескольких одновременных запросах (успешность, latency).

Запуск:
  GATEWAY_API_KEY=secret .venv/bin/python tools/llm_service/smoke_test.py
  .venv/bin/python tools/llm_service/smoke_test.py --base-url http://192.168.1.10:8080 --key secret
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

RESULTS = []


def call(url, method="GET", key=None, body=None, timeout=300):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            return resp.status, payload, (time.perf_counter() - t0) * 1000, dict(resp.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"raw": raw}
        return e.code, payload, (time.perf_counter() - t0) * 1000, dict(e.headers)


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))


def chat(url, key, prompt, max_tokens=None, model=None):
    body = {"messages": [{"role": "user", "content": prompt}]}
    if max_tokens:
        body["max_tokens"] = max_tokens
    if model:
        body["model"] = model
    return call(url + "/v1/chat/completions", "POST", key, body)


def main():
    ap = argparse.ArgumentParser(description="Smoke/stability test приватного LLM-сервиса (День 30)")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--key", default=os.environ.get("GATEWAY_API_KEY", ""))
    ap.add_argument("--requests", type=int, default=6, help="сколько параллельных запросов")
    ap.add_argument("--concurrency", type=int, default=3)
    ap.add_argument("--rate-burst", type=int, default=25, help="всплеск для проверки rate limit")
    ap.add_argument("--prompt", default="Назови три факта о локальных LLM. Кратко.")
    args = ap.parse_args()
    url = args.base_url.rstrip("/")
    print(f"Сервис: {url} · ключ: {'задан' if args.key else 'НЕ задан'}")

    # 1. health / сетевой доступ
    code, health, _, _ = call(url + "/health", timeout=10)
    models = health.get("models", []) if isinstance(health, dict) else []
    record("network/health", code == 200, f"HTTP {code}, models={len(models)}, upstream={health.get('upstream')}")

    # 2. список моделей
    code, ml, _, _ = call(url + "/v1/models", key=args.key, timeout=10)
    ids = [m["id"] for m in ml.get("data", [])] if isinstance(ml, dict) else []
    record("models list", code == 200 and bool(ids), f"HTTP {code}, {len(ids)} моделей")

    # 3. чат (один ответ)
    code, resp, ms, hdr = chat(url, args.key, args.prompt, max_tokens=256)
    content = ""
    if isinstance(resp, dict):
        content = (resp.get("choices", [{}])[0].get("message", {}) or {}).get("content", "") or ""
    record("chat single", code == 200 and bool(content.strip()), f"HTTP {code}, {ms:.0f} ms, ответ {len(content)} симв.")

    # 4. аутентификация: без ключа → 401
    code, _, _, _ = call(url + "/v1/chat/completions", "POST", None, {"messages": [{"role": "user", "content": "hi"}]})
    record("auth required", code == 401, f"HTTP {code} (ожидали 401)")

    # 5. лимит контекста: огромный промпт → 413
    big = "контекст " * 20000  # ~180k символов ≈ 45k токенов
    code, resp, _, _ = chat(url, args.key, big, max_tokens=128)
    record("max context", code == 413, f"HTTP {code}, {resp.get('estimated_prompt_tokens') if isinstance(resp, dict) else '—'} токенов")

    # 6. стабильность: несколько запросов, в т.ч. параллельно
    lat, okc, fail = [], 0, 0
    def one(i):
        code, resp, ms, _ = chat(url, args.key, f"{args.prompt} (запрос {i})", max_tokens=128)
        c = (resp.get("choices", [{}])[0].get("message", {}) or {}).get("content", "") if isinstance(resp, dict) else ""
        return code, bool(c and c.strip()), ms

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        for code, ok, ms in ex.map(one, range(args.requests)):
            lat.append(ms)
            if ok:
                okc += 1
            else:
                fail += 1
    dur = time.perf_counter() - t0
    p50 = statistics.median(lat) if lat else 0
    p95 = sorted(lat)[int(len(lat) * 0.95) - 1] if lat else 0
    record(f"stability x{args.requests} (conc {args.concurrency})", fail == 0,
           f"{okc}/{args.requests} ok, p50 {p50:.0f} ms, p95 {p95:.0f} ms, всего {dur:.1f}s")

    # 7. rate limit: всплеск → должны получить 429
    codes = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        list(ex.map(lambda _: codes.append(chat(url, args.key, "ping", max_tokens=16)[0]),
                    range(args.rate_burst)))
    got429 = codes.count(429)
    record("rate limit", got429 > 0, f"{got429}/{args.rate_burst} запросов получили 429")

    # 8. Retry-After на 429
    code, resp, _, hdr = chat(url, args.key, "ping", max_tokens=16)
    record("rate limit headers", code == 429 and ("retry-after" in {k.lower() for k in hdr}),
           f"HTTP {code}, Retry-After={hdr.get('Retry-After', '—')}")

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print(f"\n=== Итог: {passed}/{len(RESULTS)} проверок пройдено ===")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
