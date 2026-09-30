#!/usr/bin/env python3
"""День 21: локальная индексация документов.

Пайплайн: собрать корпус (README/статьи/код) → разбить на чанки (2 стратегии) →
построить TF-IDF эмбеддинги → сохранить индекс в JSON → поиск по косинусному сходству.

Стратегии чанкинга:
  fixed     — фиксированный размер в токенах с перекрытием;
  structure — по структуре: у Markdown — разделы по заголовкам, у кода — файлы;
              слишком длинные разделы дополнительно режутся fixed-чанками.

Использование:
  python tools/document_indexer.py                 # собрать оба индекса + отчёт + примеры поиска
  python tools/document_indexer.py --search "текст" # поиск по уже построенным индексам
  python tools/document_indexer.py --strategy fixed --search "..."   # поиск по одной стратегии
"""

import argparse
import glob
import json
import math
import os
import re
import sys
import urllib.request
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
INDEX_DIR = os.path.join(TOOLS, "index")

TOKEN_RE = re.compile(r"[a-zа-яё0-9_]+")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")

STOPWORDS = {
    # русские
    "и", "в", "во", "не", "на", "я", "бы", "он", "что", "это", "с", "она", "как", "а", "по",
    "то", "но", "они", "к", "из", "у", "же", "мы", "за", "его", "для", "или", "до", "о", "так",
    "при", "её", "быть", "этот", "который", "уже", "был", "только", "ещё", "вот", "от", "все",
    "если", "где", "теперь", "чем", "да", "ли", "тогда", "можно", "нужно", "надо", "очень",
    "также", "всё", "этот", "каждый", "через", "после", "между", "эти", "там", "здесь",
    # английские
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was",
    "be", "as", "at", "by", "from", "it", "this", "that", "these", "those", "will", "can",
    "may", "also", "but", "not", "all", "into", "about", "after", "over",
}


def _out(msg: str) -> None:
    print(msg, flush=True)


def collect_documents(source: str = "all") -> list:
    """Возвращает [(title, source, text)].
    source == "all" — весь проект (README/статьи/код); иначе подпапка tools/<source>."""
    docs = []
    paths = []

    if source in ("", "all"):
        md = [os.path.join(ROOT, "README.md"), os.path.join(ROOT, "AGENTS.md")]
        md += sorted(glob.glob(os.path.join(TOOLS, "docs", "*.md")))
        md += sorted(glob.glob(os.path.join(TOOLS, "docs", "*.txt")))
        paths += md
        # Исходники проекта (без build/).
        for pattern in (
            os.path.join(ROOT, "app", "src", "main", "java", "**", "*.kt"),
            os.path.join(ROOT, "app", "src", "main", "res", "values", "*.xml"),
            os.path.join(ROOT, "tools", "*.py"),
            os.path.join(ROOT, "app", "build.gradle.kts"),
        ):
            paths += [p for p in glob.glob(pattern, recursive=True) if "/build/" not in p]
    else:
        base = os.path.abspath(os.path.join(TOOLS, source))
        # Не позволяем выйти за пределы tools/.
        if not base.startswith(os.path.abspath(TOOLS) + os.sep):
            _out(f"Ошибка: путь '{source}' вне tools/")
            return docs
        paths = [p for p in glob.glob(os.path.join(base, "**", "*"), recursive=True) if os.path.isfile(p)]

    seen = set()
    for p in paths:
        real = os.path.realpath(p)
        if real in seen or not os.path.isfile(p):
            continue
        seen.add(real)
        try:
            with open(p, encoding="utf-8", errors="ignore") as f:
                text = f.read()
        except OSError:
            continue
        if not text.strip():
            continue
        title = os.path.relpath(p, ROOT)
        docs.append((title, real, text))
    return docs


def tokenize(text: str) -> list:
    return TOKEN_RE.findall(text.lower())


def count_tokens(text: str) -> int:
    return len(text.split())


def markdown_sections(text: str) -> list:
    """[(section_title, section_text)] — разрезает markdown по заголовкам."""
    sections = []
    title = ""
    buf = []
    for line in text.splitlines():
        m = HEADING_RE.match(line)
        if m:
            if title or buf:
                sections.append((title if title else "doc", "\n".join(buf).strip()))
            title = m.group(2).strip()
            buf = []
        else:
            buf.append(line)
    if title or buf:
        sections.append((title if title else "doc", "\n".join(buf).strip()))
    return [(t, c) for t, c in sections if c.strip()]


