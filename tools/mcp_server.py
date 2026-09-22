#!/usr/bin/env python3
"""Локальный MCP-сервер для Дня 16–17 (демо).

Запуск:
    pip install "mcp[cli]"
    python tools/mcp_server.py

Сервер поднимается на http://0.0.0.0:8000, endpoint streamable-http: /mcp.
С эмулятора Android адрес: http://10.0.2.2:8000/mcp (host = 10.0.2.2).

Инструменты: add, multiply, current_time_utc, lorem (генерирует Lorem Ipsum
через публичный lorem API — как публичный демо-сервер).

Важно: MCP SDK 2.x включает DNS-rebinding защиту и валидирует Host-заголовок.
В коде ниже мы разрешаем 10.0.2.2 (эмулятор) — иначе будет HTTP 421 "Invalid host header".

Проверить руками (JSON-RPC):
    POST http://localhost:8000/mcp   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
    POST http://localhost:8000/mcp   {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
"""

try:
    # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:
    # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("AIAdventDemo")


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


@mcp.tool()
def lorem(word_count: int = 10) -> str:
    """Сгенерировать ровно заданное количество слов Lorem Ipsum (через публичный lorem API)."""
    import json as _json
    import math as _math
    import urllib.request as _url

    try:
        # Параграф baconipsum ~20-50 слов — запрашиваем с запасом, чтобы хватило на word_count.
        paras = max(1, _math.ceil(word_count / 30))
        with _url.urlopen(
            f"https://baconipsum.com/api/?type=meat-and-filler&paras={paras}&start_with_lorem=1",
            timeout=15,
        ) as resp:
            paragraphs = _json.loads(resp.read().decode())
    except Exception:
        paragraphs = ["Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor"]
    words = " ".join(paragraphs).split()
    if word_count <= len(words):
        return " ".join(words[:word_count])
    # API не дал нужного объёма — добираем циклически из тех же слов.
    return " ".join(words[i % len(words)] for i in range(max(0, word_count)))


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