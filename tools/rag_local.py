#!/usr/bin/env python3
"""День 28: RAG полностью локально — локальный retrieval + локальная генерация,
сравнение с облачной моделью по качеству, скорости и стабильности.

Пайплайн:
  индекс недели 6 (tools/index/index_<source>_<strategy>_<embedding>.json)
  → retrieval локально (nomic-embed-text через ollama) → контекст
  → генерация ответа локальной моделью (ollama) и (если есть ключ) облачной.

Что измеряем на каждый вызов:
  * качество   — попал ли факт-ответ (лексически; опц. LLM-судья), есть ли источники/цитаты,
                 подтверждены ли цитаты чанками;
  * скорость   — задержка ответа (avg/p50/банд), токены/с и latency retrieval;
  * стабильность — валидность строгого JSON, доля отказов/ошибок, число retry на серии прогонов.

Запуск:
  .venv/bin/python tools/rag_local.py --limit 5 --repeats 2
  LLM_API_KEY=... .venv/bin/python tools/rag_local.py --judge   # + сравнение с облаком
"""

import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.request
from datetime import datetime, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)

import document_indexer as di  # noqa: E402
import rag_eval as re_  # noqa: E402  (llm_config, _https_context)

OUT_DIR = os.path.join(TOOLS, "rag_local")
REPORT = os.path.join(OUT_DIR, "report.md")

ANSWER_SYS = (
    "Ты — ассистент по базе знаний. Отвечай, опираясь ТОЛЬКО на приведённый контекст. "
    "Не добавляй факты вне контекста. Если ответа в контексте нет — ответь «Не знаю» и попроси уточнение. "
    "Верни ТОЛЬКО JSON без markdown: "
    "{\"answer\":\"...\",\"sources\":[{\"n\":1,\"source\":\"...\",\"section\":\"...\"}],"
    "\"quotes\":[{\"text\":\"дословный фрагмент из контекста\",\"n\":1}]}."
)


# --- LLM ----------------------------------------------------------------

def http_chat(endpoint, model, key, messages, temperature=0.3, json_mode=True, timeout=300):
    """OpenAI-совместимый вызов. Возвращает content/usage/latency. Пустой key — локальный сервер."""
    body = {"model": model, "messages": messages, "temperature": temperature}
    if json_mode:
        body["response_format"] = {"type": "json_object"}
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(endpoint, data=data, headers=headers)
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout, context=re_._https_context()) as resp:
        obj = json.loads(resp.read().decode("utf-8"))
    latency_ms = (time.perf_counter() - t0) * 1000
    msg = (obj.get("choices") or [{}])[0].get("message") or {}
    usage = obj.get("usage") or {}
    return {
        "content": (msg.get("content") or "").strip(),
        "latency_ms": latency_ms,
        "prompt_tokens": usage.get("prompt_tokens", 0),
        "completion_tokens": usage.get("completion_tokens", 0),
    }


class Provider:
    def __init__(self, name, endpoint, model, key):
        self.name, self.endpoint, self.model, self.key = name, endpoint, model, key

    def call(self, messages, **kw):
        return http_chat(self.endpoint, self.model, self.key, messages, **kw)


# --- RAG helpers --------------------------------------------------------

def strip_json(s):
    s = s.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    a, b = s.find("{"), s.rfind("}")
    if a < 0 or b <= a:
        return None
    try:
        return json.loads(s[a:b + 1])
    except Exception:
        return None


def context_block(results):
    if not results:
        return "Контекст из базы знаний: релевантных данных не найдено."
    parts = ["Контекст из базы знаний:"]
    for i, (score, m) in enumerate(results, 1):
        parts.append(f"[{i}] {m['source']} :: {m['section']} (chunk {m['chunk_id']}, score {score:.3f})\n{m['snippet']}")
    return "\n".join(parts)


def rag_messages(question, results):
    return [
        {"role": "system", "content": ANSWER_SYS},
        {"role": "system", "content": context_block(results)},
        {"role": "user", "content": question},
    ]


def assess(data, results):
    """Разбирает ответ на метрики: structural validity, источники, цитаты, grounded, отказ."""
    a = {
        "valid": False, "json_valid": data is not None, "sources": False,
        "quotes": False, "grounded": False, "refused": False,
    }
    if not data:
        return a
    answer = str(data.get("answer", "")).strip()
    if not answer:
        return a
    a["valid"] = True
    a["refused"] = "не знаю" in answer.lower()
    src = data.get("sources") or []
    quotes = data.get("quotes") or []
    a["sources"] = bool(src)
    a["quotes"] = bool(quotes)
    if quotes:
        ctx = re.sub(r"\s+", " ", " ".join(m["snippet"] for _, m in results)).lower()
        a["grounded"] = any(
            (q := re.sub(r"\s+", " ", str(it.get("text", "")).strip()).lower()) and q in ctx
            for it in quotes
        )
    return a