def chunk_fixed(text: str, size: int, overlap: int) -> list:
    """Фиксированный размер в токенах (по словам) с перекрытием."""
    words = text.split()
    chunks = []
    start = 0
    while start < len(words):
        end = min(start + size, len(words))
        chunks.append(" ".join(words[start:end]))
        if end == len(words):
            break
        start += size - overlap
    return chunks


def build_chunks(docs: list, strategy: str, params: dict) -> list:
    """Собирает чанки с метаданными (source, title, section, chunk_id)."""
    fixed_size = params["size"]
    fixed_overlap = params["overlap"]
    max_section = params["max_section"]
    chunks = []
    doc_id = 0
    for title, source, text in docs:
        if strategy == "structure":
            if source.endswith(".md"):
                parts = [(sec, sec_text) for sec, sec_text in markdown_sections(text)]
            else:
                parts = [(os.path.basename(source), text)]
            i = 0
            for sec, sec_text in parts:
                if count_tokens(sec_text) <= max_section:
                    chunks.append(
                        {
                            "chunk_id": f"{doc_id:03d}-{i:04d}",
                            "source": title,
                            "title": os.path.basename(source),
                            "section": sec,
                            "text": sec_text,
                        }
                    )
                    i += 1
                else:
                    for part in chunk_fixed(sec_text, fixed_size, fixed_overlap):
                        chunks.append(
                            {
                                "chunk_id": f"{doc_id:03d}-{i:04d}",
                                "source": title,
                                "title": os.path.basename(source),
                                "section": sec,
                                "text": part,
                            }
                        )
                        i += 1
        else:  # fixed
            for j, part in enumerate(chunk_fixed(text, fixed_size, fixed_overlap)):
                chunks.append(
                    {
                        "chunk_id": f"{doc_id:03d}-{j:04d}",
                        "source": title,
                        "title": os.path.basename(source),
                        "section": os.path.basename(source),
                        "text": part,
                    }
                )
        doc_id += 1
    for c in chunks:
        c["tokens"] = count_tokens(c["text"])
        c["chars"] = len(c["text"])
    return chunks


OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
OLLAMA_MODEL = os.environ.get("OLLAMA_EMBED_MODEL", "nomic-embed-text")


