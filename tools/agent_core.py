#!/usr/bin/env python3
"""Локальный агент-сервер (RAG по произвольному проекту) — ядро.

Умеет: проиндексировать любой проект по пути (walk → чанки → эмбеддинги ollama),
искать по нему и отвечать на вопросы локальной LLM со строгим JSON-ответом
(answer/sources/quotes) и валидацией. Используется двумя обёртками:

    agent_api.py        — локальный HTTP REST (localhost; при желании — как API с токеном)
    agent_mcp_server.py — MCP-инструменты (stdio / streamable-http)

Конфиг через env:
    AGENT_LLM_ENDPOINT  (дефолт http://127.0.0.1:11434/v1/chat/completions — локальный Ollama)
    AGENT_LLM_MODEL     (дефолт llama3.2:3b)
    AGENT_LLM_API_KEY   (пусто = без Authorization; для шлюза Дня 30)
    OLLAMA_URL / OLLAMA_EMBED_MODEL  (эмбеддинги; читает document_indexer)
"""

import json
import os
import re
import time
import urllib.request
from datetime import datetime, timezone

import document_indexer as di

ROOT = di.ROOT
INDEX_DIR = os.path.join(di.INDEX_DIR, "agent")

LLM_ENDPOINT = os.environ.get("AGENT_LLM_ENDPOINT", "http://127.0.0.1:11434/v1/chat/completions")
LLM_MODEL = os.environ.get("AGENT_LLM_MODEL", "llama3.2:3b")
LLM_API_KEY = os.environ.get("AGENT_LLM_API_KEY", "")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _safe(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_.-]", "_", name).strip("._") or "project"


# --- индексация произвольного проекта ---------------------------------------


def collect_project(root: str) -> list:
    """Обходит проект и возвращает [(rel_path, abs_path, text)] по текстовым файлам."""
    return di.collect_tree(root)


def project_index_path(project: str, strategy: str = "structure", embedding: str = "ollama") -> str:
    return os.path.join(INDEX_DIR, f"index_{_safe(project)}_{strategy}_{embedding}.json")


def index_project(
    path: str,
    project: str = None,
    strategy: str = "structure",
    embedding: str = "ollama",
    chunk_size: int = 150,
    overlap: int = 30,
) -> dict:
    """Индексирует проект по пути. project — имя индекса (по умолчанию имя папки)."""
    root = os.path.abspath(os.path.expanduser(path))
    project = (project or os.path.basename(root)).strip() or "project"
    try:
        docs = collect_project(root)
    except ValueError as exc:
        return {"ok": False, "reason": str(exc)}
    if not docs:
        return {"ok": False, "reason": f"в {root} не найдено текстовых файлов для индексации"}
    params = {"size": max(1, chunk_size), "overlap": max(0, overlap), "max_section": 400}
    idx = di.build_index(strategy, params, source=project, embedding=embedding, docs=docs)
    idx["root"] = root
    os.makedirs(INDEX_DIR, exist_ok=True)
    out = project_index_path(project, strategy, embedding)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(idx, f, ensure_ascii=False, indent=1)
    return {
        "ok": True,
        "project": project,
        "root": root,
        "path": out,
        "strategy": strategy,
        "embedding": idx["embedding_model"],
        "documents": idx["corpus"]["documents"],
        "chars": idx["corpus"]["chars"],
        "chunks": len(idx["chunks"]),
    }


def load_project_index(project: str, strategy: str = "structure", embedding: str = "ollama") -> dict:
    p = project_index_path(project, strategy, embedding)
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def list_projects() -> list:
    """Список проиндексированных проектов (по файлам индексов)."""
    result = []
    if not os.path.isdir(INDEX_DIR):
        return result
    for fn in sorted(os.listdir(INDEX_DIR)):
        if not (fn.startswith("index_") and fn.endswith(".json")):
            continue
        try:
            with open(os.path.join(INDEX_DIR, fn), encoding="utf-8") as f:
                idx = json.load(f)
        except Exception:
            continue
        result.append(
            {
                "project": idx.get("source"),
                "root": idx.get("root"),
                "strategy": idx.get("strategy"),
                "embedding": idx.get("embedding_model"),
                "chunks": len(idx.get("chunks", [])),
                "built_at": idx.get("built_at"),
            }
        )
    return result


def search_project(
    project: str,
    query: str,
    top_k: int = 5,
    fetch_k: int = 0,
    min_score: float = 0.0,
    rerank: bool = True,
    strategy: str = "structure",
    embedding: str = "ollama",
) -> dict:
    idx = load_project_index(project, strategy, embedding)
    if idx is None:
        return {"ok": False, "reason": f"индекс проекта '{project}' не найден — сначала проиндексируйте его"}
    results = [
        meta
        for _, meta in di.search(
            idx, query, max(1, top_k), fetch_k=max(0, fetch_k), min_score=max(0.0, min_score), rerank=bool(rerank)
        )
    ]
    return {"ok": True, "project": project, "query": query, "results": results}


# --- LLM --------------------------------------------------------------------


