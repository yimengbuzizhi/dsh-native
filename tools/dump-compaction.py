"""Dump compaction evidence (events + exact strings) from desktop DSH sessions.

Usage: python dump-compaction.py <sessions-root> <out.json>

For every session containing a `compaction/start`, writes:
  - the compaction/* events verbatim,
  - the surrounding command/run + command/done for the same sourceCommandId,
  - the compact-checkpoint user/message verbatim,
  - the seq of the first model-visible event after compaction/end.
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
    root, out = sys.argv[1], sys.argv[2]
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".zstd"):
                files.append(os.path.join(dirpath, name))
    files.sort(key=os.path.getmtime, reverse=True)

    result = []
    for path in files:
        try:
            evs = list(events_of(path))
        except Exception:  # noqa: BLE001
            continue
        starts = [e for e in evs if e.get("type") == "compaction/start"]
        if not starts:
            continue
        cids = {(e.get("data") or {}).get("sourceCommandId") for e in starts}
        cmds = []
        for e in evs:
            if e.get("type") in ("command/run", "command/done"):
                d = e.get("data") or {}
                cid = d.get("id") or d.get("commandId")
                if cid in cids:
                    cmds.append(e)
        ends = [i for i, e in enumerate(evs) if e.get("type") == "compaction/end"]
        after = None
        if ends:
            for e in evs[ends[-1] + 1:]:
                if e.get("type") in ("user/message", "assistant/message", "tool/result", "turn/start"):
                    after = {"seq": e.get("seq"), "type": e.get("type")}
                    break
        result.append({
            "file": path,
            "session": os.path.basename(os.path.dirname(path)),
            "commands": cmds,
            "compaction": [e for e in evs if str(e.get("type", "")).startswith("compaction/")],
            "checkpoint_messages": [
                e for e in evs
                if e.get("type") == "user/message"
                and isinstance(e.get("data"), dict)
                and isinstance((e["data"].get("source") or {}), dict)
                and (e["data"].get("source") or {}).get("kind") == "compact-checkpoint"
            ],
            "first_after_end": after,
        })

    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    print(f"sessions={len(result)} -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