def ollama_embed(texts: list, batch: int = 32) -> list:
    """Эмбеддинги через локальный ollama (/api/embed, пачками)."""
    vectors = []
    for i in range(0, len(texts), batch):
        body = json.dumps(
            {"model": OLLAMA_MODEL, "input": texts[i : i + batch]}, ensure_ascii=False
        ).encode("utf-8")
        req = urllib.request.Request(
            OLLAMA_URL + "/api/embed", data=body, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        vectors.extend(data["embeddings"])
    return vectors


def build_index(strategy: str, params: dict, source: str = "all", embedding: str = "ollama") -> dict:
    """Строит индекс: чанки + эмбеддинги (ollama — плотные векторы, tfidf — разреженные)."""
    docs = collect_documents(source)
    chunks = build_chunks(docs, strategy, params)

    if embedding == "ollama":
        vectors = ollama_embed([c["text"] for c in chunks])
        dim = len(vectors[0]) if vectors else 0
        model = OLLAMA_MODEL
    else:  # tfidf
        df = Counter()
        token_sets = []
        for c in chunks:
            toks = {t for t in tokenize(c["text"]) if t not in STOPWORDS}
            token_sets.append(toks)
            for w in toks:
                df[w] += 1
        n = len(chunks)
        idf = {w: math.log((n + 1) / (df[w] + 1)) + 1.0 for w in df}
        vectors = []
        for c, toks in zip(chunks, token_sets):
            freq = Counter(tokenize(c["text"]))
            total = sum(freq.values()) or 1
            raw = {w: (freq[w] / total) * idf[w] for w in toks}
            norm = math.sqrt(sum(v * v for v in raw.values()))
            vectors.append({w: v / norm for w, v in raw.items()} if norm else {})
        dim = 0
        model = "tfidf-v1"

    corpus_chars = sum(len(d[2]) for d in docs)
    corpus_tokens = sum(count_tokens(d[2]) for d in docs)
    return {
        "strategy": strategy,
        "embedding": embedding,
        "embedding_model": model,
        "embedding_dim": dim,
        "source": source,
        "params": params,
        "built_at": datetime_now(),
        "corpus": {
            "documents": len(docs),
            "chars": corpus_chars,
            "tokens": corpus_tokens,
            "pages_est": round(corpus_chars / 2000.0, 1),
        },
        "idf": idf if embedding == "tfidf" else {},
        "chunks": chunks,
        "vectors": vectors,
    }


def datetime_now() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


def index_path(source: str, strategy: str, embedding: str) -> str:
    safe = re.sub(r"[^a-zA-Z0-9_-]", "_", source)
    return os.path.join(INDEX_DIR, f"index_{safe}_{strategy}_{embedding}.json")


def save_index(index: dict) -> str:
    os.makedirs(INDEX_DIR, exist_ok=True)
    path = index_path(index["source"], index["strategy"], index["embedding"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    return path


def load_index(source: str = "all", strategy: str = "structure", embedding: str = "ollama") -> dict:
    path = index_path(source, strategy, embedding)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def embed_query(query: str, index: dict) -> object:
    if index["embedding"] == "ollama":
        vec = ollama_embed([query])[0]
        norm = math.sqrt(sum(x * x for x in vec))
        return [x / norm for x in vec] if norm else vec
    freq = Counter(tokenize(query))
    total = sum(freq.values()) or 1
    raw = {w: (freq[w] / total) * index["idf"].get(w, 0.0) for w in freq if w not in STOPWORDS}
    norm = math.sqrt(sum(v * v for v in raw.values()))
    return {w: v / norm for w, v in raw.items()} if norm else {}


def _cosine(a, b) -> float:
    if not a or not b:
        return 0.0
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if not na or not nb:
        return 0.0
    return sum(x * y for x, y in zip(a, b)) / (na * nb)


def search(
    index: dict,
    query: str,
    top_k: int,
    fetch_k: int = 0,
    min_score: float = 0.0,
    rerank: bool = False,
) -> list:
    """Двухэтапный поиск (День 23):
      1) retrieve — косинусное сходство запроса с чанками, берём fetch_k кандидатов;
      2) filter/rerank — отсекаем по min_score и (опц.) переупорядочиваем эвристикой
         (эмбеддинг + доля лексического пересечения с запросом), затем top_k.
    fetch_k=0 → без ограничения кандидатов; min_score=0 → без порога; rerank=False → без реранка."""
    qv = embed_query(query, index)
    scored = []
    for i, vec in enumerate(index["vectors"]):
        if index["embedding"] == "ollama":
            score = _cosine(qv, vec)
        else:
            score = sum(qv[w] * vec.get(w, 0.0) for w in qv) if isinstance(qv, dict) else 0.0
        if score > 0:
            scored.append((score, i))
    scored.sort(key=lambda x: x[0], reverse=True)

    # 1) retrieve: топ-K кандидатов ДО фильтрации.
    if fetch_k and fetch_k > 0:
        scored = scored[:fetch_k]

    # 2) filter: порог отсечения по similarity.
    if min_score > 0:
        scored = [(s, i) for s, i in scored if s >= min_score]

    # 2b) rerank: эвристика — комбинируем эмбеддинг-скор с лексическим пересечением.
    if rerank and scored:
        q_terms = {t for t in tokenize(query) if t not in STOPWORDS}
        ranked = []
        for score, i in scored:
            c_terms = {t for t in tokenize(index["chunks"][i]["text"]) if t not in STOPWORDS}
            overlap = len(q_terms & c_terms) / len(q_terms) if q_terms else 0.0
            combined = 0.7 * score + 0.3 * overlap
            ranked.append((combined, score, overlap, i))
        ranked.sort(key=lambda x: x[0], reverse=True)
        scored = [(combined, i) for combined, _, _, i in ranked]
        rerank_info = {i: (sc, ov) for _, sc, ov, i in ranked}
    else:
        rerank_info = {}

    out = []
    for score, i in scored[:top_k]:
        chunk = index["chunks"][i]
        meta = {
            "chunk_id": chunk["chunk_id"],
            "source": chunk["source"],
            "section": chunk["section"],
            "tokens": chunk["tokens"],
            "snippet": chunk["text"][:600],
            "score": round(score, 4),
        }
        if i in rerank_info:
            emb_score, overlap = rerank_info[i]
            meta["emb_score"] = round(emb_score, 4)
            meta["lexical_overlap"] = round(overlap, 4)
        out.append((round(score, 4), meta))
    return out


def stats_report(index: dict) -> dict:
    sizes = [c["tokens"] for c in index["chunks"]]
    if not sizes:
        return {}
    mean = sum(sizes) / len(sizes)
    variance = sum((s - mean) ** 2 for s in sizes) / len(sizes)
    sections = len({c["section"] for c in index["chunks"]})
    return {
        "chunks": len(sizes),
        "sections": sections,
        "mean_tokens": round(mean, 1),
        "min_tokens": min(sizes),
        "max_tokens": max(sizes),
        "stddev_tokens": round(math.sqrt(variance), 1),
        "coverage_chars": sum(c["chars"] for c in index["chunks"]),
    }


PROBE_QUERIES = [
    "как маршрутизировать вызовы между несколькими MCP серверами",
    "что такое косинусное сходство эмбеддингов",
    "чем отличается фиксированный чанкинг от структурного",
    "как сохранить напоминание на сервере",
]


def main() -> None:
    ap = argparse.ArgumentParser(description="Локальная индексация документов (День 21 / RAG)")
    ap.add_argument("--search", help="поисковый запрос по уже построенному индексу")
    ap.add_argument("--source", default="all", help="подпапка tools/<source> (по умолчанию all — весь проект)")
    ap.add_argument("--strategy", choices=["fixed", "structure"], default=None,
                    help="стратегия чанкинга (по умолчанию обе)")
    ap.add_argument("--embedding", choices=["ollama", "tfidf"], default="ollama",
                    help="эмбеддинги (по умолчанию ollama nomic-embed-text)")
    ap.add_argument("--chunk-size", type=int, default=150, help="размер чанка в токенах")
    ap.add_argument("--overlap", type=int, default=30, help="перекрытие чанков")
    ap.add_argument("--top-k", type=int, default=3)
    args = ap.parse_args()

    params = {"size": max(1, args.chunk_size), "overlap": max(0, args.overlap), "max_section": 400}
    strategies = [args.strategy] if args.strategy else ["fixed", "structure"]

    if args.search:
        for s in strategies:
            path = index_path(args.source, s, args.embedding)
            if not os.path.exists(path):
                _out(f"Индекс не найден: {path} — сначала соберите его (без --search).")
                continue
            idx = load_index(args.source, s, args.embedding)
            _out(f"\n=== {s} ({idx['embedding_model']}, dim {idx['embedding_dim']}) ===")
            for score, meta in search(idx, args.search, args.top_k):
                _out(f"  {score:.4f}  [{meta['chunk_id']}] {meta['source']} :: {meta['section']}")
        return

    # Сборка.
    reports = {}
    for s in strategies:
        _out(f"Сборка '{s}' (source={args.source}, embedding={args.embedding})…")
        idx = build_index(s, params, args.source, args.embedding)
        path = save_index(idx)
        reports[s] = stats_report(idx)
        _out(f"Индекс '{s}': {path} | {idx['corpus']['documents']} док, "
             f"{idx['corpus']['chars']:,} симв. | модель {idx['embedding_model']}")

    if len(reports) == 2:
        _out("\n=== Сравнение стратегий чанкинга ===")
        _out(f"{'метрика':<18} {'fixed':>12} {'structure':>12}")
        metrics = [
            ("chunks", "чанков"),
            ("sections", "разделов"),
            ("mean_tokens", "средний размер"),
            ("min_tokens", "мин. размер"),
            ("max_tokens", "макс. размер"),
            ("stddev_tokens", "разброс (std)"),
        ]
        for key, label in metrics:
            f = reports["fixed"].get(key, "—")
            s = reports["structure"].get(key, "—")
            _out(f"{label:<18} {str(f):>12} {str(s):>12}")

    # Зондирующий поиск по построенным индексам.
    _out("\n=== Качество поиска: топ-1 по зондирующим запросам ===")
    for q in PROBE_QUERIES:
        _out(f"\n«{q}»")
        for s in strategies:
            idx = load_index(args.source, s, args.embedding)
            top = search(idx, q, 1)
            if top:
                score, meta = top[0]
                _out(f"  [{s}] {score:.4f}  {meta['source']} :: {meta['section']}")
            else:
                _out(f"  [{s}] — ничего не найдено")

    _out("\nГотово. Поиск: python tools/document_indexer.py --search \"запрос\" "
         "[--source папка] [--strategy fixed|structure] [--embedding ollama|tfidf]")


if __name__ == "__main__":
    sys.exit(main())