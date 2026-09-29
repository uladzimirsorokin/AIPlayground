#!/usr/bin/env python3
"""День 22: сравнение ответов модели без RAG и с RAG по контрольному набору вопросов.

Пайплайн RAG: вопрос → поиск релевантных чанков в индексе (document_indexer) →
контекст объединяется с вопросом → запрос к LLM. Для каждого вопроса из
tools/rag_eval/questions.json генерируется два ответа:
  * без RAG — только вопрос;
  * с RAG — вопрос + «Контекст из базы знаний» с топ-чанками.
Результат сохраняется в tools/rag_eval/report.md.

Конфигурация LLM (через переменные окружения; ключ НЕ хранится в репозитории):
  LLM_API_KEY  — обязательный API-ключ
  LLM_ENDPOINT — полный URL chat/completions (приоритетнее LLM_BASE_URL)
  LLM_BASE_URL — базовый URL, используется если LLM_ENDPOINT пуст
  LLM_MODEL    — имя модели
Дефолты эндпоинта/модели берутся из local.properties (как в приложении).

Использование:
  python tools/rag_eval.py [--source docs/DnD] [--strategy structure] [--top-k 3]
Индекс для выбранных source/strategy/embedding должен быть построен заранее:
  python tools/document_indexer.py --source docs/DnD --strategy structure --embedding ollama
"""

import argparse
import glob
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
EVAL_DIR = os.path.join(TOOLS, "rag_eval")
REPORT = os.path.join(EVAL_DIR, "report.md")

import document_indexer as di  # noqa: E402


def load_local_properties() -> dict:
    out = {}
    path = os.path.join(ROOT, "local.properties")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and "=" in line and not line.startswith("#"):
                k, _, v = line.partition("=")
                out[k.strip()] = v.strip()
    return out


def llm_config() -> tuple:
    env = os.environ
    props = load_local_properties()
    base_url = env.get("LLM_BASE_URL", props.get("LLM_BASE_URL", "https://api.openai.com"))
    endpoint = env.get("LLM_ENDPOINT", props.get("LLM_ENDPOINT", ""))
    if not endpoint:
        endpoint = base_url + "/v1/chat/completions"
    model = env.get("LLM_MODEL", props.get("LLM_MODEL", "gpt-4o-mini"))
    key = env.get("LLM_API_KEY", "")
    return endpoint, model, key


def complete(endpoint: str, model: str, key: str, messages: list, timeout: int = 120) -> str:
    """OpenAI-совместимый вызов chat/completions (без сторонних зависимостей)."""
    body = json.dumps(
        {"model": model, "messages": messages, "temperature": 0.3},
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        endpoint,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {key}",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"].strip()


def format_context(query: str, index: dict, top_k: int) -> str:
    results = di.search(index, query, top_k)
    if not results:
        return ""
    parts = ["Контекст из базы знаний (RAG):"]
    for score, meta in results:
        parts.append(
            f"- [{score:.4f}] {meta['source']} :: {meta['section']}\n  {meta['snippet']}"
        )
    return "\n".join(parts)


def retrieval_hit(index: dict, query: str, top_k: int, expected_sources: list) -> bool:
    """Попал ли топ-1 RAG-чанк в ожидаемый файл (по имени файла)."""
    expected = {os.path.basename(s) for s in expected_sources if s}
    if not expected:
        return False
    for _, meta in di.search(index, query, top_k):
        if os.path.basename(meta["source"]) in expected:
            return True
    return False


def judge_answer(endpoint: str, model: str, key: str, question: str, expected: str, answer: str) -> str:
    """LLM-судья: содержит ли ответ модели факт из эталонного ответа. Возвращает 'да'/'нет'."""
    prompt = (
        f"Вопрос: {question}\n"
        f"Эталонный ответ: {expected}\n"
        f"Ответ модели: {answer}\n"
        "Содержит ли ответ модели этот факт (или эквивалент)? Ответь ровно одним словом: да или нет."
    )
    try:
        out = complete(
            endpoint, model, key,
            [{"role": "user", "content": prompt}],
        ).strip().lower()
        return "да" if out.startswith("да") else "нет"
    except Exception:
        return "—"


