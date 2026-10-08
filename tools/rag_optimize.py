#!/usr/bin/env python3
"""День 29: оптимизация локальной LLM под конкретную задачу — RAG-ответы с цитатами.

Задача: по вопросу и найденным чанкам дать фактический ответ строго в JSON
{answer, sources, quotes}, где цитаты — дословно из контекста.

Что оптимизируем:
  * параметры  — temperature, max_tokens, context window (num_ctx);
  * квантование — размер/память модели в ресурсах (MXFP4 у gpt-oss, Q4_K_M у llama3.2);
  * промпт-шаблон — под строгий формат с дословными цитатами (few-shot).

Сравниваем качество (ответ-хит, JSON, источники, grounded), скорость (latency, tok/s)
и ресурсы (размер модели в памяти) ДО и ПОСЛЕ. Отчёт: tools/rag_optimize/report.md.

Запуск:
  .venv/bin/python tools/rag_optimize.py --base-model gpt-oss:20b --limit 5 --repeats 1
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)

import document_indexer as di  # noqa: E402
import rag_local as rl  # noqa: E402

OUT_DIR = os.path.join(TOOLS, "rag_optimize")
REPORT = os.path.join(OUT_DIR, "report.md")
LOCAL_ENDPOINT = "http://localhost:11434/v1/chat/completions"

# Короткий «базовый» промпт (как в rag_local).
BASE_PROMPT = rl.ANSWER_SYS

# Оптимизированный промпт под задачу: явная роль, правила, формат и few-shot с дословной цитатой.
TUNED_PROMPT = (
    "Ты — система ответов по внутренней базе знаний. По вопросу и фрагментам контекста "
    "дай краткий фактический ответ с точными цитатами.\n"
    "Правила:\n"
    "1. Используй ТОЛЬКО текст контекста; ничего не добавляй от себя.\n"
    "2. answer — 1–3 предложения, только факты из контекста, на языке вопроса.\n"
    "3. sources — использованные фрагменты: n (номер [n] в контексте), source и section "
    "ДОСЛОВНО как в заголовке фрагмента.\n"
    "4. quotes — 1–2 ДОСЛОВНЫЕ подстроки из текста контекста (8–40 слов), каждая напрямую "
    "подтверждает ключевой факт. Копируй символ-в-символ, не перефразируй.\n"
    "5. Если ответа в контексте нет — {\"answer\":\"Не знаю\",\"sources\":[],\"quotes\":[]}.\n"
    "Формат строго JSON без markdown:\n"
    "{\"answer\":\"...\",\"sources\":[{\"n\":1,\"source\":\"...\",\"section\":\"...\"}],"
    "\"quotes\":[{\"text\":\"...\",\"n\":1}]}\n"
    "Пример:\n"
    "Контекст: [1] docs/faq.md :: Оплата\n Возврат возможен в течение 14 дней после покупки.\n"
    "Вопрос: За сколько дней можно вернуть товар?\n"
    "Ответ: {\"answer\":\"Возврат возможен в течение 14 дней.\","
    "\"sources\":[{\"n\":1,\"source\":\"docs/faq.md\",\"section\":\"Оплата\"}],"
    "\"quotes\":[{\"text\":\"Возврат возможен в течение 14 дней после покупки.\",\"n\":1}]}"
)


def ensure_model(name, base, num_ctx, rebuild=False):
    """Создаёт в Ollama модель name на базе base с num_ctx (если её нет или --rebuild)."""
    exist = subprocess.run(["ollama", "list"], capture_output=True, text=True).stdout
    if name in exist and not rebuild:
        return
    modelfile = f"FROM {base}\nPARAMETER num_ctx {num_ctx}\nPARAMETER temperature 0.1\n"
    with tempfile.NamedTemporaryFile("w", suffix=".Modelfile", delete=False) as f:
        f.write(modelfile)
        path = f.name
    print(f"  создаю модель {name} (num_ctx={num_ctx})…")
    subprocess.run(["ollama", "create", name, "-f", path], capture_output=True, text=True)
    os.unlink(path)


def ollama_ps():
    """Текущий загруженный модель/размер/контекст из `ollama ps`."""
    out = subprocess.run(["ollama", "ps"], capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines()[1:]:
        if not line.strip():
            continue
        name = line.split()[0]
        m = re.search(r"(\d+(?:\.\d+)?)\s*(GB|MB)", line)
        size = f"{m.group(1)} {m.group(2)}" if m else "—"
        ctx = "—"
        for tok in reversed(line.split()):
            if tok.isdigit() and 256 <= int(tok) <= 2_000_000:
                # последнее число в строке — колонка CONTEXT (если есть)
                ctx = tok
                break
        rows.append({"name": name, "size": size, "ctx": ctx})
    return rows


def model_file_size(name):
    out = subprocess.run(["ollama", "list"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] != name and not parts[0].startswith(name + ":"):
            continue
        i = parts.index("GB") if "GB" in parts else (parts.index("MB") if "MB" in parts else -1)
        if i > 0:
            return f"{parts[i-1]} {parts[i]}"
    return "—"


def loaded_size(model):
    for r in ollama_ps():
        if r["name"] == model or r["name"].startswith(model + ":"):
            return r["size"]
    return None


def call(model, prompt, results, question, temperature, max_tokens, reasoning_effort=None):
    msgs = [
        {"role": "system", "content": prompt},
        {"role": "system", "content": rl.context_block(results)},
        {"role": "user", "content": question},
    ]
    return rl.http_chat(LOCAL_ENDPOINT, model, "", msgs, temperature=temperature,
                        json_mode=True, timeout=600, max_tokens=max_tokens,
                        reasoning_effort=reasoning_effort)


def run_config(cfg, questions, args):
    print(f"\n=== Конфиг '{cfg['name']}': {cfg['model']} · temp={cfg['temperature']} · "
          f"max_tokens={cfg['max_tokens']} · top_k={cfg['top_k']} ===")
    # прогрев (первый вызов включает загрузку модели — не замеряем)
    try:
        call(cfg["model"], cfg["prompt"], di.search(load_index(), "прогрев", cfg["top_k"]),
             "прогрев", cfg["temperature"], cfg["max_tokens"], cfg.get("reasoning_effort"))
    except Exception as e:
        print(f"  прогрев не удался: {e}")
    res_size = loaded_size(cfg["model"])
    res_ctx = cfg["num_ctx"]
    print(f"  ресурсы: {res_size or '—'} в памяти, ctx={res_ctx}, файл {model_file_size(cfg['model'])}")

    runs = []
    for i, q in enumerate(questions, 1):
        question, expected = q["question"], q.get("expected", "")
        t0 = time.perf_counter()
        results = di.search(load_index(), question, cfg["top_k"], fetch_k=args.fetch_k,
                            min_score=args.min_score, rerank=args.rerank)
        retr_ms = (time.perf_counter() - t0) * 1000
        rec = {"question": question, "expected": expected, "retrieval_ms": retr_ms,
               "kept": len(results), "calls": []}
        for _ in range(args.repeats):
            try:
                r = call(cfg["model"], cfg["prompt"], results, question, cfg["temperature"],
                         cfg["max_tokens"], cfg.get("reasoning_effort"))
                data = rl.strip_json(r["content"])
                a = rl.assess(data, results)
                rec["calls"].append({
                    "latency_ms": r["latency_ms"], "prompt_tokens": r["prompt_tokens"],
                    "completion_tokens": r["completion_tokens"],
                    "chars": len(str((data or {}).get("answer", ""))),
                    "hit": rl.answer_hit(str((data or {}).get("answer", "")), expected),
                    **{k: a[k] for k in ("json_valid", "valid", "sources", "quotes", "grounded", "refused")},
                    "answer": (data or {}).get("answer", ""), "raw": r["content"][:400],
                })
            except Exception as e:
                rec["calls"].append({"error": str(e)})
        runs.append(rec)
        c = rec["calls"][0]
        if "error" in c:
            print(f"  [{i}/{len(questions)}] ошибка: {c['error']}")
        else:
            print(f"  [{i}/{len(questions)}] retr {retr_ms:.0f}ms kept={len(results)} · "
                  f"{c['latency_ms']/1000:.1f}s · json={'да' if c['json_valid'] else 'нет'} "
                  f"grounded={'да' if c['grounded'] else 'нет'} hit={c['hit']}")
    return {"cfg": cfg, "runs": runs, "res_size": res_size, "res_ctx": res_ctx,
            "file_size": model_file_size(cfg["model"])}


_INDEX = None


def load_index():
    global _INDEX
    if _INDEX is None:
        _INDEX = di.load_index("all", "structure", "ollama")
    return _INDEX


def summarize(result):
    calls = [c for r in result["runs"] for c in r["calls"] if "error" not in c]
    n = len(calls)
    agg = {
        "n": n, "errors": sum(1 for r in result["runs"] for c in r["calls"] if "error" in c),
        "json": sum(c["json_valid"] for c in calls), "valid": sum(c["valid"] for c in calls),
        "sources": sum(c["sources"] for c in calls), "quotes": sum(c["quotes"] for c in calls),
        "grounded": sum(c["grounded"] for c in calls),
        "hit": sum(1 for c in calls if c["hit"]), "hit_n": sum(1 for c in calls if c["hit"] is not None),
        "latency": statistics.mean([c["latency_ms"] for c in calls]) if calls else 0,
        "prompt_tok": statistics.mean([c["prompt_tokens"] for c in calls]) if calls else 0,
        "completion_tok": statistics.mean([c["completion_tokens"] for c in calls]) if calls else 0,
        "chars": statistics.mean([c["chars"] for c in calls]) if calls else 0,
        "tok_s": statistics.mean([c["completion_tokens"] / (c["latency_ms"] / 1000)
                                  for c in calls if c["latency_ms"]]) if calls else 0,
    }
    return agg


def main():
    ap = argparse.ArgumentParser(description="Оптимизация локальной LLM под RAG (День 29)")
    ap.add_argument("--base-model", default="gpt-oss:20b")
    ap.add_argument("--questions", default=os.path.join(TOOLS, "rag_eval", "questions.json"))
    ap.add_argument("--limit", type=int, default=5)
    ap.add_argument("--repeats", type=int, default=1)
    ap.add_argument("--fetch-k", type=int, default=20)
    ap.add_argument("--min-score", type=float, default=0.7)
    ap.add_argument("--rerank", action="store_true", default=True)
    ap.add_argument("--no-rerank", dest="rerank", action="store_false")
    ap.add_argument("--rebuild", action="store_true", help="пересоздать оптимизированные модели")
    args = ap.parse_args()

    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)[:args.limit]

    opt_model = "gpt-oss-rag-opt" if args.base_model.startswith("gpt-oss") else "local-rag-opt"
    ctx_model = "gpt-oss-rag-1k" if args.base_model.startswith("gpt-oss") else "local-rag-1k"
    print("Готовлю оптимизированные модели Ollama:")
    ensure_model(opt_model, args.base_model, 8192, args.rebuild)
    ensure_model(ctx_model, args.base_model, 1024, args.rebuild)

    configs = [
        {"name": "base", "model": args.base_model, "prompt": BASE_PROMPT, "temperature": 0.7,
         "max_tokens": None, "reasoning_effort": None, "top_k": 4, "num_ctx": "по умолчанию"},
        {"name": "opt", "model": opt_model, "prompt": TUNED_PROMPT, "temperature": 0.1,
         "max_tokens": 1024, "reasoning_effort": "low", "top_k": 4, "num_ctx": 8192},
        {"name": "opt-ctx1k", "model": ctx_model, "prompt": TUNED_PROMPT, "temperature": 0.1,
         "max_tokens": 1024, "reasoning_effort": "low", "top_k": 8, "num_ctx": 1024},
    ]
    results = [run_config(cfg, questions, args) for cfg in configs]
    summ = {r["cfg"]["name"]: summarize(r) for r in results}

    os.makedirs(OUT_DIR, exist_ok=True)
    lines = [
        "# Оптимизация локальной LLM под RAG-задачу (День 29)",
        "",
        f"Дата: {datetime.now(timezone.utc).isoformat()}",
        f"Базовая модель: {args.base_model} · вопросов: {len(questions)} · repeats: {args.repeats}",
        "Задача: ответ по базе знаний строго в JSON {answer, sources, quotes}, цитаты — дословно из контекста.",
        "",
        "## Что оптимизировано",
        "",
        "| параметр | base | opt |",
        "|---|---|---|",
        "| temperature | 0.7 | **0.1** (детерминизм фактов) |",
        "| reasoning_effort | по умолчанию (много «размышлений») | **low** (короче thinking → меньше latency и токенов) |",
        "| max_tokens | не ограничен | **1024** (обрез runaway) |",
        "| context window (num_ctx) | по умолчанию | **8192** (хватает на top_k + историю) |",
        "| top_k | 4 | 4 |",
        "| промпт | короткий | **task-промпт + few-shot с дословной цитатой** |",
        "",
        "`opt-ctx1k` — отдельная проверка влияния окна: num_ctx=**1024** при top_k=8 (контекст не влезает → обрезка).",
        "",
        "## Качество / скорость / ресурсы",
        "",
        "| конфиг | ответ-хит | JSON | источники | grounded | ошибок | avg latency | ср. out tok | ср. длина ответа | tok/s | модель в памяти |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        s = summ[r["cfg"]["name"]]
        lines.append(
            f"| {r['cfg']['name']} | {s['hit']}/{s['hit_n']} | {s['json']}/{s['n']} | "
            f"{s['sources']}/{s['n']} | {s['grounded']}/{s['n']} | {s['errors']} | "
            f"{s['latency']/1000:.1f} s | {s['completion_tok']:.0f} | {s['chars']:.0f} симв. | "
            f"{s['tok_s']:.1f} | {r['res_size'] or r['file_size']} |"
        )
    lines.append("")

    for r in results:
        s = summ[r["cfg"]["name"]]
        lines += [f"## Конфиг `{r['cfg']['name']}` — {r['cfg']['model']}",
                  f"top_k={r['cfg']['top_k']}, temp={r['cfg']['temperature']}, "
                  f"reasoning_effort={r['cfg']['reasoning_effort']}, "
                  f"max_tokens={r['cfg']['max_tokens']}, "
                  f"num_ctx={r['res_ctx']}, размер модели {r['file_size']}", ""]
        for rec in r["runs"]:
            c = rec["calls"][0]
            lines.append(f"**{rec['question']}**")
            if "error" in c:
                lines += [f"- ошибка: {c['error']}", ""]
                continue
            lines += [f"- ok={c['valid']} json={c['json_valid']} grounded={c['grounded']} hit={c['hit']} "
                      f"latency={c['latency_ms']/1000:.1f}s out_tok={c['completion_tokens']}", "",
                      f"> {c['answer']}", ""]
        lines.append("---\n")

    # Выводы (текстом).
    b, o = summ.get("base"), summ.get("opt")
    if b and o:
        lines += ["## Выводы", ""]
        if o["grounded"] >= b["grounded"] and o["json"] >= b["json"]:
            lines.append(f"- **Качество не хуже**: grounded {b['grounded']}→{o['grounded']}, "
                         f"JSON {b['json']}→{o['json']}, ответ-хит {b['hit']}→{o['hit']}.")
        else:
            lines.append(f"- Качество: grounded {b['grounded']}→{o['grounded']}, "
                         f"JSON {b['json']}→{o['json']}, ответ-хит {b['hit']}→{o['hit']}.")
        dlat = (b["latency"] - o["latency"]) / 1000
        lines.append(f"- Скорость: avg latency {b['latency']/1000:.1f}s → {o['latency']/1000:.1f}s "
                     f"({'быстрее' if dlat >= 0 else 'медленнее'} на {abs(dlat):.1f}s, "
                     f"{b['latency']/max(o['latency'],1):.1f}×).")
        lines.append(f"- Ресурсы: в среднем {o['completion_tok']:.0f} out-токенов на ответ "
                     f"(было {b['completion_tok']:.0f}); модель в памяти {results[1]['res_size'] or '—'}.")
        lines.append("")
    lines += [
        "## Квантование",
        "",
        f"- `{args.base_model}` поставляется в **MXFP4** (нативное 4-битное квантование) — это и есть "
        "квантованный чекпоинт из коробки; размер ~13 ГБ, влезает в 32 ГБ unified.",
        "- Для сравнения: `llama3.2:3b` — **Q4_K_M** (~2 ГБ): в разы легче и быстрее, но заметно слабее "
        "держит строгий JSON и цитаты (День 28: grounded 0/10).",
        "- Итог: под задачу RAG с цитатами выгоднее крупная модель в нативном 4-бит кванте, чем мелкая; "
        "оптимизация промпта/параметров выжимает из неё стабильный формат.",
        "",
    ]
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\nОтчёт: {REPORT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
