#!/usr/bin/env python3
"""生成 M85-B2-4 压测用的 1000 条消息会话（v4 JSONL + 每行一个 zstd frame）。

用法：
    python tools/gen-m85b2-1000-session.py docs/m85b2-1000-session.zstd

生成内容：500 轮 × (user + assistant) = 1000 条可见消息；每 10 轮加一次 bash
tool/call + tool/result（共 50 个工具卡）。事件总数 3103。
依赖 zstandard（开发机 Python 已装；不依赖 app 代码）。
"""

import json
import sys
import uuid

import zstandard

NOW = 1794583000000
SESSION_ID = "9c9fa399-8630-4752-8ce4-36686e35e58f"
CWD = "/data/user/0/xin.dshaa.nativeapp/files/workspace"


def main() -> int:
    out_path = sys.argv[1] if len(sys.argv) > 1 else "docs/m85b2-1000-session.zstd"
    header = {
        "type": "session", "version": 4, "id": SESSION_ID, "createdAt": NOW,
        "cwd": CWD, "isSeeded": False, "delegationDepth": 0, "agentPreset": "standard",
    }
    events = []
    seq = 0

    def add(kind, data, surface=True):
        nonlocal seq
        event = {"type": kind, "seq": seq, "time": NOW + seq * 3, "data": data}
        if surface:
            event["surfaceOp"] = "append"
        events.append(event)
        seq += 1
        return event

    add("request/header", {"header": {"config": {
        "provider": "deepseek", "model": "deepseek-flash",
        "reasoningEffort": "low", "maxTokens": 8192,
    }, "reason": "initial"}}, surface=False)

    first_user_seq = None
    for turn in range(1, 501):
        add("turn/start", {"turn": turn}, surface=False)
        add("step/start", {"turn": turn, "step": 1}, surface=False)
        if first_user_seq is None:
            first_user_seq = seq
        add("user/message", {
            "content": [{"type": "text", "text": f"压测消息 {turn * 2 - 1}：第 {turn} 轮用户输入"}],
            "source": {"kind": "user"}, "role": "user", "id": str(uuid.uuid4()),
        })
        text = (
            f"压测回复 {turn * 2}。\n\n"
            f"- 第 {turn} 轮，包含 **Markdown** 列表与多段正文。\n"
            "- 这一条用来检验 1000 条消息的虚拟列表、滚动与 Markdown 测量。\n"
            + ("- 长文本片段：" + ("abcdefghij" * 30) + "\n") * 3
        )
        add("assistant/message", {"turn": turn, "step": 1, "message": {
            "role": "assistant",
            "content": [
                {"type": "reasoning", "text": f"第 {turn} 轮思考：检查 1000 条消息压测。"},
                {"type": "text", "text": text},
            ],
            "source": {"kind": "model", "provider": "deepseek", "model": "deepseek-flash"},
        }})
        if turn % 10 == 0:
            call_id = f"call_m85b2_{turn:04d}"
            add("tool/call", {"turn": turn, "step": 1, "callId": call_id, "name": "bash",
                              "arguments": json.dumps({"command": f"echo M85B2_STRESS_{turn}"})})
            add("tool/result", {"turn": turn, "step": 1, "message": {
                "role": "tool", "source": {"kind": "tool", "callId": call_id},
                "toolCallId": call_id,
                "content": [{"type": "text", "text": f"M85B2_STRESS_{turn}\n" + ("x" * 200)}],
                "isError": False, "id": str(uuid.uuid4()),
            }})
        add("step/end", {"turn": turn, "step": 1}, surface=False)
        add("turn/end", {"turn": turn, "reason": {"kind": "completed"}}, surface=False)

    add("session/title", {"title": "M85B2 1000 条压测", "messageSeqs": [first_user_seq],
                          "source": {"kind": "manual"}}, surface=False)

    lines = [json.dumps(header, ensure_ascii=False)]
    lines += [json.dumps(event, ensure_ascii=False) for event in events]
    compressor = zstandard.ZstdCompressor(level=10)
    payload = b"".join(compressor.compress(line.encode("utf-8") + b"\n") for line in lines)
    with open(out_path, "wb") as handle:
        handle.write(payload)
    print(f"session={SESSION_ID} lines={len(lines)} bytes={len(payload)} -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
