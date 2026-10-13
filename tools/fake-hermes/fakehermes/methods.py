"""JSON-RPC methods of the fake gateway. Shapes follow the deployed server (README "Protocol")."""

import threading

from . import gateway as gw

# Params models on the real server are extra="forbid": an unknown key answers 4000.
ALLOWED = {
    "client.capabilities": {"server_requests"},
    "session.create": {"profile", "cols", "source", "cwd", "cwd_explicit", "messages", "parent_session_id", "title",
                       "model", "provider", "reasoning_effort", "fast", "close_on_disconnect", "hidden",
                       "room_plumbing", "follow_profile_config"},
    "session.resume": {"session_id", "profile", "cols", "source", "lazy", "defer_history", "omit_messages",
                       "eager_build", "close_on_disconnect", "inline_images"},
    "prompt.submit": {"session_id", "text", "display_kind", "interrupted", "queued", "surface", "voice_context",
                      "title_preview", "confirm_truncate", "confirm_empty_truncate", "rebind_survivor_row_ids",
                      "profile"},
    "session.interrupt": {"session_id", "profile", "expected_hosted_task_id"},
    "config.get": {"key", "profile", "session_id"},
}


def check_params(method: str, params: dict):
    allowed = ALLOWED.get(method)
    if allowed is None:
        return
    extra = sorted(k for k in params if k not in allowed and not k.startswith("truncate_before_"))
    if extra:
        raise gw.RpcError(4000, "unknown param(s) for %s: %s" % (method, ", ".join(extra)))


def _live_session(conn, params) -> "gw.Session":
    sid = params.get("session_id") or ""
    with conn.hub.lock:
        session = conn.hub.by_runtime.get(sid)
    if session is None:
        raise gw.RpcError(4001, "session not found")
    return session


def _take(faults: dict, key: str) -> bool:
    """Consume one unit of a counted fault."""
    n = faults.get(key)
    if not n:
        return False
    if isinstance(n, int) and n > 1:
        faults[key] = n - 1
    else:
        faults.pop(key)
    return True


def ping(conn, rid, params):
    return gw.NO_REPLY if conn.hub.faults.get("no_pong") else {"ok": True}


def capabilities(conn, rid, params):
    conn.server_requests = params.get("server_requests") is True
    return {"server_requests": ["approval", "clarify", "display.install.sudo", "preview.act", "preview.read",
                                "secret", "sudo", "terminal.read", "tour", "vault.code", "vault.save_login",
                                "vault.unlock_prompt", "window.read"],
            "declines_not_shown": True}


def _info(session) -> dict:
    return {"model": "fake-hermes", "provider": "fake", "tools": {}, "skills": {}, "cwd": "/fake",
            "branch": None, "project": None, "lazy": True, "profile_name": "default"}


def session_create(conn, rid, params):
    hub = conn.hub
    session = hub.new_session(conn, params.get("title"))

    def built():  # the agent builds asynchronously, then session.info goes out
        session.emit("session.info", _info(session))
    timer = threading.Timer(0.03, built)
    timer.daemon = True
    return ({"session_id": session.runtime, "stored_session_id": session.stored, "message_count": 0,
             "messages": [], "info": _info(session)}, timer.start)


def _row(m: dict) -> dict:
    if m["role"] == "tool":
        return {"role": "tool", "name": m.get("name"), "context": m.get("context"), "timestamp": m.get("timestamp")}
    return {"role": m["role"], "text": m.get("text", ""), "timestamp": m.get("timestamp")}


