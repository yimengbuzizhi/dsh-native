"""Scan desktop DSH sessions for compaction events (ground truth for the schema).

Usage:
  python scan-compaction.py <sessions-root> [max-files]

Prints:
  1. a histogram of every top-level event `type`;
  2. every compaction-ish event (type or source.kind containing "compact"), full JSON,
     truncated to the first 4 samples per type;
  3. for each session that has compaction, the seq/type of all events around it.
"""
import io
import json
import os
import sys
from collections import Counter

import zstandard


def read_all(path: str) -> bytes:
    data = open(path, "rb").read()
    dctx = zstandard.ZstdDecompressor()
    with dctx.stream_reader(io.BytesIO(data)) as reader:
        return reader.read()


def events_of(path: str):
    raw = read_all(path)
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            yield json.loads(line)
        except json.JSONDecodeError:
            continue


def source_kind(ev) -> str:
    data = ev.get("data")
    if not isinstance(data, dict):
        return ""
    src = data.get("source")
    if isinstance(src, dict):
        return src.get("kind") or ""
    return ""


def main() -> int:
    root = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".zstd"):
                files.append(os.path.join(dirpath, name))
    files.sort(key=os.path.getmtime, reverse=True)

    types = Counter()
    samples = {}
    compacted = []

    for path in files[:limit]:
        try:
            evs = list(events_of(path))
        except Exception as exc:  # noqa: BLE001
            print(f"!! {path}: {exc}")
            continue
        hit = False
        for ev in evs:
            t = ev.get("type") or "?"
            types[t] += 1
            src = source_kind(ev)
            if "compact" in t or "compact" in src:
                hit = True
                key = f"{t}|{src}"
                bucket = samples.setdefault(key, [])
                if len(bucket) < 4:
                    bucket.append((path, ev))
        if hit:
            compacted.append((path, evs))

    print("== event type histogram ==")
    for t, n in types.most_common():
        print(f"{n:6d}  {t}")

    print()
    print("== compaction samples ==")
    for key, bucket in samples.items():
        print("=" * 100)
        print(f"key={key}  ({len(bucket)} shown)")
        for path, ev in bucket:
            print(f"-- {os.path.basename(os.path.dirname(path))} seq={ev.get('seq')} t={ev.get('time')}")
            print(json.dumps(ev, ensure_ascii=False)[:4000])

    print()
    print(f"== sessions with compaction: {len(compacted)} / {min(len(files), limit)} scanned ==")
    for path, evs in compacted[:6]:
        print("-" * 100)
        print(os.path.basename(os.path.dirname(path)))
        for ev in evs:
            t = ev.get("type")
            src = ((ev.get("data") or {}).get("source") or {}).get("kind")
            print(f"  seq={ev.get('seq')} t={t} src={src}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
