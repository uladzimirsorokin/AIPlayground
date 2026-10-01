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
import re
import sys
import ssl
import urllib.request
from datetime import datetime, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
EVAL_DIR = os.path.join(TOOLS, "rag_eval")
REPORT = os.path.join(EVAL_DIR, "report.md")

import document_indexer as di  # noqa: E402


_HTTPS_CTX = None


def _https_context():
    """SSL-контекст, работающий на Homebrew Python без системных CA-сертификатов
    (как в mcp_server.py): ищем системные CA-файлы, fallback unverified для публичных API."""
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
    with urllib.request.urlopen(req, timeout=timeout, context=_https_context()) as resp:
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


def rewrite_query(endpoint: str, model: str, key: str, question: str) -> str:
    """Query rewrite (День 23): перефразировать вопрос в поисковый запрос (ключевые слова/синонимы)."""
    prompt = (
        "Сформулируй КОРОТКИЙ поисковый запрос (3–6 слов) по смыслу вопроса: только ключевые "
        "сущности, без перечислений и синонимов. Верни ТОЛЬКО запрос, без пояснений.\n\n"
        f"Вопрос: {question}"
    )
    try:
        out = complete(endpoint, model, key, [{"role": "user", "content": prompt}]).strip()
        return out or question
    except Exception:
        return question


def format_results(results: list) -> str:
    if not results:
        return ""
    parts = ["Контекст из базы знаний (RAG):"]
    for score, meta in results:
        parts.append(f"- [{score:.4f}] {meta['source']} :: {meta['section']}\n  {meta['snippet']}")
    return "\n".join(parts)


def correct_rank_full(index: dict, query: str, expected_base: set):
    """1-based позиция первого чанка из ожидаемого файла в полном embedding-ранжировании."""
    if not expected_base:
        return None
    allres = di.search(index, query, top_k=len(index["chunks"]))
    for i, (_, m) in enumerate(allres, 1):
        if os.path.basename(m["source"]) in expected_base:
            return i
    return None


def answer_hit(results: list, expected: str):
    """Есть ли в найденных чанках слово-ответ (answer-level, а не file-level)."""
    tokens = [t for t in re.findall(r"[a-zа-яё0-9]+", expected.lower()) if len(t) >= 4]
    if not tokens:
        return None
    text = " ".join(m["snippet"] for _, m in results).lower()
    return any(t in text for t in tokens)


UNKNOWN_QUESTIONS = [
    "Какая столица Австралии?",
    "Сколько спутников у планеты Марс?",
    "Кто написал роман «Война и мир»?",
]


RAG_SYSTEM_INSTRUCTIONS = (
    "Отвечай, опираясь ТОЛЬКО на приведённый контекст.\n"
    "1) Сначала краткий ответ.\n"
    "2) Затем блок «Источники:» — для каждого использованного чанка: [номер] source :: section.\n"
    "3) Затем блок «Цитаты:» — 1–2 дословных фрагмента из контекста в кавычках «...» с номером [номер].\n"
    "Если в контексте нет ответа — ответь ровно «Не знаю» и попроси уточнить вопрос. Не выдумывай факты."
)


def rag_context_block(results: list) -> str:
    if not results:
        return ("Контекст из базы знаний (RAG): релевантных данных не найдено. "
                "Ответь «Не знаю» и попроси уточнить вопрос.")
    parts = ["Контекст из базы знаний (RAG):"]
    for i, (score, m) in enumerate(results, 1):
        parts.append(f"[{i}] {m['source']} :: {m['section']} (score {score:.3f})\n{m['snippet']}")
    return "\n".join(parts)


def rag_answer(endpoint, model, key, question, results):
    return complete(endpoint, model, key, [
        {"role": "system", "content": RAG_SYSTEM_INSTRUCTIONS},
        {"role": "system", "content": rag_context_block(results)},
        {"role": "user", "content": question},
    ])


def answer_quality(answer: str, results: list):
    """(has_sources, has_quotes, grounded): источники/цитаты/подтверждены ли цитаты чанками."""
    low = answer.lower()
    tokens = set()
    for _, m in results:
        tokens.add(os.path.basename(m["source"]).lower())
        if m.get("section"):
            tokens.add(m["section"].lower())
    has_sources = ("источник" in low) and any(t and t in low for t in tokens)
    quotes = re.findall(r"«([^»]{8,})»", answer) + re.findall(r"\"([^\"]{8,})\"", answer)
    has_quotes = len(quotes) > 0
    ctx = re.sub(r"\s+", " ", " ".join(m["snippet"] for _, m in results)).lower()
    grounded = False
    for q in quotes:
        qn = re.sub(r"\s+", " ", q).strip().lower()
        if qn and qn in ctx:
            grounded = True
            break
    return has_sources, has_quotes, grounded