def session_resume(conn, rid, params):
    hub = conn.hub
    stored = params.get("session_id") or ""
    if not stored:
        raise gw.RpcError(4006, "session_id required")
    omit = bool(params.get("omit_messages"))
    with hub.lock:
        if _take(hub.faults, "fail_resume"):
            raise gw.RpcError(5000, "fake: resume failed")
        session = hub.sessions.get(stored)
        if session is None or not (session.persisted or session.runtime):
            raise gw.RpcError(4007, "session not found")
        cold = session.runtime is None
        hub.bind_runtime(session)
        session.viewers.add(conn)
        hub.record({"kind": "resume", "session": session.stored, "omit_messages": omit, "conn": conn.id, "cold": cold})
        messages = [] if omit else [_row(m) for m in session.messages]
        result = {
            "session_id": session.runtime,
            "resumed": session.stored,
            "session_key": session.stored,
            "message_count": len(session.messages),
            "messages": messages,
            "messages_omitted": omit,
            "info": _info(session),
            "running": session.running,
            "started_at": session.created_at,
        }
        if cold:
            result.update({"inflight": None, "status": "idle"})
            return result
        result["status"] = "waiting" if session.open_requests else ("working" if session.running else "idle")
        if session.running:
            result["turn_started_at"] = session.turn.started_at
            result["inflight"] = {"user": session.turn.text, "assistant": session.turn.streamed, "streaming": True}
        if session.queued:
            result["queued"] = {"user": "\n\n".join(session.queued)}
        if session.open_requests:
            result["open_requests"] = [{"id": w.id, "method": w.method, "params": w.params}
                                       for w in session.open_requests.values()]
        return result


def prompt_submit(conn, rid, params):
    hub = conn.hub
    session = _live_session(conn, params)
    text = params.get("text") if isinstance(params.get("text"), str) else ""
    queued_flag = params.get("queued") is True
    surface = params.get("surface") if params.get("surface") in ("hud", "voice-live") else None
    base = {"kind": "prompt", "session": session.stored, "text": text, "queued": queued_flag, "surface": surface,
            "voice_context_chars": len(params.get("voice_context") or ""), "busy_mode": hub.busy_mode}
    with hub.lock:
        lose = hub.faults.get("lose_submits")
        if lose:
            spec = lose if isinstance(lose, dict) else {"count": int(lose)}
            count = int(spec.get("count", 1))
            if count <= 1:
                hub.faults.pop("lose_submits")
            else:
                hub.faults["lose_submits"] = {**spec, "count": count - 1}
            if spec.get("mode", "after_store") == "before_store":
                hub.record({**base, "outcome": "lost_before_store", "counted": False})
            else:
                status = _accept(hub, session, text, queued_flag)["status"]
                hub.record({**base, "outcome": "lost_after_store:" + status, "counted": True})
            lost = True
        else:
            lost = False
            if session.running and _take(hub.faults, "busy_errors"):
                hub.record({**base, "outcome": "error:4009", "counted": False})
                raise gw.RpcError(4009, "session busy")
            result = _accept(hub, session, text, queued_flag)
            hub.record({**base, "outcome": result["status"], "counted": True})
    if lost:
        if not isinstance(lose, dict) or lose.get("drop", True):
            conn.drop("abort")
        _launch(session)
        return gw.NO_REPLY
    return result, lambda: _launch(session)


def _accept(hub, session, text: str, queued_flag: bool) -> dict:
    """Take the text (caller holds hub.lock). A new turn's thread starts in _launch, after the reply."""
    if not session.running:
        session.start_turn(text, launch=False)
        return {"status": "streaming", "user_row_id": len(session.messages)}
    mode = "queue" if queued_flag else hub.busy_mode
    if mode == "steer":
        session.turn.absorb(text)
        return {"status": "steered"}
    if mode == "interrupt" and hub.interrupt_style == "redirect":
        # The live turn takes the correction in (stored as a real user row); no new turn starts.
        session.add_user_row(text)
        session.turn.absorb(text)
        return {"status": "redirected"}
    # Queue (durable at accept); interrupt mode also asks the live turn to stop.
    session.queued.append(text)
    session.add_user_row(text)
    if mode == "interrupt":
        session.turn.interrupt()
    return {"status": "queued"}


def _launch(session):
    turn = session.turn
    if turn is not None and not turn.launched:
        turn.launched = True
        turn.start()


def session_interrupt(conn, rid, params):
    hub = conn.hub
    session = _live_session(conn, params)
    with hub.lock:
        hub.record({"kind": "interrupt", "session": session.stored, "running": session.running})
    session.interrupt()
    return {"status": "interrupted"}


def config_get(conn, rid, params):
    key = params.get("key")
    if key == "busy":
        return {"value": conn.hub.busy_mode}
    raise gw.RpcError(4002, "unknown config key: %s" % key)


METHODS = {
    "gateway.ping": ping,
    "client.capabilities": capabilities,
    "session.create": session_create,
    "session.resume": session_resume,
    "prompt.submit": prompt_submit,
    "session.interrupt": session_interrupt,
    "config.get": config_get,
}