def answer_hit(answer, expected):
    """Лексический прокси качества: есть ли в ответе слова-факты из эталона."""
    toks = [t for t in re.findall(r"[a-zа-яё0-9]+", expected.lower()) if len(t) >= 5]
    if not toks:
        return None
    low = answer.lower()
    return any(t in low for t in toks)


def generate(provider, question, results):
    """RAG-ответ строгим JSON c валидацией и одним retry. Возвращает (data, metrics)."""
    msgs = rag_messages(question, results)
    m = {"calls": 0, "retries": 0, "errors": 0, "latency_ms": 0.0, "completion_tokens": 0,
         "json_valid": False, "valid": False, "sources": False, "quotes": False,
         "grounded": False, "refused": False}
    raw = ""
    try:
        r = provider.call(msgs)
        m["calls"] += 1
        m["latency_ms"] += r["latency_ms"]
        m["completion_tokens"] += r["completion_tokens"]
        raw = r["content"]
        data = strip_json(raw)
        a = assess(data, results)
        if a["json_valid"] and a["valid"]:
            m.update({k: a[k] for k in ("json_valid", "valid", "sources", "quotes", "grounded", "refused")})
            return data, m
        # retry один раз (невалидный JSON / пустой ответ)
        m["retries"] += 1
        fix = msgs + [{"role": "assistant", "content": raw},
                      {"role": "user", "content": "Формат неверный. Верни строго JSON по схеме."}]
        r2 = provider.call(fix)
        m["calls"] += 1
        m["latency_ms"] += r2["latency_ms"]
        m["completion_tokens"] += r2["completion_tokens"]
        data2 = strip_json(r2["content"])
        a2 = assess(data2, results)
        m["json_valid"] = m["json_valid"] or a2["json_valid"]
        if a2["json_valid"] and a2["valid"]:
            m.update({k: a2[k] for k in ("valid", "sources", "quotes", "grounded", "refused")})
            return data2, m
        fallback = data2 or data or {"answer": raw.strip(), "sources": [], "quotes": []}
        m["refused"] = "не знаю" in str(fallback.get("answer", "")).lower()
        m["sources"] = bool(fallback.get("sources"))
        m["quotes"] = bool(fallback.get("quotes"))
        return fallback, m
    except Exception as e:
        m["errors"] += 1
        return {"answer": f"ошибка: {e}", "sources": [], "quotes": []}, m


def render(data, results, top_k):
    ans = str(data.get("answer", "")).strip()
    src = data.get("sources") or []
    quotes = data.get("quotes") or []
    if not src and results and "не знаю" not in ans.lower():
        src = [{"n": i + 1, "source": mm["source"], "section": mm["section"]}
               for i, (_, mm) in enumerate(results[:top_k])]
    out = [ans]
    for s in src:
        out.append(f"  [{s.get('n','')}] {s.get('source','')} :: {s.get('section','')}")
    for q in quotes:
        out.append(f"  «{str(q.get('text','')).strip()}» [{q.get('n','')}]")
    return "\n".join(out)


def judge(provider, question, expected, answer):
    prompt = (f"Вопрос: {question}\nЭталонный ответ: {expected}\nОтвет модели: {answer}\n"
              "Содержит ли ответ модели этот факт (или эквивалент)? Ответь одним словом: да или нет.")
    try:
        out = provider.call([{"role": "user", "content": prompt}], json_mode=False,
                            timeout=180)["content"].strip().lower()
        return "да" if out.startswith("да") else "нет"
    except Exception:
        return "—"


# --- Run ----------------------------------------------------------------

def pct(part, whole):
    return f"{part}/{whole}" if whole else "—"


