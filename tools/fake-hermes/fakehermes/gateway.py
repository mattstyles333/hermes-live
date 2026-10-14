"""The /api/ws JSON-RPC gateway: one Connection per socket, Sessions that outlive sockets.

Events go only to the sockets viewing a session (added by session.create or session.resume,
removed when the socket drops); with no viewer they are dropped and counted, as on the real server.
A reconnected client that does not resume sees nothing of its sessions.
"""

import json
import secrets
import threading
import time

from .agent import Turn
from .wsproto import WebSocketClosed


class RpcError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class Waiter:
    def __init__(self, rid: str, method: str, params: dict, session):
        self.id = rid
        self.method = method
        self.params = params
        self.session = session
        self.done = threading.Event()
        self.result = None


class Connection:
    def __init__(self, hub, ws, cid: str):
        self.hub = hub
        self.ws = ws
        self.id = cid
        self.alive = True
        self.server_requests = False  # set by client.capabilities {server_requests: true}
        self._lock = threading.Lock()
        self._held = []

    # ── sending ────────────────────────────────────────────────────────────────────────────

    def send(self, obj: dict) -> bool:
        text = json.dumps(obj, ensure_ascii=False)
        with self._lock:
            if not self.alive:
                return False
            if not self.hub.unstalled.is_set():
                self._held.append(text)
                return True
            return self.ws.send_text(text)

    def flush_held(self):
        with self._lock:
            held, self._held = self._held, []
            for text in held:
                if self.alive:
                    self.ws.send_text(text)

    def event(self, type_: str, session_id, payload=None, seq=None) -> bool:
        params = {"type": type_, "session_id": session_id}
        if payload is not None:
            params["payload"] = payload
        if seq is not None:
            params["seq"] = seq
        return self.send({"jsonrpc": "2.0", "method": "event", "params": params})

    def reply(self, rid, result):
        self.send({"jsonrpc": "2.0", "id": rid, "result": result})

    def error(self, rid, code: int, message: str):
        self.send({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})

    def drop(self, mode: str):
        with self._lock:
            self.alive = False
        if mode == "close":
            self.ws.close(1001, "fake: going away")
        else:
            self.ws.abort()

    # ── reading ────────────────────────────────────────────────────────────────────────────

    def serve(self):
        hub = self.hub
        if not hub.faults.get("no_ready"):
            self.event("gateway.ready", None, {
                "skin": {}, "change_events": True, "heartbeat": True, "replay_epoch": hub.boot_id,
            })
        try:
            while self.alive:
                text = self.ws.recv()
                hub.unstalled.wait()
                if not self.alive:
                    break
                self._handle(text.strip())
        except WebSocketClosed:
            pass
        finally:
            with self._lock:
                self.alive = False

    def _handle(self, text: str):
        """One JSON-RPC object per message, as the real server reads it (no newline batching)."""
        hub = self.hub
        try:
            frame = json.loads(text)
        except ValueError:
            hub.record({"kind": "parse_error", "conn": self.id, "chars": len(text)})
            self.send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            return
        if not isinstance(frame, dict):
            return
        method = frame.get("method")
        rid = frame.get("id")
        params = frame.get("params") if isinstance(frame.get("params"), dict) else {}
        if method is None and rid is not None:
            hub.record({"kind": "response", "conn": self.id, "id": rid,
                        "result": frame.get("result"), "error": frame.get("error")})
            resolve_request(hub, rid, frame.get("result"), frame.get("error"))
            return
        if method is None:
            return
        if method != "gateway.ping":
            hub.record({"kind": "rpc", "conn": self.id, "method": method, "id": rid, "params": params,
                        "session": session_key(hub, params.get("session_id"))})
        handler = METHODS.get(method)
        if handler is None:
            if rid is not None:
                self.error(rid, -32601, "unknown method: %s — the client and the Hermes backend are out of sync" % method)
            return
        try:
            check_params(method, params)
            result = handler(self, rid, params)
        except RpcError as e:
            if rid is not None:
                self.error(rid, e.code, e.message)
            return
        if result is NO_REPLY or rid is None:
            return
        if isinstance(result, tuple):
            value, after = result
            self.reply(rid, value)
            after()
        else:
            self.reply(rid, result)


NO_REPLY = object()


def session_key(hub, sid):
    """The stored id for a runtime or stored id (for the received log)."""
    if not sid:
        return None
    with hub.lock:
        session = hub.by_runtime.get(sid) or hub.sessions.get(sid)
        return session.stored if session else sid


def resolve_request(hub, rid, result, error):
    """A client response to a server request; late or unknown ids are dropped, an error is no answer."""
    with hub.lock:
        for session in hub.sessions.values():
            waiter = session.open_requests.pop(rid, None)
            if waiter is not None:
                waiter.result = result if error is None else None
                waiter.done.set()
                return


