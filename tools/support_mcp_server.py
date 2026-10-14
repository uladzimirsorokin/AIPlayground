#!/usr/bin/env python3
"""MCP-обёртка мини-сервиса поддержки (День 33): CRM (JSON) + RAG по FAQ.

Инструменты: get_user, get_ticket, list_tickets, search_faq, answer_ticket.

Запуск (stdio — для Claude Desktop / opencode / Android-агента):
    python tools/support_mcp_server.py
Запуск (streamable-http):
    SUPPORT_MCP_TRANSPORT=streamable-http python tools/support_mcp_server.py
    # endpoint: http://127.0.0.1:8096/mcp

LLM/эмбеддинги — из support_service/agent_core (AGENT_LLM_*, SUPPORT_EMBEDDING).
"""

import json
import os

import support_service as svc

try:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:  # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("SupportCRM")


@mcp.tool()
def get_user(user_id: str) -> str:
    """Данные пользователя из CRM по id (имя, email, тариф, устройство, версия приложения)."""
    return json.dumps({"ok": True, "user": svc.get_user(user_id)}, ensure_ascii=False)


@mcp.tool()
def get_ticket(ticket_id: str) -> str:
    """Данные тикета из CRM по id (статус, приоритет, версия, сообщения)."""
    return json.dumps({"ok": True, "ticket": svc.get_ticket(ticket_id)}, ensure_ascii=False)


@mcp.tool()
def list_tickets(user_id: str = "", status: str = "") -> str:
    """Список тикетов (можно отфильтровать по user_id и/или status: open|closed)."""
    return json.dumps({"ok": True, "tickets": svc.list_tickets(user_id, status)}, ensure_ascii=False)


@mcp.tool()
def search_faq(query: str, top_k: int = 5) -> str:
    """Поиск по базе знаний (FAQ/документация) — топ релевантных фрагментов."""
    return json.dumps({"ok": True, "results": svc.search_faq(query, top_k)}, ensure_ascii=False)


@mcp.tool()
def answer_ticket(question: str, user_id: str = "", ticket_id: str = "", top_k: int = 5) -> str:
    """Ответ ассистента поддержки с учётом тикета/пользователя и базы знаний (RAG)."""
    return json.dumps(svc.answer(question, user_id, ticket_id, top_k), ensure_ascii=False)


if __name__ == "__main__":
    import argparse

    import agent_core as core

    ap = argparse.ArgumentParser(description="MCP-сервер поддержки (День 33)")
    ap.add_argument("--model", default=os.environ.get("AGENT_LLM_MODEL", ""),
                    help="модель LLM (иначе AGENT_LLM_MODEL, дефолт llama3.2:3b)")
    ap.add_argument("--endpoint", default=os.environ.get("AGENT_LLM_ENDPOINT", ""),
                    help="OpenAI-совместимый endpoint (иначе AGENT_LLM_ENDPOINT)")
    ap.add_argument("--api-key", default=os.environ.get("AGENT_LLM_API_KEY", ""),
                    help="API-ключ (иначе AGENT_LLM_API_KEY)")
    ap.add_argument("--transport", choices=["stdio", "streamable-http"],
                    default=os.environ.get("SUPPORT_MCP_TRANSPORT", "stdio"))
    ap.add_argument("--host", default=os.environ.get("SUPPORT_MCP_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("SUPPORT_MCP_PORT", "8096")))
    args = ap.parse_args()

    if args.model:
        core.LLM_MODEL = args.model
    if args.endpoint:
        core.LLM_ENDPOINT = args.endpoint
    if args.api_key:
        core.LLM_API_KEY = args.api_key

    if args.transport == "streamable-http":
        kwargs = dict(transport="streamable-http", host=args.host, port=args.port)
        try:
            from mcp.server.transport_security import TransportSecuritySettings

            kwargs["transport_security"] = TransportSecuritySettings(
                allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*", "0.0.0.0:*", "10.0.2.2:*"]
            )
        except ImportError:
            pass
        mcp.run(**kwargs)
    else:
        mcp.run(transport="stdio")
