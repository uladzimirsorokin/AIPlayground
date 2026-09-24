#!/usr/bin/env python3
"""Локальный MCP-сервер для Дня 16–18 (демо).

Запуск:
    pip install "mcp[cli]"
    python tools/mcp_server.py

Сервер поднимается на http://0.0.0.0:8000, endpoint streamable-http: /mcp.
С эмулятора Android адрес: http://10.0.2.2:8000/mcp (host = 10.0.2.2).

Инструменты:
    add, multiply, current_time_utc, lorem — демо (Дни 16–17).
    schedule_reminder, list_reminders, check_due_reminders,
    start_metrics_collector, stop_metrics_collector, get_metrics_summary —
    планировщик и фоновые задачи (День 18, см. ниже).

Важно: MCP SDK 2.x включает DNS-rebinding защиту и валидирует Host-заголовок.
В коде ниже мы разрешаем 10.0.2.2 (эмулятор) — иначе будет HTTP 421 "Invalid host header".

Проверить руками (JSON-RPC):
    POST http://localhost:8000/mcp   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
    POST http://localhost:8000/mcp   {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}

--- День 18: планировщик и фоновые задачи ---------------------------------

Идея: сам MCP-сервер — это процесс, который может работать 24/7 (запущен
отдельно от Android-приложения, например через `python tools/mcp_server.py`
под systemd/launchd/докером с restart-policy). Сразу при импорте модуля
стартует фоновый daemon-поток `_scheduler_loop`, который каждую секунду:
  - проверяет отложенные напоминания (`schedule_reminder`) и переводит их
    в статус "due", когда наступает `due_at`;
  - если включён периодический сбор данных (`start_metrics_collector`),
    раз в `interval_seconds` пишет новую точку метрики.
Все данные (напоминания, точки метрик, состояние коллектора) хранятся в
SQLite (`tools/mcp_scheduler.db`), поэтому переживают перезапуск сервера:
при старте `collector_running` читается из БД и сбор продолжается сам.
Инструменты для клиента (агента) — это не сам таймер, а способ поставить
задачу и забрать агрегированный результат:
  - `schedule_reminder(text, delay_seconds)` — поставить напоминание;
  - `check_due_reminders()` — забрать то, что уже наступило (once-delivery);
  - `start_metrics_collector(interval_seconds)` / `stop_metrics_collector()`;
  - `get_metrics_summary(minutes)` — сводка (count/avg/min/max/latest) за окно.
Агент (`ChatAgent`) вызывает эти инструменты через обычный function-calling
цикл (см. `ChatAgent.runWithMcpTools`), т.е. сводку он выдаёт когда его
спросят — но данные к этому моменту уже накоплены фоновым потоком сервера,
независимо от того, был ли в этот момент открыт чат.
"""

import json
import os
import random
import re
import sqlite3
import ssl
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

try:
    # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:
    # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("AIAdventDemo")

_DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mcp_scheduler.db")
_DB_LOCK = threading.Lock()


