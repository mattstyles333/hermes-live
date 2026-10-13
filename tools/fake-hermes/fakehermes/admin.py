"""Admin and assertion API under /__fake/ (no auth: bind to 127.0.0.1 only). See README.md."""

import threading

from .agent import Scenario

FAULT_KEYS = {
    "refuse_logins",    # true | 401 | 429 | 503: /auth/password-login refuses
    "refuse_refresh",   # true | 401 | 503: /auth/native/refresh refuses
    "refuse_tickets",   # true | 401 | 503: /api/auth/ws-ticket refuses
    "refuse_upgrade",   # true: /api/ws answers 403
    "no_ready",         # true: no gateway.ready after the upgrade
    "no_pong",          # true: gateway.ping gets no answer
    "lose_submits",     # {"count": n, "mode": "after_store" | "before_store", "drop": true}
    "busy_errors",      # n: the next n prompt.submit calls answer 4009 "session busy"
    "fail_resume",      # n: the next n session.resume calls answer 5000
    "reap_on_drop",     # true: a detached idle session loses its runtime id (prompt.submit -> 4001)
}


def handle(hub, method: str, path: str, query: dict, body: dict):
    path = path.strip("/")
    if path == "health":
        return 200, {"ok": True, "fake": True, "port": hub.port, "scenario": hub.scenario.name,
                     "boot": hub.boot_id, "busy_mode": hub.busy_mode, "interrupt_style": hub.interrupt_style,
                     "data_dir": hub.data_dir}
    if path == "faults":
        if method == "GET":
            return 200, {"faults": hub.faults}
        if method == "DELETE":
            with hub.lock:
                hub.faults.clear()
            hub.stall(False)
            return 200, {"faults": {}}
        unknown = sorted(set(body) - FAULT_KEYS)
        if unknown:
            return 400, {"error": "unknown fault", "keys": unknown, "known": sorted(FAULT_KEYS)}
        with hub.lock:
            for k, v in body.items():
                if v in (None, False, 0):
                    hub.faults.pop(k, None)
                else:
                    hub.faults[k] = v
        return 200, {"faults": hub.faults}
    if path == "config" and method == "POST":
        with hub.lock:
            if "busy_mode" in body:
                if body["busy_mode"] not in ("interrupt", "queue", "steer"):
                    return 400, {"error": "busy_mode must be interrupt, queue or steer"}
                hub.busy_mode = body["busy_mode"]
            if "interrupt_style" in body:
                if body["interrupt_style"] not in ("redirect", "queue"):
                    return 400, {"error": "interrupt_style must be redirect or queue"}
                hub.interrupt_style = body["interrupt_style"]
            if "access_ttl_s" in body:
                hub.access_ttl_s = int(body["access_ttl_s"])
        if body.get("expire_access_tokens"):
            hub.expire_access_tokens()
        return 200, {"busy_mode": hub.busy_mode, "interrupt_style": hub.interrupt_style,
                     "access_ttl_s": hub.access_ttl_s}
    if path == "scenario" and method == "POST":
        try:
            scenario = Scenario.load(body["path"]) if "path" in body else Scenario(body)
        except (OSError, ValueError, KeyError) as e:
            return 400, {"error": "bad scenario: %s" % e}
        with hub.lock:
            hub.scenario = scenario
            hub.busy_mode = scenario.busy_mode
            hub.interrupt_style = scenario.data.get("interrupt_style", "redirect")
        return 200, {"scenario": scenario.name}
    if path == "next" and method == "POST":
        rule = body.get("rule")
        if not isinstance(rule, dict):
            return 400, {"error": "body needs {rule: {...}, match?: 'text'}"}
        with hub.lock:
            hub.next_rules.append((body.get("match"), rule))
        return 200, {"queued_rules": len(hub.next_rules)}
    if path == "drop" and method == "POST":
        n = hub.drop(body.get("mode", "abort"), body.get("session"))
        return 200, {"dropped": n}
    if path == "stall" and method == "POST":
        on = body.get("on", True)
        hub.stall(bool(on))
        ms = body.get("ms")
        if on and ms:
            timer = threading.Timer(ms / 1000.0, lambda: hub.stall(False))
            timer.daemon = True
            timer.start()
        return 200, {"stalled": bool(on), "ms": ms}
    if path == "restart" and method == "POST":
        hub.restart()
        return 200, {"restarted": True, "boot": hub.boot_id, "sessions": len(hub.sessions)}
    if path == "reset" and method == "POST":
        hub.reset()
        return 200, {"reset": True, "boot": hub.boot_id}
    if path == "sessions":
        with hub.lock:
            out = []
            for s in hub.sessions.values():
                snap = s.snapshot()
                snap["received"] = [e for e in hub.log if e.get("session") == s.stored]
                out.append(snap)
        return 200, {"sessions": out}
    if path == "received":
        with hub.lock:
            entries = [e for e in hub.log if _wanted(e, query)]
        return 200, {"received": entries}
    if path == "assert/prompts":
        return 200, prompts(hub, query.get("session"))
    if path == "assert/double-submits":
        return 200, double_submits(hub)
    if path == "assert/interrupts":
        with hub.lock:
            items = [_strip(e) for e in hub.log if e.get("kind") == "interrupt"]
        return 200, {"interrupts": items, "count": len(items)}
    return 404, {"error": "unknown admin path", "path": path}


def _wanted(entry: dict, query: dict) -> bool:
    if "session" in query and entry.get("session") != query["session"]:
        return False
    if "kind" in query and entry.get("kind") != query["kind"]:
        return False
    if "method" in query and entry.get("method") != query["method"]:
        return False
    return True


def _strip(entry: dict) -> dict:
    return {"ts": entry["ts"], "session": entry.get("session"), "boot": entry.get("boot"),
            "running": entry.get("running")}


def prompts(hub, session=None) -> dict:
    """Every prompt.submit the server read, per stored session, with its outcome."""
    with hub.lock:
        items = [e for e in hub.log if e.get("kind") == "prompt" and (session is None or e.get("session") == session)]
    by_session = {}
    for e in items:
        by_session.setdefault(e.get("session"), []).append({k: e.get(k) for k in (
            "ts", "text", "outcome", "counted", "queued", "surface", "voice_context_chars", "boot", "busy_mode")})
    return {"sessions": by_session, "count": len(items)}


def double_submits(hub) -> dict:
    """Same text accepted twice: in one session, or in two sessions (a resend into a new session)."""
    with hub.lock:
        counted = [e for e in hub.log if e.get("kind") == "prompt" and e.get("counted")]
    same, texts = {}, {}
    for e in counted:
        same.setdefault((e.get("session"), e.get("text")), []).append(e["ts"])
        texts.setdefault(e.get("text"), set()).add(e.get("session"))
    same_session = [{"session": s, "text": t, "count": len(ts), "ts": ts} for (s, t), ts in same.items() if len(ts) > 1]
    cross_session = [{"text": t, "sessions": sorted(x for x in ss if x)} for t, ss in texts.items() if len(ss) > 1]
    return {"ok": not same_session and not cross_session, "same_session": same_session, "cross_session": cross_session}