def main():
    ap = argparse.ArgumentParser(description="Локальный RAG + сравнение с облаком (День 28)")
    ap.add_argument("--source", default="all")
    ap.add_argument("--strategy", choices=["fixed", "structure"], default="structure")
    ap.add_argument("--embedding", choices=["ollama", "tfidf"], default="ollama")
    ap.add_argument("--top-k", type=int, default=3)
    ap.add_argument("--fetch-k", type=int, default=20)
    ap.add_argument("--min-score", type=float, default=0.7)
    ap.add_argument("--rerank", action="store_true", default=True)
    ap.add_argument("--no-rerank", dest="rerank", action="store_false")
    ap.add_argument("--rewrite", action="store_true", help="query rewrite локальной моделью")
    ap.add_argument("--questions", default=os.path.join(TOOLS, "rag_eval", "questions.json"))
    ap.add_argument("--limit", type=int, default=0, help="сколько вопросов взять (0 = все)")
    ap.add_argument("--repeats", type=int, default=1, help="повторов на вопрос (для стабильности)")
    ap.add_argument("--local-endpoint", default="http://localhost:11434/v1/chat/completions")
    ap.add_argument("--local-model", default="llama3.2:3b")
    ap.add_argument("--judge", action="store_true", help="LLM-судья против эталона")
    ap.add_argument("--no-cloud", action="store_true")
    args = ap.parse_args()

    index_path = di.index_path(args.source, args.strategy, args.embedding)
    if not os.path.exists(index_path):
        print(f"Индекс не найден: {index_path}. Соберите document_indexer (Неделя 6).")
        return 1
    index = di.load_index(args.source, args.strategy, args.embedding)
    print(f"Индекс: {index_path} ({len(index['chunks'])} чанков, {index['embedding_model']})")

    local = Provider("local", args.local_endpoint, args.local_model, "")
    cendpoint, cmodel, ckey = re_.llm_config()
    cloud = None if (args.no_cloud or not ckey) else Provider("cloud", cendpoint, cmodel, ckey)
    providers = [local] + ([cloud] if cloud else [])
    print(f"Локальная: {local.model} @ {local.endpoint}")
    print(f"Облачная:  {cloud.model if cloud else 'нет (LLM_API_KEY не задан)'}")

    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)
    if args.limit:
        questions = questions[:args.limit]
    print(f"Вопросов: {len(questions)} · повторов: {args.repeats}")

    retr_lat = []
    rows = []
    for i, q in enumerate(questions, 1):
        question, expected = q["question"], q.get("expected", "")
        t0 = time.perf_counter()
        results = di.search(index, question, args.top_k, fetch_k=args.fetch_k,
                            min_score=args.min_score, rerank=args.rerank)
        retr_ms = (time.perf_counter() - t0) * 1000
        retr_lat.append(retr_ms)
        print(f"\n[{i}/{len(questions)}] {question[:70]}… retrieval {retr_ms:.0f} ms, чанков {len(results)}")

        row = {"question": question, "expected": expected, "retrieval_ms": retr_ms,
               "chunks": [{"source": m["source"], "section": m["section"], "score": s}
                          for s, m in results], "providers": {}}
        for p in providers:
            calls, comp = [], 0
            errs, retries, jvalid, valid, srcs, quotes_ct, grounded, hits = 0, 0, 0, 0, 0, 0, 0, []
            answers, judges = [], []
            for _ in range(args.repeats):
                data, m = generate(p, question, results)
                raw_answer = str(data.get("answer", "")).strip()
                answers.append(render(data, results, args.top_k))
                calls.append(m["latency_ms"])
                comp += m["completion_tokens"]
                errs += m["errors"]
                retries += m["retries"]
                jvalid += 1 if m["json_valid"] else 0
                valid += 1 if m["valid"] else 0
                srcs += 1 if m["sources"] else 0
                quotes_ct += 1 if m["quotes"] else 0
                grounded += 1 if m["grounded"] else 0
                h = answer_hit(raw_answer, expected)
                if h is not None:
                    hits.append(1 if h else 0)
                if args.judge:
                    judges.append(judge(cloud or local, question, expected, raw_answer))
            agg = {
                "runs": args.repeats, "errors": errs, "retries": retries,
                "json_valid": jvalid, "valid": valid, "sources": srcs, "quotes": quotes_ct,
                "grounded": grounded,
                "hit": sum(hits) if hits else None, "hit_n": len(hits),
                "judge_yes": judges.count("да") if judges else None,
                "avg_ms": statistics.mean(calls), "p50_ms": statistics.median(calls),
                "std_ms": statistics.pstdev(calls) if len(calls) > 1 else 0.0,
                "tok_s": (comp / (sum(calls) / 1000)) if sum(calls) else 0.0,
                "answer": answers[0], "answers": answers,
            }
            row["providers"][p.name] = agg
            print(f"    {p.name:<5} ok={valid}/{args.repeats} json={jvalid}/{args.repeats} "
                  f"src={srcs}/{args.repeats} hit={agg['hit']}/{agg['hit_n']} "
                  f"avg={agg['avg_ms']/1000:.1f}s {agg['tok_s']:.1f} tok/s retries={retries} errors={errs}")
        rows.append(row)

    # --- report ---
    os.makedirs(OUT_DIR, exist_ok=True)
    lines = [
        "# Локальный RAG + сравнение с облаком (День 28)",
        "",
        f"Дата: {datetime.now(timezone.utc).isoformat()}",
        f"Индекс (Неделя 6): source={args.source} strategy={args.strategy} embedding={args.embedding} "
        f"({len(index['chunks'])} чанков)",
        f"Retrieval: локально (ollama {index['embedding_model']}) · top_k={args.top_k} "
        f"fetch_k={args.fetch_k} min_score={args.min_score} rerank={args.rerank}",
        f"Генерация: local={local.model} · cloud={cloud.model if cloud else '—'} · repeats={args.repeats}",
        f"Вопросов: {len(questions)}",
        "",
        "```",
        "индекс(Неделя6) ─► retrieval ЛОКАЛЬНО (nomic-embed-text) ─► контекст+вопрос",
        "                                                          ├─► LOCAL модель (ollama)  ─┐",
        "                                                          └─► CLOUD модель (opt.)    ─┴─► сравнение",
        "```",
        "",
        f"## Retrieval (локально)\n",
        f"- Вопросов: {len(questions)} · avg latency: {statistics.mean(retr_lat):.0f} ms "
        f"(p50 {statistics.median(retr_lat):.0f} ms)",
        f"- avg чанков в контексте: {statistics.mean([len(r['chunks']) for r in rows]):.1f}",
        "",
        "## Сводка: качество / скорость / стабильность",
        "",
        "| провайдер | ответ-хит | судья | JSON валиден | ответ непуст | источники | цитаты grounded | ошибок | retry | avg latency | tok/s |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for p in providers:
        jvalid = sum(r["providers"][p.name]["json_valid"] for r in rows)
        valid = sum(r["providers"][p.name]["valid"] for r in rows)
        srcs = sum(r["providers"][p.name]["sources"] for r in rows)
        grounded = sum(r["providers"][p.name]["grounded"] for r in rows)
        errs = sum(r["providers"][p.name]["errors"] for r in rows)
        retr = sum(r["providers"][p.name]["retries"] for r in rows)
        hits = [r["providers"][p.name]["hit"] for r in rows if r["providers"][p.name]["hit_n"]]
        hit_sum = sum(hits) if hits else None
        hit_n = sum(r["providers"][p.name]["hit_n"] for r in rows)
        jys = [r["providers"][p.name]["judge_yes"] for r in rows if r["providers"][p.name]["judge_yes"] is not None]
        jy = sum(jys) if jys else None
        avg_ms = statistics.mean([r["providers"][p.name]["avg_ms"] for r in rows])
        runs = len(rows) * args.repeats
        toks = statistics.mean([r["providers"][p.name]["tok_s"] for r in rows])
        lines.append(
            f"| {p.name} | {hit_sum}/{hit_n} | {f'{jy}/{len(jys)}' if jy is not None else '—'} "
            f"| {jvalid}/{runs} | {valid}/{runs} | {srcs}/{runs} | {grounded}/{runs} "
            f"| {errs} | {retr} | {avg_ms/1000:.1f} s | {toks:.1f} |"
        )
    lines.append("")

    for r in rows:
        lines += [f"## {r['question']}", f"- Ожидание: {r['expected']}",
                  f"- Retrieval: {r['retrieval_ms']:.0f} ms, чанков {len(r['chunks'])}: "
                  + "; ".join(f"{c['section']} ({c['score']:.3f})" for c in r["chunks"]), ""]
        for p in providers:
            a = r["providers"][p.name]
            lines += [f"**{p.name}** ({p.model}) — ok {a['valid']}/{a['runs']}, JSON {a['json_valid']}/{a['runs']}, "
                      f"источники {a['sources']}/{a['runs']}, grounded {a['grounded']}/{a['runs']}, "
                      f"hit {a['hit']}/{a['hit_n']}, avg {a['avg_ms']/1000:.1f}s, {a['tok_s']:.1f} tok/s, "
                      f"retry {a['retries']}, errors {a['errors']}", "", a["answer"], ""]
        lines.append("---\n")

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\nОтчёт: {REPORT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
