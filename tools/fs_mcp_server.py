#!/usr/bin/env python3
"""MCP-обёртка файловых инструментов (День 34): read/search/write/replace/list/diff.

Даёт любому MCP-клиенту (Android-агент, opencode, Claude Desktop) реальные операции с
файлами проекта. Корень задаётся env FS_ROOT (дефолт — cwd).

Запуск (stdio):
    FS_ROOT=/path/to/project python tools/fs_mcp_server.py
Запуск (streamable-http):
    FS_ROOT=/path/to/project FS_MCP_TRANSPORT=streamable-http python tools/fs_mcp_server.py
    # endpoint: http://127.0.0.1:8097/mcp
"""

import json
import os

import file_agent as fa

try:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _MCPServer
except ModuleNotFoundError:  # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _MCPServer

mcp = _MCPServer("ProjectFiles")


@mcp.tool()
def list_files(path: str = "") -> str:
    """Список файлов проекта (можно ограничить подпапкой path)."""
    return fa.fs_list_files(path)


@mcp.tool()
def read_file(path: str) -> str:
    """Прочитать содержимое файла проекта (путь относительно корня)."""
    return fa.fs_read_file(path)


@mcp.tool()
def search(pattern: str, path: str = "", glob: str = "") -> str:
    """Regex-поиск по файлам проекта; возвращает совпадения «file:line: текст»."""
    return fa.fs_search(pattern, path, glob)


@mcp.tool()
def write_file(path: str, content: str) -> str:
    """Создать или перезаписать файл проекта целиком."""
    return fa.fs_write_file(path, content)


@mcp.tool()
def replace_in_file(path: str, old: str, new: str) -> str:
    """Заменить первое вхождение old на new в файле."""
    return fa.fs_replace_in_file(path, old, new)


@mcp.tool()
def prepend_to_files(glob: str, text: str, path: str = "") -> str:
    """Добавить текст в НАЧАЛО каждого файла по glob (например шапку/комментарий/лицензию). Массовая правка."""
    return fa.fs_prepend_to_files(glob, text, path)


@mcp.tool()
def append_to_files(glob: str, text: str, path: str = "") -> str:
    """Добавить текст в КОНЕЦ каждого файла по glob."""
    return fa.fs_append_to_files(glob, text, path)


@mcp.tool()
def replace_in_files(glob: str, old: str, new: str, path: str = "") -> str:
    """Заменить old на new во ВСЕХ файлах по glob, где встречается old (массовая замена)."""
    return fa.fs_replace_in_files(glob, old, new, path)


@mcp.tool()
def insert_before_in_files(glob: str, pattern: str, text: str, path: str = "") -> str:
    """Вставить текст ПЕРЕД первой строкой, совпавшей с regex pattern, в каждом файле по glob
    (например, шапку/комментарий прямо перед классом)."""
    return fa.fs_insert_before_in_files(glob, pattern, text, path)


@mcp.tool()
def git_diff() -> str:
    """Текущий git diff проекта (unified) — что изменилось."""
    return fa.fs_git_diff()


if __name__ == "__main__":
    fa.ROOT = os.path.abspath(os.path.expanduser(os.environ.get("FS_ROOT", os.getcwd())))
    transport = os.environ.get("FS_MCP_TRANSPORT", "stdio")
    if transport == "streamable-http":
        host = os.environ.get("FS_MCP_HOST", "127.0.0.1")
        port = int(os.environ.get("FS_MCP_PORT", "8097"))
        kwargs = dict(transport="streamable-http", host=host, port=port)
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
