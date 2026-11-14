#!/usr/bin/env python3
"""M86-H isolated desktop-wire fixture. Standard library only; no DSH/credentials/files touched.

Start ONE managed process, then adb reverse tcp:18786 tcp:18786:
  python tools/m86h-remote-fixture.py --host 127.0.0.1 --port 18786
  A: http://127.0.0.1:18786/a   token: m86h-a
  B: http://127.0.0.1:18786/b   token: m86h-b
Both expose sessionId=m86h-shared. These tokens are public fixture constants.

GET /api/debug/state returns counts, requests, answers and event records for both backends.
POST /a/api/debug/control (Authorization: Bearer m86h-a), likewise /b:
  {"reset":true,"listDelayMs":0,"createDelayMs":0,"snapshotDelayMs":0,
   "promptAckDelayMs":0,"eventDelayMs":50,"toolDelayMs":300,"askDelayMs":100,
   "scenario":"simple|ask|hold|tool-error","promptMode":"normal|reject|accept-drop"}
POST /a/api/debug/drop-ws drops only A's follow sockets; /a/api/debug/finish ends a hold.
Controls affect subsequent operations; each prompt captures its controls on admission.
Use --self-test for deterministic in-memory protocol checks (does NOT start a server).

Wire sources verified against installed @deepseek-ai/dsh 0.2.2-rc.3:
  dsh-typert-protocol: client-request/server-response unary envelope
  dsh-api-gateway/src/stream-protocol.ts: remote.mux open/item/end/cancel
  dsh-api-session-controller/src/types.ts: session/list, create, prompt, follow, cancel
  dsh-user-questions/src/{index,projection}.ts + dsh-tool-ask-user/src/timed.ts:
    multi_select tool args, pending:true tool result, continued userQuestions/answer,
    user/message source.kind=user-question-reply. No synthetic legacy answer endpoint.
This is an in-memory protocol fixture, not a real desktop/LLM or $events answerer.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import queue
import socket
import struct
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

SHARED_SESSION = "m86h-shared"
DEFAULTS = dict(listDelayMs=0, createDelayMs=0, snapshotDelayMs=0, promptAckDelayMs=0,
                eventDelayMs=50, toolDelayMs=300, askDelayMs=100,
                scenario="simple", promptMode="normal")
SURFACE = {"user/message", "assistant/message", "tool/result"}


def now():
    return int(time.time() * 1000)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def wait_ms(value):
    time.sleep(value / 1000)


def message(identity, role, text, **extra):
    return dict(id=identity, role=role, content=[dict(type="text", text=text)], **extra)


def response(rpc_id, value=None, error=None):
    result = dict(ok=True, value=value) if error is None else dict(ok=False, error=error)
    return dict(type="server-response", rpcId=rpc_id, result=result)


class FixtureError(Exception):
    pass


class Session:
    def __init__(self, backend, identity, title, seeded=False):
        self.backend = backend
        self.id = identity
        self.title = title
        self.created_at = now()
        self.updated_at = self.created_at
        self.records = []
        self.running = False
        self.turn = 0
        self.receipts = set()
        self.pending_questions = {}
        self.followers = set()
        self.inbox = queue.Queue()
        self.release = threading.Event()
        if seeded:
            self.event("user/message", message("seed-user", "user", f"{backend.name} 独立历史", source=dict(kind="user")))
            self.event("assistant/message", dict(message=message("seed-assistant", "assistant", f"来自服务端 {backend.name}"), stream=[]))

    def event(self, kind, data):
        with self.backend.lock:
            event = dict(type=kind, seq=len(self.records), time=now(), data=data)
            if kind in SURFACE:
                event["surfaceOp"] = "append"
            self.records.append(dict(type="event", event=event))
            self.updated_at = event["time"]
            for follower in tuple(self.followers):
                follower.item(dict(type="event", event=event))
            return event

    def stream(self, frame):
        with self.backend.lock:
            for follower in tuple(self.followers):
                follower.item(dict(type="assistant-stream", frame=frame))

    def snapshot(self):
        with self.backend.lock:
            cursor = len(self.records) - 1
            values = dict(title=self.title,
                          modelSelection=dict(lastUsed=dict(provider="fixture", model=f"fixture-{self.backend.name}")),
                          userQuestions=dict(active=list(self.pending_questions.values()), settled=[]))
            return dict(type="snapshot", header=dict(version=4, id=self.id, createdAt=self.created_at,
                        cwd=f"C:/m86h-fixture/{self.backend.name}", isSeeded=False), cursor=cursor,
                        records=list(self.records), hasMore=False,
                        projections=dict(asOfSeq=cursor, values=values), assistantStream=dict(revision=0))

    def summary(self):
        return dict(sessionId=self.id, updatedAt=self.updated_at, running=self.running,
                    blank=not self.records, agentAvailable=True, cwd=f"C:/m86h-fixture/{self.backend.name}",
                    projections=dict(kind="sequenced", asOfSeq=len(self.records) - 1, values=dict(
                        title=self.title, modelSelection=dict(lastUsed=dict(provider="fixture", model=f"fixture-{self.backend.name}")),
                        sessionListMetadata=dict(blank=not self.records, lastPromptAt=self.updated_at))))

    def enqueue(self, request, config):
        with self.backend.lock:
            if request["requestId"] in self.receipts:
                self.backend.counts["duplicateRequestIds"] += 1
                return
            self.receipts.add(request["requestId"])
            self.inbox.put((request, config))
            if not self.running:
                self.running = True
                threading.Thread(target=self.work, daemon=True).start()

    def work(self):
        while True:
            with self.backend.lock:
                try:
                    request, config = self.inbox.get_nowait()
                except queue.Empty:
                    self.running = False
                    return
                self.turn += 1
                turn = self.turn
                self.release.clear()
            wait_ms(config["eventDelayMs"])
            self.event("turn/start", dict(turn=turn))
            self.event("user/message", dict(id=f"user-{turn}", role="user", content=request["content"],
                       source=dict(kind="user", rpcId=request["requestId"], clientTimeZone=request.get("clientTimeZone", "UTC"))))
            call_id = f"m86h-tool-{turn}"
            self.event("tool/call", dict(callId=call_id, name="pwsh", arguments=json.dumps(dict(command=f"echo fixture-{self.backend.name}"))))
            self.release.wait(config["toolDelayMs"] / 1000)
            is_error = config["scenario"] == "tool-error"
            self.event("tool/result", dict(message=dict(id=f"tool-result-{turn}", role="tool", toolCallId=call_id,
                       content=[dict(type="text", text=f"{self.backend.name} tool {'failed' if is_error else 'done'}")], isError=is_error)))
            if config["scenario"] == "hold":
                self.release.wait(120)
            if config["scenario"] == "ask":
                self.ask(turn, config)
            self.assistant(turn, f"{self.backend.name} 已处理第 {turn} 条输入")
            self.event("turn/end", dict(turn=turn, reason=dict(kind="completed")))

    def ask(self, turn, config):
        call_id = f"m86h-ask-{turn}"
        questions = [dict(id="choice", header=f"{self.backend.name} 单选", question="选择一个选项", options=[dict(label="A"), dict(label="B")]),
                     dict(id="extras", header="多选", question="可多选或填写其他", multi_select=True,
                          options=[dict(label="X", description="第一个"), dict(label="Y", description="第二个")])]
        self.event("request/header", dict(reason="initial", header=dict(tools=[dict(name="ask_user_question",
                   description="fixture timed questions", parameters=dict(type="object", properties=dict(timeout=dict(type="integer"))))])))
        self.event("tool/call", dict(callId=call_id, name="ask_user_question", arguments=json.dumps(dict(questions=questions, timeout=1), ensure_ascii=False)))
        wait_ms(config["askDelayMs"])
        with self.backend.lock:
            projected = [dict(q, multiSelect=q.get("multi_select", False)) for q in questions]
            for q in projected:
                q.pop("multi_select", None)
            self.pending_questions[call_id] = dict(callId=call_id, questions=projected, state="continued")
        self.event("tool/result", dict(message=dict(id=f"ask-pending-{turn}", role="tool", toolCallId=call_id,
                   content=[dict(type="text", text=json.dumps(dict(pending=True, callId=call_id,
                                message="The user can still answer; do not treat pending as permission.")))], isError=False)))

    def assistant(self, turn, text):
        attempt = f"attempt-{turn}"
        revision = turn * 10
        self.stream(dict(type="start", attemptId=attempt, revision=revision, startedAfterSeq=len(self.records)-1, turn=turn, step=1))
        self.stream(dict(type="chunk", attemptId=attempt, revision=revision+1, index=0, time=now(), chunk=dict(type="reasoning-delta", text=f"{self.backend.name} fixture reasoning")))
        self.stream(dict(type="chunk", attemptId=attempt, revision=revision+2, index=1, time=now(), chunk=dict(type="text-delta", text=text)))
        event = self.event("assistant/message", dict(message=message(f"assistant-{turn}", "assistant", text), stream=[]))
        self.stream(dict(type="end", attemptId=attempt, revision=revision+3, index=2,
                         outcome=dict(kind="committed", eventType="assistant/message", seq=event["seq"])))


class Backend:
    def __init__(self, name):
        self.name = name
        self.token = f"m86h-{name.lower()}"
        self.lock = threading.RLock()
        self.config = DEFAULTS.copy()
        self.counts = Counter()
        self.requests = []
        self.answers = []
        self.created = 0
        self.sessions = {SHARED_SESSION: Session(self, SHARED_SESSION, f"{name} shared session", seeded=True)}

    def control(self, value):
        with self.lock:
            if value.get("reset"):
                if any(s.running for s in self.sessions.values()):
                    raise FixtureError("finish/cancel running turns before reset")
                for session in self.sessions.values():
                    for follower in tuple(session.followers):
                        follower.close()
                self.config = DEFAULTS.copy()
                self.counts.clear(); self.requests.clear(); self.answers.clear(); self.created = 0
                self.sessions = {SHARED_SESSION: Session(self, SHARED_SESSION, f"{self.name} shared session", seeded=True)}
            for key, setting in value.items():
                if key == "reset":
                    continue
                if key not in DEFAULTS:
                    raise FixtureError(f"unknown control: {key}")
                if key.endswith("Ms") and (not isinstance(setting, int) or isinstance(setting, bool) or not 0 <= setting <= 60000):
                    raise FixtureError(f"{key} must be integral milliseconds in 0..60000")
                if key == "scenario" and setting not in {"simple", "ask", "hold", "tool-error"}:
                    raise FixtureError("unknown scenario")
                if key == "promptMode" and setting not in {"normal", "reject", "accept-drop"}:
                    raise FixtureError("unknown promptMode")
                self.config[key] = setting
            return self.config.copy()

    def state(self):
        with self.lock:
            return dict(name=self.name, config=self.config.copy(), counts=dict(self.counts),
                        promptCalls=self.counts["session/prompt"], acceptedPrompts=self.counts["acceptedPrompts"],
                        duplicateRequestIds=self.counts["duplicateRequestIds"], requests=list(self.requests),
                        lastPrompt=self.requests[-1] if self.requests else None,
                        answers=list(self.answers), lastAnswers=self.answers[-1] if self.answers else None,
                        sessions={identity: dict(running=s.running, records=list(s.records),
                                  followers=len(s.followers), pendingQuestions=list(s.pending_questions.values()))
                                  for identity, s in self.sessions.items()})

    def rpc(self, endpoint, args):
        with self.lock:
            self.counts[endpoint] += 1
            config = self.config.copy()
        if endpoint == "session/list":
            if args != {"_request": {}}:
                raise FixtureError("session/list expects {_request:{}}")
            wait_ms(config["listDelayMs"])
            with self.lock:
                return dict(items=[s.summary() for s in self.sessions.values()]), False
        if endpoint == "session/create":
            if args != {"request": {}}:
                raise FixtureError("session/create expects {request:{}}")
            wait_ms(config["createDelayMs"])
            with self.lock:
                self.created += 1
                identity = f"m86h-new-{self.created}"  # deliberately repeats on A and B
                self.sessions[identity] = Session(self, identity, f"{self.name} new {self.created}")
            return dict(sessionId=identity), False
        if endpoint == "userQuestions/answer":
            if set(args) != {"agentId", "callId", "answer"}:
                raise FixtureError("answer expects agentId/callId/answer")
            session = self.sessions.get(args["agentId"])
            if session is None:
                raise FixtureError("unknown agentId")
            with self.lock:
                asked = session.pending_questions.get(args["callId"])
                if asked is None:
                    return False, False
                answers = args["answer"]["answers"]
                ids = [a["id"] for a in answers]
                if len(ids) != len(set(ids)) or set(ids) != {q["id"] for q in asked["questions"]}:
                    raise FixtureError("answer must name each question exactly once")
                if any(not isinstance(a.get("selected"), list) for a in answers):
                    raise FixtureError("selected must be an array")
                self.answers.append(args)
                session.pending_questions.pop(args["callId"])
            session.event("user/message", message(f"answer-{args['callId']}", "user", json.dumps(dict(
                kind="answer_to_pending_question", tool="ask_user_question", callId=args["callId"],
                questions=asked["questions"], answers=answers), ensure_ascii=False),
                source=dict(kind="user-question-reply", callId=args["callId"], outcome="answered")))
            return True, False
        request = args.get("request", {})
        session = self.sessions.get(request.get("sessionId"))
        if session is None:
            raise FixtureError("unknown sessionId")
        if endpoint == "session/cancel":
            session.release.set()
            return dict(accepted=True), False
        if endpoint == "session/prompt":
            if set(request) - {"sessionId", "requestId", "mode", "clientTimeZone", "content"}:
                raise FixtureError("unexpected prompt field")
            if request.get("mode") != "queue" or not request.get("requestId") or not request.get("content"):
                raise FixtureError("fixture expects existing queue/text prompt contract")
            with self.lock:
                self.requests.append(request)
            if config["promptMode"] == "reject":
                raise FixtureError("fixture rejected prompt before admission")
            with self.lock:
                self.counts["acceptedPrompts"] += 1
                session.enqueue(request, config)
            wait_ms(config["promptAckDelayMs"])
            return dict(accepted=True), config["promptMode"] == "accept-drop"
        raise FixtureError(f"unsupported endpoint: {endpoint}")


class Follower:
    def __init__(self, handler, session, stream_id):
        self.handler = handler
        self.session = session
        self.stream_id = stream_id
        self.lock = threading.Lock()
        self.closed = False

    def item(self, value):
        self.send(encode(dict(type="item", streamId=self.stream_id, value=value)))

    def send(self, body, opcode=1):
        with self.lock:
            if self.closed:
                return
            length = len(body)
            header = bytes([0x80 | opcode])
            header += bytes([length]) if length < 126 else b"\x7e" + struct.pack("!H", length) if length < 65536 else b"\x7f" + struct.pack("!Q", length)
            try:
                self.handler.connection.sendall(header + body)
            except OSError:
                self.closed = True

    def close(self):
        self.closed = True
        try:
            self.handler.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def read_exact(stream, length):
    value = stream.read(length)
    if len(value) != length:
        raise EOFError()
    return value


def read_ws(stream):
    first, second = read_exact(stream, 2)
    if not first & 0x80 or not second & 0x80:
        raise FixtureError("fixture requires final masked client frames")
    length = second & 127
    if length == 126:
        length = struct.unpack("!H", read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(stream, 8))[0]
    if length > 1048576:
        raise FixtureError("frame too large")
    mask = read_exact(stream, 4)
    body = bytes(b ^ mask[i % 4] for i, b in enumerate(read_exact(stream, length)))
    return first & 15, body


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # Prompts, headers and tokens stay out of console logs.

    def send_json(self, value, status=200):
        body = encode(value)
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Delayed replies to a retired Android generation are intentional.

    def route(self):
        path = urlsplit(self.path).path
        parts = path.split("/", 2)
        backend = self.server.backends.get(parts[1]) if len(parts) == 3 else None
        return backend, "/" + parts[2] if backend else path

    def authorize(self, backend):
        if self.headers.get("Authorization") == f"Bearer {backend.token}":
            return True
        self.send_json(dict(error="fixture token rejected"), 401)
        return False

    def do_GET(self):
        backend, path = self.route()
        if backend is None and path == "/api/debug/state":
            self.send_json({key: value.state() for key, value in self.server.backends.items()}); return
        if backend is None:
            self.send_json(dict(error="use /a or /b base"), 404); return
        if path == "/api/health":
            self.send_json(dict(ok=True, dsh="reachable", api="ok")); return
        if not self.authorize(backend):
            return
        if path == "/api/dsh/api/remote.mux" and self.headers.get("Upgrade", "").lower() == "websocket":
            self.websocket(backend); return
        self.send_json(dict(error="not found"), 404)

    def do_POST(self):
        backend, path = self.route()
        if backend is None:
            self.send_json(dict(error="use /a or /b base"), 404); return
        if not self.authorize(backend):
            self.close_connection = True
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 1048576:
                raise FixtureError("request too large")
            data = json.loads(read_exact(self.rfile, length))
            if path == "/api/debug/control":
                self.send_json(backend.control(data)); return
            if path in {"/api/debug/drop-ws", "/api/debug/finish"}:
                with backend.lock:
                    for session in backend.sessions.values():
                        if path.endswith("drop-ws"):
                            for follower in tuple(session.followers):
                                follower.close()
                        else:
                            session.release.set()
                self.send_json(dict(ok=True)); return
            prefix = "/api/dsh/api/"
            if not path.startswith(prefix):
                self.send_json(dict(error="not found"), 404); return
            endpoint = path[len(prefix):]
            if data.get("type") != "client-request" or data.get("method") != endpoint or not data.get("rpcId"):
                raise FixtureError("invalid client-request envelope")
            try:
                value, drop = backend.rpc(endpoint, data["payload"]["args"])
                if drop:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                else:
                    self.send_json(response(data["rpcId"], value))
            except FixtureError as error:
                self.send_json(response(data["rpcId"], error=dict(code="fixture/rejected", message=str(error), details={})))
        except (ValueError, KeyError, TypeError, EOFError, FixtureError) as error:
            self.send_json(dict(error=str(error)), 400)
        except (BrokenPipeError, ConnectionResetError, OSError):
            self.close_connection = True

    def websocket(self, backend):
        key = self.headers.get("Sec-WebSocket-Key")
        if key is None:
            self.send_json(dict(error="missing websocket key"), 400); return
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101); self.send_header("Upgrade", "websocket"); self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept); self.end_headers(); self.wfile.flush()
        follower = None
        self.close_connection = True
        try:
            while True:
                opcode, body = read_ws(self.rfile)
                if opcode == 8:
                    return
                if opcode == 9:
                    if follower:
                        follower.send(body, opcode=10)
                    continue
                if opcode != 1:
                    continue
                frame = json.loads(body)
                if frame["type"] == "cancel":
                    return
                if frame["type"] != "open" or frame.get("endpoint") != "session/follow" or follower is not None:
                    raise FixtureError("expected one session/follow open")
                request = frame["payload"]["args"]["request"]
                if request.get("assistantStream") is not True or request["address"]["kind"] != "session":
                    raise FixtureError("unexpected follow request")
                session = backend.sessions[request["address"]["sessionId"]]
                wait_ms(backend.config["snapshotDelayMs"])
                follower = Follower(self, session, frame["streamId"])
                with backend.lock:
                    follower.item(session.snapshot())
                    session.followers.add(follower)
                    backend.counts["followOpens"] += 1
        except (EOFError, OSError, ValueError, KeyError, TypeError, FixtureError):
            return
        finally:
            if follower:
                with backend.lock:
                    follower.session.followers.discard(follower)
                follower.close()


def self_test():
    a, b = Backend("A"), Backend("B")
    assert a.sessions.keys() == b.sessions.keys() == {SHARED_SESSION}
    assert a.sessions[SHARED_SESSION].snapshot()["records"][0]["event"]["data"]["content"][0]["text"] != b.sessions[SHARED_SESSION].snapshot()["records"][0]["event"]["data"]["content"][0]["text"]
    assert response("rpc", True) == {"type": "server-response", "rpcId": "rpc", "result": {"ok": True, "value": True}}
    rows, drop = a.rpc("session/list", {"_request": {}})
    assert not drop and rows["items"][0]["sessionId"] == SHARED_SESSION
    created, drop = a.rpc("session/create", {"request": {}})
    assert created["sessionId"] == "m86h-new-1" and not drop
    a.control(dict(scenario="ask", eventDelayMs=0, toolDelayMs=0, askDelayMs=0))
    s = a.sessions[SHARED_SESSION]
    s.turn = 1; s.ask(1, a.config)
    pending = s.records[-1]["event"]["data"]["message"]
    assert json.loads(pending["content"][0]["text"])["pending"] is True
    assert s.pending_questions["m86h-ask-1"]["questions"][1]["multiSelect"] is True
    answers = dict(agentId=SHARED_SESSION, callId="m86h-ask-1", answer=dict(answers=[dict(id="choice", selected=[]), dict(id="extras", selected=["X", "Y"], custom="custom")]))
    assert a.rpc("userQuestions/answer", answers) == (True, False)
    assert a.rpc("userQuestions/answer", answers) == (False, False)
    assert s.records[-1]["event"]["data"]["source"]["kind"] == "user-question-reply"
    a.control(dict(promptMode="reject"))
    request = dict(requestId="one", sessionId=SHARED_SESSION, mode="queue", content=[dict(type="text", text="fixture")])
    try:
        a.rpc("session/prompt", dict(request=request))
        raise AssertionError("reject should fail")
    except FixtureError:
        pass
    assert a.state()["promptCalls"] == 1 and a.state()["acceptedPrompts"] == 0
    assert b.state()["promptCalls"] == 0
    print("M86-H fixture self-test: PASS (no socket/server started)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18786)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(); return
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.backends = dict(a=Backend("A"), b=Backend("B"))
    print(f"M86-H fixture listening on http://{args.host}:{args.port} (A=/a, B=/b; public tokens m86h-a/m86h-b)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