def is_faithful(endpoint, model, key, context: str, answer: str) -> bool:
    """LLM-faithfulness (День 24): следует ли ответ только из контекста (нет фактов вне контекста)."""
    prompt = (
        f"Контекст:\n{context}\n\nОтвет модели: {answer}\n\n"
        "Следует ли ответ ТОЛЬКО из контекста (нет фактов, которых в контексте нет)? "
        "Верни строго JSON: {\"supported\": true|false}."
    )
    try:
        out = complete(endpoint, model, key, [
            {"role": "system", "content": "Ты — строгий проверяющий фактов. Отвечай только JSON."},
            {"role": "user", "content": prompt},
        ])
        c = out.strip().removeprefix("```json").removesuffix("```").strip()
        s = c.find("{")
        e = c.rfind("}")
        if s < 0 or e <= s:
            return False
        return bool(json.loads(c[s:e + 1]).get("supported", False))
    except Exception:
        return False


def answer_with_context(endpoint, model, key, sys_prompt, question, context):
    msgs = [{"role": "system", "content": sys_prompt}]
    if context:
        msgs.append({"role": "system", "content": context})
    msgs.append({"role": "user", "content": question})
    return complete(endpoint, model, key, msgs)


def main() -> None:
    ap = argparse.ArgumentParser(description="RAG: реранкинг/фильтрация + сравнение режимов (День 23)")
    ap.add_argument("--source", default="all")
    ap.add_argument("--strategy", choices=["fixed", "structure"], default="structure")
    ap.add_argument("--embedding", choices=["ollama", "tfidf"], default="ollama")
    ap.add_argument("--top-k", type=int, default=3, help="топ-K ПОСЛЕ фильтрации")
    ap.add_argument("--fetch-k", type=int, default=20, help="топ-K ДО фильтрации (кандидаты для реранка)")
    ap.add_argument("--min-score", type=float, default=0.7, help="порог отсечения нерелевантных (similarity)")
    ap.add_argument("--questions", default=os.path.join(EVAL_DIR, "questions.json"))
    ap.add_argument("--auto-index", action="store_true",
                    help="построить индекс (document_indexer), если его ещё нет")
    ap.add_argument("--judge", action="store_true",
                    help="оценивать ответы LLM-судьёй против эталона (expected)")
    ap.add_argument("--no-answers", action="store_true",
                    help="только retrieval-метрики, без генерации ответов")
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
    have_llm = bool(key)
    if not have_llm:
        print("Нет LLM_API_KEY — считаю только retrieval-метрики (rewrite и ответы пропускаю).")

    # Режимы retrieval (День 23): от «сырого» до rewrite+rerank+filter.
    modes = [
        ("naive", {"top_k": args.top_k}),
        ("filter", {"top_k": args.top_k, "min_score": args.min_score}),
        ("rerank", {"top_k": args.top_k, "fetch_k": args.fetch_k, "rerank": True}),
    ]
    if have_llm:
        modes.append(("rewrite", {"top_k": args.top_k, "fetch_k": args.fetch_k,
                                  "min_score": args.min_score, "rerank": True, "rewrite": True}))

    stats = {name: {"hit": 0, "anshit": 0, "kept": 0, "top1": 0.0, "n": 0} for name, _ in modes}
    per_q = []

    for i, q in enumerate(questions, 1):
        question = q["question"]
        expected = q.get("expected", "")
        expected_sources = q.get("sources", [])
        expected_base = {os.path.basename(s) for s in expected_sources}
        print(f"\n[{i}/{len(questions)}] {question[:60]}…")
        row = {}
        for name, cfg in modes:
            cfg = dict(cfg)
            do_rewrite = cfg.pop("rewrite", False)
            query = rewrite_query(endpoint, model, key, question) if (do_rewrite and have_llm) else question
            res = di.search(index, query, **cfg)
            hit = any(os.path.basename(m["source"]) in expected_base for _, m in res)
            ah = bool(answer_hit(res, expected))
            top1 = res[0][0] if res else 0.0
            stats[name]["hit"] += 1 if hit else 0
            stats[name]["anshit"] += 1 if ah else 0
            stats[name]["kept"] += len(res)
            stats[name]["top1"] += top1
            stats[name]["n"] += 1
            row[name] = {"results": res, "hit": hit, "anshit": ah, "query": query}
            print(f"    {name:<8} file={'да' if hit else 'нет'} answer={'да' if ah else 'нет'} "
                  f"kept={len(res)} top1={top1:.3f}")
        # Воронка «улучшенного» пайплайна и место правильного чанка в embedding-ранжировании.
        rank_full = correct_rank_full(index, question, expected_base)
        if args.min_score > 0:
            m_filtered = len(di.search(index, question, top_k=args.fetch_k, fetch_k=args.fetch_k,
                                       min_score=args.min_score))
        else:
            m_filtered = min(args.fetch_k, len(index["chunks"]))
        per_q.append({"question": question, "expected": expected, "sources": expected_sources,
                      "row": row, "rank_full": rank_full, "m_filtered": m_filtered})

    # Генерация ответов и сравнение качества (no_rag / naive / improved) + проверка цитат.
    sys_prompt = "Ты — полезный и краткий ассистент. Отвечай по делу, без лишней воды."
    answered = []
    citation = {"sources": 0, "quotes": 0, "grounded": 0, "faithful": 0, "n": 0}
    if have_llm and not args.no_answers:
        improved_mode = "rewrite" if "rewrite" in stats else "rerank"
        judged = {"no_rag": 0, "naive": 0, "improved": 0, "n": 0}
        for item in per_q:
            question, expected = item["question"], item["expected"]
            naive_results = item["row"]["naive"]["results"]
            improved_results = item["row"][improved_mode]["results"]
            no_rag = answer_with_context(endpoint, model, key, sys_prompt, question, None)
            naive = rag_answer(endpoint, model, key, question, naive_results)
            improved = rag_answer(endpoint, model, key, question, improved_results)
            has_src, has_q, grounded = answer_quality(improved, improved_results)
            faithful = is_faithful(endpoint, model, key, rag_context_block(improved_results), improved) \
                if args.judge else None
            citation["n"] += 1
            citation["sources"] += 1 if has_src else 0
            citation["quotes"] += 1 if has_q else 0
            citation["grounded"] += 1 if grounded else 0
            if faithful is not None:
                citation["faithful"] += 1 if faithful else 0
            jn = ji = jm = "—"
            if args.judge:
                jn = judge_answer(endpoint, model, key, question, expected, no_rag)
                ji = judge_answer(endpoint, model, key, question, expected, naive)
                jm = judge_answer(endpoint, model, key, question, expected, improved)
                judged["n"] += 1
                judged["no_rag"] += 1 if jn == "да" else 0
                judged["naive"] += 1 if ji == "да" else 0
                judged["improved"] += 1 if jm == "да" else 0
            answered.append({"item": item, "no_rag": no_rag, "naive": naive, "improved": improved,
                             "jn": jn, "ji": ji, "jm": jm,
                             "has_src": has_src, "has_q": has_q, "grounded": grounded,
                             "faithful": faithful})

    # Режим «не знаю»: вопросы вне корпуса с высоким порогом → пустой контекст → должен быть отказ.
    unknown_results = []
    if have_llm and not args.no_answers:
        for uq in UNKNOWN_QUESTIONS:
            res = di.search(index, uq, top_k=args.top_k, fetch_k=args.fetch_k,
                            min_score=max(args.min_score, 0.9), rerank=True)
            ans = rag_answer(endpoint, model, key, uq, res)
            refused = ("не знаю" in ans.lower()) or ("уточн" in ans.lower())
            unknown_results.append({"question": uq, "answer": ans, "refused": refused,
                                    "kept": len(res)})

    # Отчёт.
    lines = [
        "# RAG: реранкинг/фильтрация и сравнение режимов (День 23)",
        "",
        f"Дата: {datetime.now(timezone.utc).isoformat()}",
        f"Индекс: source={args.source} strategy={args.strategy} embedding={args.embedding}",
        f"Модель: {model or '—'} · top_k={args.top_k} · fetch_k={args.fetch_k} · min_score={args.min_score}",
        "",
        "```",
        "ОФЛАЙН: корпус → чанки(fixed|structure) → эмбеддинги(ollama) → индекс.json",
        "",
        f"ОНЛАЙН: вопрос ──[rewrite? LLM]──► query'",
        f"                              │ embed",
        f"   [все {len(index['chunks'])} чанков] ──косинус──► sort",
        f"                              │ retrieve",
        f"                         fetch_k={args.fetch_k}",
        f"                              │ filter (score ≥ min_score={args.min_score})",
        f"                              ▼",
        f"                         top candidates",
        f"                              │ rerank? 0.7·emb + 0.3·лексика",
        f"                              ▼",
        f"                         top_k={args.top_k} ──► контекст + вопрос ──► LLM",
        "```",
        "",
        "## Retrieval по режимам",
        "",
        "| режим | file-hit@top_k | answer-hit@top_k | avg чанков | avg top1 |",
        "|---|---|---|---|---|",
    ]
    for name, _ in modes:
        s = stats[name]
        n = s["n"] or 1
        lines.append(f"| {name} | {s['hit']}/{s['n']} | {s['anshit']}/{s['n']} | {s['kept']/n:.1f} | {s['top1']/n:.3f} |")
    lines.append("")
    print("\nRetrieval по режимам (file-hit — верный файл, answer-hit — слово-ответ в чанках):")
    for name, _ in modes:
        s = stats[name]
        n = s["n"] or 1
        print(f"  {name:<8} file={s['hit']}/{s['n']} answer={s['anshit']}/{s['n']} "
              f"avg_kept={s['kept']/n:.1f} avg_top1={s['top1']/n:.3f}")

    for item in per_q:
        total = len(index["chunks"])
        rank = item["rank_full"]
        rerank_hit = item["row"].get("rerank", {}).get("hit")
        lines += [
            f"## {item['question']}",
            f"- Ожидание: {item['expected']}",
            f"- Источники: {', '.join(item['sources'])}",
            f"- Воронка: всего {total} → fetch_k {args.fetch_k} → filter(min_score) {item['m_filtered']} "
            f"→ top_k {args.top_k}",
            f"- Правильный чанк: embedding-ранг {rank if rank else '—'}"
            + (f"; после реранка в топ-{args.top_k}: {'да' if rerank_hit else 'нет'}" if rerank_hit is not None else ""),
        ]
        for name, _ in modes:
            r = item["row"][name]
            q = r["query"]
            lines.append(f"- **{name}**: file-hit={'да' if r['hit'] else 'нет'}, "
                         f"answer-hit={'да' if r['anshit'] else 'нет'}, kept={len(r['results'])}"
                         + (f", query={q!r}" if q != item["question"] else ""))
            for score, meta in r["results"]:
                lines.append(f"    - [{score:.3f}] {meta['source']} :: {meta['section']}")
        lines.append("")

    if answered:
        lines += ["## Качество ответов (no RAG vs naive RAG vs improved RAG)", ""]
        for a in answered:
            it = a["item"]
            lines += [
                f"### {it['question']}",
                f"- Эталон: {it['expected']}",
                f"- Судья: no RAG={a['jn']} · naive={a['ji']} · improved={a['jm']}",
                f"- Источники в ответе: {'да' if a['has_src'] else 'нет'} · "
                f"цитаты: {'да' if a['has_q'] else 'нет'} · цитаты подтверждены чанками: "
                f"{'да' if a['grounded'] else 'нет'}",
                "",
                "**Без RAG:**", a["no_rag"], "",
                "**Naive RAG:**", a["naive"], "",
                "**Improved RAG (rewrite+rerank+filter):**", a["improved"], "",
                "---", "",
            ]
        c = citation
        lines += [
            "## Источники и цитаты (на improved-ответах)",
            "",
            f"- Источники в ответе: {c['sources']}/{c['n']}",
            f"- Цитаты в ответе: {c['quotes']}/{c['n']}",
            f"- Цитаты подтверждены чанками: {c['grounded']}/{c['n']}",
        ]
        if args.judge:
            lines.append(f"- Ответ подтверждён контекстом (faithfulness): {c['faithful']}/{c['n']}")
        lines.append("")
        print(f"\nЦитаты/источники: источники {c['sources']}/{c['n']}, "
              f"цитаты {c['quotes']}/{c['n']}, подтверждены {c['grounded']}/{c['n']}"
              + (f", faithful {c['faithful']}/{c['n']}" if args.judge else ""))
        if args.judge:
            j = judged
            lines += [
                "## Сводка судьи",
                f"- Без RAG: {j['no_rag']}/{j['n']}",
                f"- Naive RAG: {j['naive']}/{j['n']}",
                f"- Improved RAG: {j['improved']}/{j['n']}",
                "",
            ]
            print(f"Судья: без RAG {j['no_rag']}/{j['n']}, naive {j['naive']}/{j['n']}, "
                  f"improved {j['improved']}/{j['n']}")

    if unknown_results:
        refused = sum(1 for u in unknown_results if u["refused"])
        lines += [
            "## Режим «не знаю» (вне корпуса, высокий порог)",
            "",
            f"- Отказ («не знаю»/уточнение): {refused}/{len(unknown_results)}",
            "",
        ]
        for u in unknown_results:
            lines += [f"- «{u['question']}» (чанков {u['kept']}, отказ={'да' if u['refused'] else 'нет'})",
                      f"  > {u['answer']}", ""]
        print(f"Режим «не знаю»: отказ {refused}/{len(unknown_results)}")

    os.makedirs(EVAL_DIR, exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\nОтчёт сохранён: {REPORT}")


if __name__ == "__main__":
    sys.exit(main())