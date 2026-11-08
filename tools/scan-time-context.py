"""Scan desktop DSH sessions for time-context injections.

Usage: python scan-time-context.py <sessions-root> [max-files]
Prints one line per time-context user message: seq, time, turn/step from the text,
and the preceding model-visible event time (to check Elapsed semantics).
"""
import io
import json
import os
import sys

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


def main() -> int:
    root = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".zstd"):
                files.append(os.path.join(dirpath, name))
    files.sort(key=os.path.getmtime, reverse=True)
    shown = 0
    for path in files[:limit]:
        try:
            evs = list(events_of(path))
        except Exception as exc:  # noqa: BLE001
            print(f"!! {path}: {exc}")
            continue
        for i, ev in enumerate(evs):
            if ev.get("type") != "user/message":
                continue
            data = ev.get("data") or {}
            src = (data.get("source") or {}).get("kind")
            if src != "time-context":
                continue
            text = ""
            for block in data.get("content") or []:
                if isinstance(block, dict) and block.get("type") == "text":
                    text += block.get("text") or ""
            # previous model-visible event before this seq
            prev = None
            for back in evs[:i][::-1]:
                if back.get("type") in ("user/message", "assistant/message", "tool/result"):
                    prev = back
                    break
            prev_src = ((prev.get("data") or {}).get("source") or {}).get("kind") if prev else None
            prev_text = ""
            for block in (prev.get("data") or {}).get("content") or [] if prev else []:
                if isinstance(block, dict) and block.get("type") == "text":
                    prev_text += block.get("text") or ""
            print("=" * 100)
            print(f"file={os.path.basename(os.path.dirname(path))} seq={ev.get('seq')} t={ev.get('time')}")
            print(f"prev type={prev.get('type') if prev else None} src={prev_src} t={prev.get('time') if prev else None} "
                  f"delta={(ev.get('time') or 0) - (prev.get('time') or 0) if prev else None}")
            print(text)
            print(f"prev text: {prev_text[:160]!r}")
            shown += 1
            if shown >= 8:
                return 0
    print(f"found={shown} scanned={len(files[:limit])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