class Session:
    def __init__(self, hub, stored: str, title=None):
        self.hub = hub
        self.stored = stored
        self.runtime = None  # None: not live in this process (cold); resume mints one
        self.title = title
        self.created_at = time.time()
        self.persisted = False
        self.messages = []
        self.queued = []  # texts waiting for the next turn (merged into one when it runs)
        self.turn = None
        self.viewers = set()
        self.open_requests = {}
        self.dropped_events = 0
        self.seq = 0

    @property
    def running(self) -> bool:
        return self.turn is not None

    @staticmethod
    def from_record(hub, record: dict) -> "Session":
        s = Session(hub, record["stored"], record.get("title"))
        s.created_at = record.get("created_at", time.time())
        s.persisted = True
        s.messages = record.get("messages", [])
        return s

    def save(self):
        if self.persisted:
            self.hub.store.save_session({
                "stored": self.stored, "title": self.title, "created_at": self.created_at,
                "messages": self.messages,
            })

    def add_user_row(self, text: str):
        """Store a user row now (submit and queue both persist at accept time on the real server)."""
        self.persisted = True
        self.messages.append({"role": "user", "text": text, "timestamp": round(time.time(), 3)})
        self.save()

    def tool_row(self, name: str, context):
        """Transcript row for a finished tool call (`{role: "tool", name, context}`, no text)."""
        with self.hub.lock:
            if self.turn is not None and not self.turn.cancelled:
                self.messages.append({"role": "tool", "name": name, "context": context or "",
                                      "timestamp": round(time.time(), 3)})
                self.save()

    def live_viewers(self):
        return [c for c in list(self.viewers) if c.alive]

    # ── what the turn runner calls (Turn.host) ─────────────────────────────────────────────

    def emit(self, type_: str, payload):
        with self.hub.lock:
            self.seq += 1
            seq = self.seq
        sent = False
        for conn in self.live_viewers():
            sent = conn.event(type_, self.runtime, payload, seq) or sent
        if not sent:
            self.dropped_events += 1

    def server_request(self, method: str, params: dict) -> Waiter:
        rid = "srq-" + secrets.token_hex(6)
        full = {"session_id": self.runtime, **params}
        waiter = Waiter(rid, method, full, self)
        viewers = self.live_viewers()
        capable = [c for c in viewers if c.server_requests]
        if viewers and not capable:
            # No viewer asked for server requests: approval is withdrawn, clarify is undelivered.
            self.hub.record({"kind": "server_request", "session": self.stored, "id": rid, "method": method,
                             "delivered": False})
            waiter.done.set()
            return waiter
        with self.hub.lock:
            self.open_requests[rid] = waiter
        self.hub.record({"kind": "server_request", "session": self.stored, "id": rid, "method": method,
                         "delivered": bool(capable)})
        for conn in capable:
            conn.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": full})
        return waiter

    def cancel_request(self, waiter: Waiter, reason: str):
        """Withdraw an open request (once): `request.cancel {id, method, reason}`."""
        with self.hub.lock:
            was_open = self.open_requests.pop(waiter.id, None) is not None
        waiter.done.set()
        if was_open:
            self.emit("request.cancel", {"id": waiter.id, "method": waiter.method, "reason": reason})

    def finish(self, turn: Turn, text: str, status: str, error):
        with self.hub.lock:
            if turn is not self.turn or turn.cancelled:
                return
            if text:
                self.messages.append({"role": "assistant", "text": text, "timestamp": round(time.time(), 3)})
            self.turn = None
            self.save()
            payload = {"text": text, "usage": {"input": 0, "output": len(text.split())}, "status": status}
            if error:
                payload.update({"error": error, "recoverable": True})
            self.hub.record({"kind": "turn_end", "session": self.stored, "status": status})
            self.emit("message.complete", payload)
            if self.queued:
                merged, self.queued = "\n\n".join(self.queued), []
                self.start_turn(merged, store=False)

    # ── turns ──────────────────────────────────────────────────────────────────────────────

    def start_turn(self, text: str, launch: bool = True, store: bool = True):
        """Store the user row (unless the queue already did), then run the turn.

        With launch=False the caller starts the thread after it has sent the submit reply, so the
        reply goes out before `message.start`.
        """
        with self.hub.lock:
            if store:
                self.add_user_row(text)
            self.turn = Turn(self, text, self.hub.pick_rule(text), self.hub.scenario.stream)
            if launch:
                self.turn.launched = True
                self.turn.start()

    def interrupt(self) -> bool:
        """session.interrupt: clear the queue, withdraw open requests, stop the turn."""
        with self.hub.lock:
            self.queued.clear()
            waiters = list(self.open_requests.values())
            turn = self.turn
        if turn is not None:
            turn.interrupt()
        for waiter in waiters:
            self.cancel_request(waiter, "interrupted")
        return turn is not None

    def shutdown(self):
        """Server restart: the turn dies silently, open requests go away."""
        with self.hub.lock:
            if self.turn is not None:
                self.turn.cancel()
                self.turn = None
            for waiter in self.open_requests.values():
                waiter.done.set()
            self.open_requests.clear()
            self.queued.clear()
            self.viewers.clear()

    def snapshot(self) -> dict:
        return {
            "stored_session_id": self.stored,
            "session_id": self.runtime,
            "title": self.title,
            "persisted": self.persisted,
            "running": self.running,
            "viewers": sorted(c.id for c in self.live_viewers()),
            "messages": self.messages,
            "queued": list(self.queued),
            "open_requests": [{"id": w.id, "method": w.method} for w in self.open_requests.values()],
            "dropped_events": self.dropped_events,
        }


from . import methods  # noqa: E402  (registers METHODS)

METHODS = methods.METHODS
check_params = methods.check_params
