"""Dump a desktop DSH v4 session (zstd-framed JSONL) to plain JSONL.

Usage: python dump-desktop-session.py <session.v4.jsonl.zstd> [out.jsonl] [--time]

--time prints only user/message + turn/step events with the time-context text.
"""
import io
import json
import sys

import zstandard


def read_all(path: str) -> bytes:
    data = open(path, "rb").read()
    dctx = zstandard.ZstdDecompressor()
    with dctx.stream_reader(io.BytesIO(data)) as reader:
        return reader.read()


def main() -> int:
    src = sys.argv[1]
    args = [a for a in sys.argv[2:] if not a.startswith("--")]
    dst = args[0] if args else src.replace(".zstd", ".dump.jsonl")
    raw = read_all(src)
    with open(dst, "wb") as out:
        out.write(raw)
    n = 0
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        n += 1
        t = ev.get("type")
        if t not in ("turn/start", "turn/end", "step/start", "step/end", "user/message"):
            continue
        src_kind = ((ev.get("data") or {}).get("source") or {}).get("kind")
        text = ""
        for block in (ev.get("data") or {}).get("content") or []:
            if isinstance(block, dict) and block.get("type") == "text":
                text += block.get("text") or ""
        print(f"{ev.get('seq')} t={ev.get('time')} {t} src={src_kind} {text[:220]!r}")
    print(f"events={n} -> {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
