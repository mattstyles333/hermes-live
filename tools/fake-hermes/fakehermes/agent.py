"""Scripted agent: picks a rule for a prompt and plays it as gateway events.

A rule (see README "Scenario file") is a list of steps (tool calls with delays, interim text,
approval or clarify requests that wait for the client, failures, hangs) and a reply that is
streamed as `message.delta` chunks and ends with `message.complete`.
"""

import json
import re
import secrets
import threading
import time

DEFAULT_STREAM = {"chunk_words": 4, "chunk_delay_ms": 40}


class Interrupted(Exception):
    pass


class Scenario:
    def __init__(self, data: dict):
        self.data = data
        self.name = data.get("name", "scenario")
        self.busy_mode = data.get("busy_mode", "interrupt")
        self.stream = {**DEFAULT_STREAM, **data.get("stream", {})}
        self.rules = data.get("rules", [])
        self.fallback = data.get("fallback") or {"name": "fallback", "reply": "Done. (fake Hermes: no rule matched.)"}

    @staticmethod
    def load(path: str) -> "Scenario":
        with open(path, encoding="utf-8") as f:
            return Scenario(json.load(f))

    def pick(self, text: str) -> dict:
        lowered = text.lower()
        for rule in self.rules:
            if _matches(rule, lowered, text):
                return rule
        return self.fallback


def _matches(rule: dict, lowered: str, raw: str) -> bool:
    anyof = [k.lower() for k in rule.get("match", [])]
    allof = [k.lower() for k in rule.get("all", [])]
    if anyof and not any(k in lowered for k in anyof):
        return False
    if allof and not all(k in lowered for k in allof):
        return False
    pattern = rule.get("regex")
    if pattern and not re.search(pattern, raw, re.IGNORECASE):
        return False
    return bool(anyof or allof or pattern)


_PLACEHOLDER = re.compile(r"\{(prompt|choice|answer\.[A-Za-z0-9_\-]+)\}")


def render(template: str, ctx: dict) -> str:
    def sub(m):
        key = m.group(1)
        if key == "prompt":
            return ctx.get("prompt", "")[:120]
        if key == "choice":
            return ctx.get("choice") or ""
        value = ctx.get("answers", {}).get(key[len("answer."):])
        return value if isinstance(value, str) else ""
    return _PLACEHOLDER.sub(sub, template)


