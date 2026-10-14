"""Server state: tokens, tickets, sessions, connections, faults and the received log."""

import secrets
import threading
import time

from .agent import Scenario
from .gateway import Connection, Session
from .store import Store

ACCESS_TTL_S = 12 * 3600
REFRESH_TTL_S = 30 * 24 * 3600
TICKET_TTL_S = 30


def _id(prefix: str) -> str:
    return prefix + secrets.token_hex(4)


def _runtime_id() -> str:
    return secrets.token_hex(4)  # 8 hex, like the real server


def _stored_id() -> str:
    return time.strftime("%Y%m%d_%H%M%S", time.gmtime()) + "_" + secrets.token_hex(3)


class Hub:
    def __init__(self, scenario_path: str, data_dir: str, user: str, password: str, quiet: bool = False):
        self.lock = threading.RLock()
        self.scenario_path = scenario_path
        self.scenario = Scenario.load(scenario_path)
        self.store = Store(data_dir)
        self.data_dir = data_dir
        self.user = user
        self.password = password
        self.quiet = quiet
        self.port = 0
        self.access_ttl_s = ACCESS_TTL_S
        self.refresh_ttl_s = REFRESH_TTL_S
        self.unstalled = threading.Event()
        self.unstalled.set()
        self._boot()

    def _boot(self):
        """(Re)load everything a real server keeps across a restart; forget everything it doesn't."""
        self.faults = {}
        self.busy_mode = self.scenario.busy_mode
        self.interrupt_style = self.scenario.data.get("interrupt_style", "redirect")
        self.connections = set()
        self.tickets = {}
        self.next_rules = []
        self.tokens = self.store.load_tokens()
        self.log = self.store.load_log()
        self.sessions = {}
        self.by_runtime = {}
        for record in self.store.load_sessions():
            session = Session.from_record(self, record)
            self.sessions[session.stored] = session
        self.boot_id = _id("boot-")
        self.unstalled.set()

    def say(self, text: str):
        if not self.quiet:
            print(text, flush=True)

    # ── auth ───────────────────────────────────────────────────────────────────────────────

    def login_error(self, code: int) -> dict:
        if code == 429:
            return {"detail": "Too many login attempts. Try again shortly."}
        if code >= 500:
            return {"detail": "Provider unreachable: fake outage"}
        return {"detail": "Invalid credentials"}

    def mint_tokens(self, user: str):
        with self.lock:
            now = time.time()
            ttl = int(self.access_ttl_s)
            access = "fh-at-" + secrets.token_urlsafe(18)
            refresh = "fh-rt-" + secrets.token_urlsafe(18)
            self.tokens["access"][access] = {"user": user, "exp": now + ttl}
            self.tokens["refresh"][refresh] = {"user": user, "exp": now + self.refresh_ttl_s}
            self.store.save_tokens(self.tokens)
            return access, refresh, ttl

    def refresh(self, refresh_token: str):
        with self.lock:
            entry = self.tokens["refresh"].pop(refresh_token, None)
            if entry is None or entry["exp"] < time.time():
                self.store.save_tokens(self.tokens)
                return None
            access, refresh, ttl = self.mint_tokens(entry["user"])
            return {"access_token": access, "refresh_token": refresh, "token_type": "Bearer",
                    "expires_at": int(time.time() + ttl), "provider": "basic", "user_id": entry["user"]}

    def access_ok(self, token) -> bool:
        with self.lock:
            entry = self.tokens["access"].get(token or "")
            return entry is not None and entry["exp"] > time.time()

    def expire_access_tokens(self):
        with self.lock:
            for entry in self.tokens["access"].values():
                entry["exp"] = 0
            self.store.save_tokens(self.tokens)

    def mint_ticket(self) -> str:
        with self.lock:
            ticket = secrets.token_urlsafe(32)
            self.tickets[ticket] = time.time() + TICKET_TTL_S
            return ticket

    def take_ticket(self, ticket: str) -> bool:
        with self.lock:
            exp = self.tickets.pop(ticket, None)
            return exp is not None and exp > time.time()

    # ── sessions ───────────────────────────────────────────────────────────────────────────

    def new_session(self, conn, title=None) -> Session:
        with self.lock:
            session = Session(self, _stored_id(), title=title)
            session.runtime = _runtime_id()
            session.viewers.add(conn)
            self.sessions[session.stored] = session
            self.by_runtime[session.runtime] = session
            return session

    def bind_runtime(self, session: Session):
        with self.lock:
            if session.runtime is None:
                session.runtime = _runtime_id()
            self.by_runtime[session.runtime] = session

    def pick_rule(self, text: str) -> dict:
        with self.lock:
            for i, (pattern, rule) in enumerate(self.next_rules):
                if pattern is None or pattern.lower() in text.lower():
                    del self.next_rules[i]
                    return rule
        return self.scenario.pick(text)

    # ── connections ────────────────────────────────────────────────────────────────────────

    def run_connection(self, ws):
        conn = Connection(self, ws, _id("ws-"))
        with self.lock:
            self.connections.add(conn)
        try:
            conn.serve()
        finally:
            with self.lock:
                self.connections.discard(conn)
                for session in self.sessions.values():
                    session.viewers.discard(conn)
                    if self.faults.get("reap_on_drop") and session.runtime and not session.viewers and not session.running:
                        # The reaper ends a detached idle session: its runtime id answers 4001 now.
                        self.by_runtime.pop(session.runtime, None)
                        session.runtime = None

    def drop(self, mode: str = "abort", stored=None) -> int:
        with self.lock:
            targets = list(self.connections)
            if stored:
                session = self.sessions.get(stored)
                targets = session.live_viewers() if session else []
        for conn in targets:
            conn.drop(mode)
        return len(targets)

    def stall(self, on: bool):
        if on:
            self.unstalled.clear()
        else:
            self.unstalled.set()
            with self.lock:
                conns = list(self.connections)
            for conn in conns:
                conn.flush_held()

    def restart(self):
        """As if the process restarted on the same data dir."""
        with self.lock:
            conns = list(self.connections)
            sessions = list(self.sessions.values())
        self.unstalled.set()
        for session in sessions:
            session.shutdown()
        for conn in conns:
            conn.drop("abort")
        with self.lock:
            self._boot()

    def reset(self):
        """Forget everything, including persisted sessions, tokens and the received log."""
        with self.lock:
            sessions = list(self.sessions.values())
            conns = list(self.connections)
        for session in sessions:
            session.shutdown()
        for conn in conns:
            conn.drop("abort")
        with self.lock:
            self.store.clear()
            self._boot()

    # ── received log ───────────────────────────────────────────────────────────────────────

    def record(self, entry: dict):
        entry = {"ts": round(time.time(), 3), "boot": self.boot_id, **entry}
        with self.lock:
            self.log.append(entry)
        self.store.append_log(entry)

    def record_http(self, method: str, path: str):
        self.record({"kind": "http", "method": method, "path": path})
