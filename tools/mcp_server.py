#!/usr/bin/env python3
"""Локальный MCP-сервер для Дня 16 (демо).

Запуск:
    pip install "mcp[cli]"
    python tools/mcp_server.py

Сервер поднимается на http://0.0.0.0:8000, endpoint streamable-http: /mcp.
С эмулятора Android адрес: http://10.0.2.2:8000/mcp (host = 10.0.2.2).

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