def _db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(_DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _db_init() -> None:
    with _DB_LOCK:
        conn = _db_connect()
        conn.execute(
            """CREATE TABLE IF NOT EXISTS reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                created_at REAL NOT NULL,
                due_at REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'scheduled',
                acknowledged INTEGER NOT NULL DEFAULT 0
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts REAL NOT NULL,
                value REAL NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS scheduler_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )"""
        )
        conn.commit()
        conn.close()


def _state_get(conn: sqlite3.Connection) -> dict:
    rows = conn.execute("SELECT key, value FROM scheduler_state").fetchall()
    return {k: v for k, v in rows}


def _state_set(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO scheduler_state(key, value) VALUES (?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, value),
    )


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


def _scheduler_loop() -> None:
    """Фоновый тик планировщика: раз в секунду. Работает всё время жизни процесса."""
    while True:
        try:
            now = time.time()
            with _DB_LOCK:
                conn = _db_connect()
                conn.execute(
                    "UPDATE reminders SET status='due' WHERE status='scheduled' AND due_at<=?",
                    (now,),
                )
                state = _state_get(conn)
                if state.get("collector_running") == "1":
                    interval = float(state.get("collector_interval", "60"))
                    last_tick = float(state.get("collector_last_tick", "0"))
                    if now - last_tick >= interval:
                        value = round(random.uniform(0, 100), 2)
                        conn.execute("INSERT INTO metrics(ts, value) VALUES (?, ?)", (now, value))
                        _state_set(conn, "collector_last_tick", str(now))
                conn.commit()
                conn.close()
        except Exception as exc:  # сервер не должен упасть из-за сбоя тика
            print(f"[scheduler] tick error: {exc}")
        time.sleep(1)


_db_init()
threading.Thread(target=_scheduler_loop, daemon=True, name="mcp-scheduler").start()


@mcp.tool()
def add(a: float, b: float) -> float:
    """Сложить два числа."""
    return a + b


@mcp.tool()
def multiply(a: float, b: float) -> float:
    """Перемножить два числа."""
    return a * b


@mcp.tool()
def current_time_utc() -> str:
    """Текущее время в UTC (ISO 8601)."""
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


_HTTPS_CTX = None


def _https_context():
    """SSL-контекст, который работает в том числе на Homebrew Python без CA-сертификатов
    в системном хранилище. Ищем системные CA-файлы; если не нашли — unverified (демо,
    только публичные источники: Wikipedia/baconipsum)."""
    global _HTTPS_CTX
    if _HTTPS_CTX is not None:
        return _HTTPS_CTX
    for cafile in (
        "/etc/ssl/cert.pem",
        "/etc/ssl/certs/ca-certificates.crt",
        "/etc/pki/tls/certs/ca-bundle.crt",
        "/usr/local/etc/openssl/cert.pem",
        "/opt/homebrew/etc/openssl/cert.pem",
    ):
        if os.path.exists(cafile):
            try:
                _HTTPS_CTX = ssl.create_default_context(cafile=cafile)
                return _HTTPS_CTX
            except Exception:
                pass
    _HTTPS_CTX = ssl._create_unverified_context()
    return _HTTPS_CTX


@mcp.tool()
def lorem(word_count: int = 10) -> str:
    """Сгенерировать ровно заданное количество слов Lorem Ipsum (через публичный lorem API)."""
    import json as _json
    import math as _math

    try:
        # Параграф baconipsum ~20-50 слов — запрашиваем с запасом, чтобы хватило на word_count.
        paras = max(1, _math.ceil(word_count / 30))
        with urllib.request.urlopen(
            f"https://baconipsum.com/api/?type=meat-and-filler&paras={paras}&start_with_lorem=1",
            timeout=15,
            context=_https_context(),
        ) as resp:
            paragraphs = _json.loads(resp.read().decode())
    except Exception:
        paragraphs = ["Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor"]
    words = " ".join(paragraphs).split()
    if word_count <= len(words):
        return " ".join(words[:word_count])
    # API не дал нужного объёма — добираем циклически из тех же слов.
    return " ".join(words[i % len(words)] for i in range(max(0, word_count)))


@mcp.tool()
def schedule_reminder(text: str, delay_seconds: int) -> str:
    """Поставить отложенное напоминание: сохраняется в SQLite и срабатывает через
    delay_seconds секунд силами фонового потока сервера (не зависит от того,
    подключён ли в этот момент клиент). Забрать сработавшие — check_due_reminders()."""
    now = time.time()
    due_at = now + max(0, delay_seconds)
    with _DB_LOCK:
        conn = _db_connect()
        cur = conn.execute(
            "INSERT INTO reminders(text, created_at, due_at, status) VALUES (?, ?, ?, 'scheduled')",
            (text, now, due_at),
        )
        conn.commit()
        reminder_id = cur.lastrowid
        conn.close()
    return json.dumps(
        {"id": reminder_id, "text": text, "due_at": _iso(due_at), "status": "scheduled"},
        ensure_ascii=False,
    )


@mcp.tool()
def list_reminders(status: str = "all") -> str:
    """Список всех напоминаний (для отладки/обзора). status: all|scheduled|due|fired."""
    with _DB_LOCK:
        conn = _db_connect()
        if status == "all":
            rows = conn.execute(
                "SELECT id, text, due_at, status, acknowledged FROM reminders ORDER BY due_at"
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT id, text, due_at, status, acknowledged FROM reminders WHERE status=? ORDER BY due_at",
                (status,),
            ).fetchall()
        conn.close()
    items = [
        {"id": r[0], "text": r[1], "due_at": _iso(r[2]), "status": r[3], "acknowledged": bool(r[4])}
        for r in rows
    ]
    return json.dumps(items, ensure_ascii=False)


@mcp.tool()
def check_due_reminders() -> str:
    """Забрать напоминания, время которых уже наступило и которые ещё не были
    доставлены — и пометить их доставленными (once-delivery, повторно не вернутся)."""
    now = time.time()
    with _DB_LOCK:
        conn = _db_connect()
        conn.execute(
            "UPDATE reminders SET status='due' WHERE status='scheduled' AND due_at<=?", (now,)
        )
        rows = conn.execute(
            "SELECT id, text, due_at FROM reminders WHERE status='due' AND acknowledged=0"
        ).fetchall()
        ids = [r[0] for r in rows]
        if ids:
            conn.executemany(
                "UPDATE reminders SET status='fired', acknowledged=1 WHERE id=?",
                [(i,) for i in ids],
            )
        conn.commit()
        conn.close()
    fired = [{"id": r[0], "text": r[1], "due_at": _iso(r[2])} for r in rows]
    return json.dumps({"fired": fired, "count": len(fired)}, ensure_ascii=False)


@mcp.tool()
def start_metrics_collector(interval_seconds: int = 60) -> str:
    """Запустить периодический сбор данных (демо-метрика) раз в interval_seconds секунд.
    Работает фоновым потоком сервера, состояние в SQLite — переживает переподключение
    клиента и перезапуск сервера (сбор продолжится сам). Сводка — get_metrics_summary()."""
    with _DB_LOCK:
        conn = _db_connect()
        _state_set(conn, "collector_running", "1")
        _state_set(conn, "collector_interval", str(max(1, interval_seconds)))
        _state_set(conn, "collector_last_tick", "0")
        conn.commit()
        conn.close()
    return json.dumps({"status": "started", "interval_seconds": max(1, interval_seconds)})


@mcp.tool()
def stop_metrics_collector() -> str:
    """Остановить периодический сбор данных (уже собранные точки остаются в SQLite)."""
    with _DB_LOCK:
        conn = _db_connect()
        _state_set(conn, "collector_running", "0")
        conn.commit()
        conn.close()
    return json.dumps({"status": "stopped"})


@mcp.tool()
def get_metrics_summary(minutes: int = 60) -> str:
    """Агрегированная сводка собранных метрик за последние minutes минут:
    count/avg/min/max/latest + запущен ли коллектор и с каким интервалом."""
    since = time.time() - minutes * 60
    with _DB_LOCK:
        conn = _db_connect()
        rows = conn.execute(
            "SELECT ts, value FROM metrics WHERE ts>=? ORDER BY ts", (since,)
        ).fetchall()
        state = _state_get(conn)
        conn.close()
    values = [r[1] for r in rows]
    return json.dumps(
        {
            "window_minutes": minutes,
            "count": len(values),
            "avg": round(sum(values) / len(values), 2) if values else None,
            "min": min(values) if values else None,
            "max": max(values) if values else None,
            "latest": values[-1] if values else None,
            "collector_running": state.get("collector_running") == "1",
            "collector_interval_seconds": int(float(state.get("collector_interval", "60"))),
        },
        ensure_ascii=False,
    )


# --- День 19: композиция MCP-инструментов -----------------------------------
#
# Три самостоятельных инструмента (search → summarize → save_to_file) образуют
# пайплайн обработки данных. Каждый инструмент можно вызывать отдельно, а можно
# запустить оркестратор run_pipeline, который сам выполняет цепочку на сервере:
#   1. search(query)        — получить данные (локальная демо-база + Wikipedia);
#   2. summarize(text)      — обработать: извлекательное резюме + статистика;
#   3. save_to_file(..)     — сохранить результат в tools/pipeline_outputs/.
# Передача данных между шагами идёт напрямую (возврат функции → аргумент следующей),
# отчёт run_pipeline содержит trace шагов с in/out — это и есть проверка корректности
# композиции. Модель может также построить цепочку сама через function-calling.

_PIPELINE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pipeline_outputs")

# Локальная демо-база знаний: работает офлайн и служит fallback, если Wikipedia недоступна.
_DEMO_DOCS = {
    "кофе": (
        "Кофе — это напиток из обжаренных зёрен кофейного дерева. "
        "Кофеин, содержащийся в кофе, является самым популярным психоактивным веществом в мире. "
        "Считается, что кофе был впервые открыт в Эфиопии, где пастухи заметили бодрящий эффект ягод. "
        "Современные способы приготовления включают эспрессо, фильтр, френч-пресс и аэропресс. "
        "Кофе содержит антиоксиданты и при умеренном потреблении может снижать риск некоторых заболеваний."
    ),
    "python": (
        "Python — это высокоуровневый язык программирования, созданный Гвидо ван Россумом в 1991 году. "
        "Он отличается читаемым синтаксисом и широкой экосистемой библиотек. "
        "Python активно используется в веб-разработке, анализе данных, машинном обучении и автоматизации. "
        "Интерпретатор Python доступен для всех основных операционных систем."
    ),
    "андроид": (
        "Android — это мобильная операционная система на базе ядра Linux, разрабатываемая компанией Google. "
        "Она устанавливается на большинство смартфонов в мире и поддерживает приложения на Java и Kotlin. "
        "Jetpack Compose — современный декларативный UI-фреймворк для Android-приложений. "
        "Официальная IDE для разработки — Android Studio, а сборка проектов идёт через Gradle."
    ),
    "mcp": (
        "Model Context Protocol (MCP) — открытый протокол, который соединяет AI-модели с внешними инструментами и данными. "
        "MCP использует JSON-RPC поверх Streamable HTTP или stdio. "
        "Сервер объявляет инструменты через tools/list, а клиент вызывает их через tools/call. "
        "Это позволяет строить композиции из нескольких инструментов — цепочки обработки данных."
    ),
}


def _wikipedia_titles(query: str, limit: int = 3) -> list:
    """Резолвит произвольный запрос в реальные названия статей Wikipedia (opensearch)."""
    url = "https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode(
        {"action": "opensearch", "search": query, "limit": limit, "format": "json"}
    )
    req = urllib.request.Request(url, headers={"User-Agent": "AIAdventDemo/1.0"})
    with urllib.request.urlopen(req, timeout=8, context=_https_context()) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data[1] if len(data) > 1 else []