def main() -> None:
    ap = argparse.ArgumentParser(description="Сравнение ответов LLM без RAG и с RAG (День 22)")
    ap.add_argument("--source", default="all")
    ap.add_argument("--strategy", choices=["fixed", "structure"], default="structure")
    ap.add_argument("--embedding", choices=["ollama", "tfidf"], default="ollama")
    ap.add_argument("--top-k", type=int, default=3)
    ap.add_argument("--questions", default=os.path.join(EVAL_DIR, "questions.json"))
    ap.add_argument("--auto-index", action="store_true",
                    help="построить индекс (document_indexer), если его ещё нет")
    ap.add_argument("--judge", action="store_true",
                    help="оценивать ответы LLM-судьёй против эталона (expected)")
    args = ap.parse_args()

    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)
    print(f"Вопросов в наборе: {len(questions)}")

    index_path = di.index_path(args.source, args.strategy, args.embedding)
    if not os.path.exists(index_path):
        if args.auto_index:
            print(f"Индекса нет — строю ({args.source}, {args.strategy}, {args.embedding})…")
            idx = di.build_index(args.strategy, {"size": 150, "overlap": 30, "max_section": 400},
                                 args.source, args.embedding)
            di.save_index(idx)
        else:
            print(f"Индекс не найден: {index_path}. Сначала соберите его document_indexer "
                  f"(или добавьте --auto-index).")
            return 1
    index = di.load_index(args.source, args.strategy, args.embedding)
    print(f"Индекс: {index_path} ({len(index['chunks'])} чанков, {index['embedding_model']})")

    endpoint, model, key = llm_config()
    if not key:
        print("Нет LLM_API_KEY (переменная окружения). Ключ не хранится в репозитории.")
        return 1

    sys_prompt = "Ты — полезный и краткий ассистент. Отвечай по делу, без лишней воды."

    lines = [
        f"# RAG-сравнение (День 22)",
        "",
        f"Дата: {datetime.now(timezone.utc).isoformat()}",
        f"Индекс: source={args.source} strategy={args.strategy} embedding={args.embedding}",
        f"Модель: {model} · top_k={args.top_k}",
        "",
    ]

    hits = 0
    judged = {"without_yes": 0, "with_yes": 0, "n": 0}
    summary_rows = []

    for i, q in enumerate(questions, 1):
        question = q["question"]
        expected = q.get("expected", "")
        sources = ", ".join(q.get("sources", []))
        print(f"\n[{i}/{len(questions)}] {question[:60]}…")

        context = format_context(question, index, args.top_k)
        hit = retrieval_hit(index, question, args.top_k, q.get("sources", []))
        hits += 1 if hit else 0

        without = complete(
            endpoint, model, key,
            [{"role": "system", "content": sys_prompt}, {"role": "user", "content": question}],
        )
        with_rag = complete(
            endpoint, model, key,
            [
                {"role": "system", "content": sys_prompt},
                {"role": "system", "content": context},
                {"role": "user", "content": question},
            ],
        ) if context else "— (нет релевантных чанков)"

        j_without = j_with = "—"
        if args.judge:
            j_without = judge_answer(endpoint, model, key, question, expected, without)
            j_with = judge_answer(endpoint, model, key, question, expected, with_rag)
            judged["n"] += 1
            judged["without_yes"] += 1 if j_without == "да" else 0
            judged["with_yes"] += 1 if j_with == "да" else 0

        summary_rows.append((i, hit, j_without, j_with))
        lines += [
            f"## {i}. {question}",
            f"- **Ожидание:** {expected}",
            f"- **Источники:** {sources}",
            f"- **RAG-чанки:** {context if context else '— нет'}",
            f"- **Retrieval hit (топ-1 в нужный файл):** {'да' if hit else 'нет'}",
            f"- **Судья без RAG / с RAG:** {j_without} / {j_with}",
            "",
            "**Без RAG:**",
            without,
            "",
            "**С RAG:**",
            with_rag,
            "",
            "---",
            "",
        ]

    # Сводка
    lines += [
        "## Сводка",
        "",
        f"- Вопросов: {len(questions)}",
        f"- Retrieval hit: {hits}/{len(questions)} (топ-1 чанк попал в ожидаемый файл)",
    ]
    if args.judge:
        lines += [
            f"- Судья без RAG: {judged['without_yes']}/{judged['n']}",
            f"- Судья с RAG: {judged['with_yes']}/{judged['n']}",
        ]
    lines += [
        "",
        "| № | hit | судья без | судья с |",
        "|---|-----|-----------|---------|",
    ]
    for i, hit, jw, jr in summary_rows:
        lines.append(f"| {i} | {'да' if hit else 'нет'} | {jw} | {jr} |")
    lines.append("")

    print(f"\nRetrieval hit: {hits}/{len(questions)}")
    if args.judge:
        print(f"Судья (содержит эталонный факт): без RAG {judged['without_yes']}/{judged['n']}, "
              f"с RAG {judged['with_yes']}/{judged['n']}")

    os.makedirs(EVAL_DIR, exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\nОтчёт сохранён: {REPORT}")


if __name__ == "__main__":
    sys.exit(main())