class Turn:
    """One running turn of a session. `run` is the thread body; `interrupt` ends it early.

    `host` (the session) supplies emit(type, payload), server_request(method, params) -> waiter,
    cancel_request(waiter, reason), tool_row(name, context) and finish(turn, text, status, error).
    """

    def __init__(self, host, text: str, rule: dict, stream: dict):
        self.host = host
        self.text = text
        self.rule = rule
        self.stream = {**stream, **rule.get("stream", {})}
        self.stop = threading.Event()
        self.cancelled = False  # set by restart: say nothing more
        self.streamed = ""
        self.started_at = time.time()
        self.launched = False
        self.corrections = []  # redirected or steered text the live turn absorbed
        self.thread = threading.Thread(target=self.run, name="turn", daemon=True)

    def start(self):
        self.thread.start()

    def interrupt(self):
        self.stop.set()

    def absorb(self, text: str):
        """A redirect or steer: the live turn takes the text in; its reply acknowledges it."""
        self.corrections.append(text)

    def cancel(self):
        self.cancelled = True
        self.stop.set()

    def _wait(self, ms: float):
        if ms > 0 and self.stop.wait(ms / 1000.0):
            raise Interrupted()
        if self.stop.is_set():
            raise Interrupted()

    def run(self):
        ctx = {"prompt": self.text, "answers": {}}
        try:
            self.host.emit("message.start", None)
            reply = self.rule.get("reply", "Done.")
            for i, step in enumerate(self.rule.get("steps", [])):
                outcome = self._step(i, step, ctx)
                if outcome is not None:
                    kind, value = outcome
                    if kind == "reply":
                        reply = value
                        break
                    if kind == "fail":
                        self.host.finish(self, "", "error", value)
                        return
            final = render(reply, ctx)
            if self.corrections:
                final += " Also noted: %s." % "; ".join(t[:80] for t in self.corrections)
            self._stream_reply(final)
            self.host.finish(self, self.streamed, "complete", None)
        except Interrupted:
            if not self.cancelled:
                self.host.finish(self, self.streamed, "interrupted", None)
        except Exception as e:  # a broken scenario must not kill the server
            if not self.cancelled:
                self.host.finish(self, self.streamed, "error", "fake agent error: %s" % e)

    def _step(self, index: int, step: dict, ctx: dict):
        self._wait(step.get("wait_ms", 0))
        if "tool" in step:
            tool_id = "call_%d_%d" % (int(self.started_at * 1000) % 100000, index)
            payload = {"tool_id": tool_id, "name": step["tool"]}
            if step.get("context"):
                payload["context"] = render(step["context"], ctx)
            self.host.emit("tool.start", payload)
            began = time.time()
            self._wait(step.get("ms", 500))
            summary = render(step.get("summary", "done"), ctx)
            done = {"tool_id": tool_id, "name": step["tool"], "args": {}, "duration_s": round(time.time() - began, 3),
                    "result": {"ok": True, "fake": True}, "summary": summary}
            self.host.emit("tool.complete", done)
            self.host.tool_row(step["tool"], payload.get("context"))
        elif "interim" in step:
            self.host.emit("message.interim", {"text": render(step["interim"], ctx), "already_streamed": False})
        elif "status" in step:
            self.host.emit("status.update", {"kind": step.get("kind", "status"), "text": step["status"]})
        elif "warning" in step:
            self.host.emit("error", {"message": step["warning"]})
        elif "approval" in step:
            return self._approval(step, ctx)
        elif "clarify" in step:
            return self._clarify(step, ctx)
        elif "fail" in step:
            fail = step["fail"]
            message = fail if isinstance(fail, str) else fail.get("message", "The agent failed.")
            if isinstance(fail, dict) and fail.get("error_event"):
                self.host.emit("error", {"message": message})
            return ("fail", message)
        elif step.get("hang"):
            self.stop.wait()
            raise Interrupted()
        return None

    def _approval(self, step: dict, ctx: dict):
        spec = step["approval"]
        params = {
            "request_id": secrets.token_hex(16),
            "command": render(spec.get("command", "run a command"), ctx),
            "description": render(spec.get("description", "The agent wants to run this."), ctx),
            "pattern_key": spec.get("pattern_key", "fake"),
            "pattern_keys": [spec.get("pattern_key", "fake")],
            "allow_permanent": True,
            "allow_session": True,
            "choices": spec.get("choices", ["once", "session", "always", "deny"]),
        }
        if spec.get("tool_name"):
            params["tool_name"] = spec["tool_name"]
        answer = self._ask("approval", params, step.get("timeout_ms", 300_000))
        choice = (answer or {}).get("choice") if isinstance(answer, dict) else None
        ctx["choice"] = choice or "deny"
        if choice in (None, "deny"):
            return ("reply", step.get("deny_reply", "OK, I did not do that."))
        return None

    def _clarify(self, step: dict, ctx: dict):
        spec = step["clarify"]
        questions = []
        for i, q in enumerate(spec.get("questions", [])):
            questions.append({
                "qid": q.get("qid", "q%d" % i),
                "question": render(q.get("question", "Which one?"), ctx),
                "choices": q.get("choices") or None,
                "multi_select": bool(q.get("multi_select", False)),
            })
        answer = self._ask("clarify", {"questions": questions}, step.get("timeout_ms", 300_000))
        answers = (answer or {}).get("answers") if isinstance(answer, dict) else None
        if not answers:
            return ("reply", step.get("cancel_reply", "OK, I will leave that for now."))
        ctx["answers"].update({k: v for k, v in answers.items() if isinstance(v, str)})
        return None

    def _ask(self, method: str, params: dict, timeout_ms: int):
        waiter = self.host.server_request(method, params)
        deadline = time.time() + timeout_ms / 1000.0
        while not waiter.done.wait(0.05):
            if self.stop.is_set():
                self.host.cancel_request(waiter, "interrupted")
                raise Interrupted()
            if time.time() > deadline:
                self.host.cancel_request(waiter, "timeout")
                return None
        return waiter.result

    def _stream_reply(self, text: str):
        words = text.split(" ")
        n = max(1, int(self.stream["chunk_words"]))
        for i in range(0, len(words), n):
            chunk = " ".join(words[i:i + n])
            if i + n < len(words):
                chunk += " "
            self._wait(self.stream["chunk_delay_ms"] if i else 0)
            self.streamed += chunk
            self.host.emit("message.delta", {"text": chunk})
