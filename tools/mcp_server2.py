#!/usr/bin/env python3
"""Второй локальный MCP-сервер (День 20: оркестрация нескольких серверов).

Дополняет основной сервер (tools/mcp_server.py, порт 8000) набором «заметок»
(notes): сохранить/список/поиск/удалить. Держится на отдельном порту 8001,
поэтому клиент (агент) может подключаться к обоим серверам сразу, агрегировать
их инструменты и маршрутизировать вызовы по имени инструмента.

Запуск:
    python tools/mcp_server2.py            # http://127.0.0.1:8001/mcp
С эмулятора: http://10.0.2.2:8001/mcp

Инструменты:
    note_save(title, content)  — сохранить заметку (SQLite tools/mcp_notes.db);
    note_list()                — список всех заметок;
    note_find(keyword)         — поиск по заголовку и содержимому;
    note_delete(id)            — удалить заметку по id.

Типичный длинный флоу через оба сервера:
    search (8000) → summarize (8000) → note_save (8001) → note_find (8001).
"""

import json
import os
import sqlite3
import threading
import time
from datetime import datetime, timezone

try:
    # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:
    # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("AIAdventNotes")

_DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mcp_notes.db")
_DB_LOCK = threading.Lock()


def _log(msg: str) -> None:
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {msg}", flush=True)


def _db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(_DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _db_init() -> None:
    with _DB_LOCK:
        conn = _db_connect()
        conn.execute(
            """CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at REAL NOT NULL
            )"""
        )
        conn.commit()
        conn.close()


_db_init()


def _tool(*deco_args, **deco_kwargs):
    """Логирующая обёртка @mcp.tool() (как в основном сервере)."""

    def inner(fn):
        import functools

        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            call = kwargs if kwargs else dict(zip(fn.__code__.co_varnames[: len(args)], args))
            _log(f"[tool] {fn.__name__}({json.dumps(call, ensure_ascii=False)})")
            t0 = time.time()
            try:
                result = fn(*args, **kwargs)
            except Exception as exc:
                _log(f"[tool] {fn.__name__} ERROR: {exc}")
                raise
            _log(f"[tool] {fn.__name__} -> {str(result)[:200]} ({int((time.time() - t0) * 1000)}ms)")
            return result

        return mcp.tool(*deco_args, **deco_kwargs)(wrapper)

    return inner


@_tool()
def note_save(title: str, content: str) -> str:
    """Сохранить заметку: title + content, возвращает id и время создания.
    Полезно как финальный шаг пайплайна (например, после summarize с другого сервера)."""
    with _DB_LOCK:
        conn = _db_connect()
        cur = conn.execute(
            "INSERT INTO notes(title, content, created_at) VALUES (?, ?, ?)",
            (title, content, time.time()),
        )
        conn.commit()
        note_id = cur.lastrowid
        conn.close()
    return json.dumps({"id": note_id, "title": title, "content": content}, ensure_ascii=False)


@_tool()
def note_list() -> str:
    """Список всех заметок (id, title, created_at, длина содержимого)."""
    with _DB_LOCK:
        conn = _db_connect()
        rows = conn.execute("SELECT id, title, created_at, content FROM notes ORDER BY created_at DESC").fetchall()
        conn.close()
    items = [
        {"id": r[0], "title": r[1], "created_at": datetime.fromtimestamp(r[2], tz=timezone.utc).isoformat(),
         "chars": len(r[3])}
        for r in rows
    ]
    return json.dumps(items, ensure_ascii=False)


@_tool()
def note_find(keyword: str) -> str:
    """Поиск заметок по keyword в заголовке и содержимом (регистронезависимо)."""
    kw = keyword.lower()
    with _DB_LOCK:
        conn = _db_connect()
        rows = conn.execute("SELECT id, title, created_at, content FROM notes ORDER BY created_at DESC").fetchall()
        conn.close()
    items = [
        {"id": r[0], "title": r[1], "created_at": datetime.fromtimestamp(r[2], tz=timezone.utc).isoformat(),
         "content": r[3]}
        for r in rows
        if kw in r[1].lower() or kw in r[3].lower()
    ]
    return json.dumps(items, ensure_ascii=False)


@_tool()
def note_delete(id: int) -> str:
    """Удалить заметку по id. Возвращает удалён ли элемент."""
    with _DB_LOCK:
        conn = _db_connect()
        cur = conn.execute("DELETE FROM notes WHERE id=?", (id,))
        conn.commit()
        deleted = cur.rowcount > 0
        conn.close()
    return json.dumps({"id": id, "deleted": deleted}, ensure_ascii=False)


if __name__ == "__main__":
    kwargs = dict(transport="streamable-http", host="0.0.0.0", port=8001)
    try:
        from mcp.server.transport_security import TransportSecuritySettings

        kwargs["transport_security"] = TransportSecuritySettings(
            allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*", "10.0.2.2:*", "0.0.0.0:*"]
        )
    except ImportError:
        pass  # mcp 1.x
    mcp.run(**kwargs)