def _wikipedia_extract(title: str) -> str:
    """Текст статьи по точному названию (REST summary)."""
    url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + urllib.parse.quote(title.replace(" ", "_"))
    req = urllib.request.Request(url, headers={"User-Agent": "AIAdventDemo/1.0"})
    with urllib.request.urlopen(req, timeout=8, context=_https_context()) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("extract") or ""


def _search_source(query: str, limit: int) -> list:
    """Собирает результаты из локальной демо-базы (регистронезависимо) и Wikipedia:
    запрос резолвится через opensearch в реальное название статьи, затем берётся extract."""
    results = []
    q = query.strip().lower()
    # 1) локальная демо-база: по ключу или вхождению любого слова запроса.
    for key, text in _DEMO_DOCS.items():
        k = key.lower()
        if q == k or any(w in text.lower() for w in q.split()):
            results.append({"title": key.capitalize(), "url": f"local://{key}", "extract": text})
    # 2) Wikipedia: opensearch → REST summary. Падение сети/CA-сертификатов не фатально.
    try:
        for title in _wikipedia_titles(q, limit)[:max(1, limit)]:
            extract = _wikipedia_extract(title)
            if extract:
                results.append(
                    {
                        "title": title,
                        "url": "https://en.wikipedia.org/wiki/" + title.replace(" ", "_"),
                        "extract": extract,
                    }
                )
    except Exception as exc:
        print(f"[search] wikipedia unavailable: {exc}", flush=True)
    return results[:max(1, limit)]


