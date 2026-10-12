#!/usr/bin/env python3
"""MCP-обёртка агент-сервера (День 31+): инструменты для других проектов/клиентов.

Запуск (stdio — для Claude Desktop / opencode / Cline):
    python tools/agent_mcp_server.py

Запуск (streamable-http — отдельный процесс, к которому ходят по URL):
    AGENT_MCP_TRANSPORT=streamable-http python tools/agent_mcp_server.py
    # endpoint: http://127.0.0.1:8091/mcp

Инструменты: list_projects, index_project, search_project, ask.
Локальная LLM и эмбеддинги — из agent_core (AGENT_LLM_ENDPOINT/AGENT_LLM_MODEL/...).
"""

import json
import os

import agent_core as core

try:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:  # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("LocalAgent")


@mcp.tool()
def list_projects() -> str:
    """Список проектов, проиндексированных локальным агентом (имя, корень, число чанков)."""
    return json.dumps({"ok": True, "projects": core.list_projects()}, ensure_ascii=False)


@mcp.tool()
def index_project(
    path: str,
    project: str = "",
    strategy: str = "structure",
    embedding: str = "ollama",
    chunk_size: int = 150,
    overlap: int = 30,
) -> str:
    """Проиндексировать проект по абсолютному пути для последующих вопросов.
    project — имя индекса (по умолчанию имя папки); strategy — structure|fixed."""
    res = core.index_project(
        path,
        project=project or None,
        strategy=strategy,
        embedding=embedding,
        chunk_size=chunk_size,
        overlap=overlap,
    )
    return json.dumps(res, ensure_ascii=False)


@mcp.tool()
def search_project(project: str, query: str, top_k: int = 5, min_score: float = 0.0, rerank: bool = True) -> str:
    """Семантический поиск по проиндексированному проекту: топ-чанки файлов."""
    res = core.search_project(project, query, top_k=top_k, min_score=min_score, rerank=rerank)
    return json.dumps(res, ensure_ascii=False)


@mcp.tool()
def ask(project: str, question: str, top_k: int = 5, min_score: float = 0.0) -> str:
    """Ответить на вопрос о проекте, опираясь на его документацию/код (RAG + локальная LLM)."""
    res = core.answer(project, question, top_k=top_k, min_score=min_score)
    return json.dumps(res, ensure_ascii=False)


if __name__ == "__main__":
    transport = os.environ.get("AGENT_MCP_TRANSPORT", "stdio")
    if transport == "streamable-http":
        host = os.environ.get("AGENT_MCP_HOST", "127.0.0.1")
        port = int(os.environ.get("AGENT_MCP_PORT", "8091"))
        kwargs = dict(transport="streamable-http", host=host, port=port)
        try:
            from mcp.server.transport_security import TransportSecuritySettings

            kwargs["transport_security"] = TransportSecuritySettings(
                allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*", "0.0.0.0:*"]
            )
        except ImportError:
            pass
        mcp.run(**kwargs)
    else:
        mcp.run(transport="stdio")