def chat(messages: list, temperature: float = 0.1, max_tokens: int = 0, json_object: bool = False, timeout: int = 180) -> str:
    """OpenAI-совместимый вызов (Ollama / шлюз). Возвращает content ответа."""
    body = {"model": LLM_MODEL, "messages": messages, "temperature": temperature}
    if max_tokens and max_tokens > 0:
        body["max_tokens"] = max_tokens
    if json_object:
        body["response_format"] = {"type": "json_object"}
    req = urllib.request.Request(
        LLM_ENDPOINT,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    if LLM_API_KEY:
        req.add_header("Authorization", f"Bearer {LLM_API_KEY}")
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"]
    _log(f"llm: {LLM_MODEL} {len(content)} симв. за {int((time.time() - t0) * 1000)}ms")
    return content


def _log(msg: str) -> None:
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {msg}", flush=True)


# --- RAG-ответ ---------------------------------------------------------------

_ANSWER_SYSTEM = (
    "Ты — ассистент разработчика. Отвечай на вопросы о проекте, опираясь ТОЛЬКО на "
    "приведённый контекст (фрагменты файлов проекта). Если данных в контексте нет — "
    "честно ответь «Не знаю», не выдумывай."
)

_ANSWER_INSTRUCTION = (
    "Верни ответ СТРОГО в формате JSON (без markdown и пояснений):\n"
    '{"answer": "краткий ответ", '
    '"sources": [{"n": 1, "source": "путь/файл", "section": "раздел"}], '
    '"quotes": [{"text": "дословный фрагмент из контекста", "n": 1}]}\n'
    "ТРЕБОВАНИЯ: только факты из контекста; каждый источник — реально использованный чанк "
    "(n — его номер в контексте); каждая цитата — дословный фрагмент, подтверждающий ключевой "
    'факт. Если ответа в контексте нет — {"answer": "Не знаю", "sources": [], "quotes": []}.'
)


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", str(s)).strip().lower()


def _parse_json(raw: str):
    c = re.sub(r"^```(json)?", "", raw.strip()).strip()
    c = re.sub(r"```$", "", c).strip()
    s, e = c.find("{"), c.rfind("}")
    if s < 0 or e <= s:
        return None
    try:
        return json.loads(c[s : e + 1])
    except Exception:
        return None


def _valid(data, context: str) -> bool:
    if not isinstance(data, dict):
        return False
    answer = str(data.get("answer", "")).strip()
    if not answer:
        return False
    if "не знаю" in answer.lower():
        return True
    quotes = data.get("quotes") or []
    sources = data.get("sources") or []
    if not quotes or not sources:
        return False
    ctx = _norm(context)
    return all(_norm(q.get("text", "")) and _norm(q.get("text", "")) in ctx for q in quotes)


def _context_block(results: list) -> str:
    lines = [f"Контекст проекта (топ-{len(results)} чанков):\n"]
    for i, r in enumerate(results):
        lines.append(f"[{i + 1}] {r['source']} :: {r.get('section', '')} (score {r.get('score', 0):.3f})")
        lines.append(r.get("snippet", ""))
        lines.append("")
    return "\n".join(lines)


def answer(
    project: str,
    question: str,
    top_k: int = 5,
    min_score: float = 0.0,
    fetch_k: int = 0,
    rerank: bool = True,
    strategy: str = "structure",
    embedding: str = "ollama",
) -> dict:
    """RAG-ответ строгим JSON с валидацией и одним повтором. Без источников — «Не знаю»."""
    sr = search_project(
        project, question, top_k=top_k, fetch_k=fetch_k, min_score=min_score,
        rerank=rerank, strategy=strategy, embedding=embedding,
    )
    if not sr.get("ok"):
        return sr
    results = sr["results"]
    if not results:
        return {"ok": True, "project": project, "question": question, "answer": "Не знаю", "sources": [], "quotes": [], "results": []}

    context = _context_block(results)
    messages = [
        {"role": "system", "content": _ANSWER_SYSTEM},
        {"role": "system", "content": context},
        {"role": "system", "content": _ANSWER_INSTRUCTION},
        {"role": "user", "content": question},
    ]
    raw = chat(messages, json_object=True)
    data = _parse_json(raw)
    if not _valid(data, context):
        _log("rag: ответ не прошёл валидацию, повтор")
        retry = messages + [
            {"role": "assistant", "content": raw},
            {
                "role": "user",
                "content": "Ответ отклонён: неверный JSON, либо факты/цитаты не из контекста. "
                "Используй ТОЛЬКО контекст; цитаты — дословные и подтверждающие; иначе «Не знаю».",
            },
        ]
        raw = chat(retry, json_object=True)
        data = _parse_json(raw)
        if not _valid(data, context):
            return {
                "ok": True, "project": project, "question": question,
                "answer": "Не знаю. Не удалось надёжно подтвердить ответ по найденному контексту.",
                "sources": [], "quotes": [], "results": results,
            }
    return {
        "ok": True,
        "project": project,
        "question": question,
        "answer": str(data.get("answer", "")).strip(),
        "sources": data.get("sources", []),
        "quotes": data.get("quotes", []),
        "results": results,
    }