@mcp.tool()
def search(query: str, limit: int = 3) -> str:
    """Поиск по теме: возвращает JSON-массив результатов (title, url, extract), где extract —
    полнотекстовый отрывок. Это первый шаг пайплайна: возьмите extract из результата и
    передайте его в summarize как text. Источники: локальная демо-база и Wikipedia."""
    return json.dumps(_search_source(query, limit), ensure_ascii=False)


@mcp.tool()
def summarize(text: str, max_sentences: int = 3) -> str:
    """Извлекательное резюме текста: первые max_sentences предложений + статистика
    (длина, число предложений, топ-слова). Второй шаг пайплайна: на вход подавайте
    extract из search, а поле summary из результата передавайте в save_to_file как content."""
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
    summary = " ".join(sentences[:max(1, max_sentences)])
    words = re.findall(r"\w+", text.lower())
    top = sorted(set(words), key=words.count, reverse=True)[:5]
    return json.dumps(
        {
            "original_chars": len(text),
            "original_sentences": len(sentences),
            "summary": summary,
            "summary_chars": len(summary),
            "top_words": top,
        },
        ensure_ascii=False,
    )


@mcp.tool()
def save_to_file(filename: str, content: str) -> str:
    """Сохраняет content в файл tools/pipeline_outputs/ (директория создаётся сама).
    Возвращает путь, размер и число строк. Финальный шаг пайплайна: передавайте сюда
    summary из summarize (или любой другой текст) как content."""
    os.makedirs(_PIPELINE_DIR, exist_ok=True)
    safe = os.path.basename(filename) or "output.txt"
    path = os.path.join(_PIPELINE_DIR, safe)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return json.dumps(
        {"filename": safe, "path": path, "chars": len(content), "lines": content.count("\n") + 1},
        ensure_ascii=False,
    )


@mcp.tool()
def run_pipeline(query: str, filename: str = "pipeline_result.txt") -> str:
    """Оркестратор пайплайна: search → summarize → save_to_file. Каждый шаг получает
    результат предыдущего напрямую; отчёт содержит trace шагов с in/out и итоговое резюме."""
    trace = []

    # Шаг 1: получить данные.
    search_res = json.loads(search(query))
    if not search_res:
        return json.dumps(
            {"ok": False, "query": query, "reason": "ничего не найдено по запросу", "steps": trace},
            ensure_ascii=False,
        )
    first = search_res[0]
    extract = first["extract"]
    trace.append(
        {"step": 1, "tool": "search", "in": {"query": query}, "out": {"title": first["title"], "chars": len(extract)}}
    )
    print(f"[pipeline] step 1 search: {query!r} -> {len(extract)} chars", flush=True)

    # Шаг 2: обработать (резюме).
    summary_res = json.loads(summarize(extract))
    summary = summary_res["summary"]
    trace.append(
        {
            "step": 2,
            "tool": "summarize",
            "in": {"chars": len(extract)},
            "out": {"summary_chars": len(summary), "top_words": summary_res["top_words"]},
        }
    )
    print(f"[pipeline] step 2 summarize: {len(extract)} -> {len(summary)} chars", flush=True)

    # Шаг 3: сохранить результат.
    saved = json.loads(save_to_file(filename, summary))
    trace.append(
        {
            "step": 3,
            "tool": "save_to_file",
            "in": {"summary_chars": len(summary)},
            "out": {"path": saved["path"], "chars": saved["chars"], "lines": saved["lines"]},
        }
    )
    print(f"[pipeline] step 3 save_to_file: {saved['path']} ({saved['chars']} chars)", flush=True)

    return json.dumps(
        {
            "ok": True,
            "query": query,
            "title": first["title"],
            "summary": summary,
            "steps": trace,
        },
        ensure_ascii=False,
    )


if __name__ == "__main__":
    # host="0.0.0.0" + разрешённые Host-заголовки: MCP SDK 2.x включает DNS-rebinding защиту
    # и по умолчанию валидирует Host (иначе с эмулятора будет HTTP 421 "Invalid host header").
    kwargs = dict(transport="streamable-http", host="0.0.0.0", port=8000)
    try:
        from mcp.server.transport_security import TransportSecuritySettings

        kwargs["transport_security"] = TransportSecuritySettings(
            allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*", "10.0.2.2:*", "0.0.0.0:*"]
        )
    except ImportError:
        pass  # mcp 1.x — параметра нет, защита не встроена
    mcp.run(**kwargs)