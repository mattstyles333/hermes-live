"""Persistence for the fake: sessions, tokens and the received log, in one data dir.

Every write is temp-then-rename, so a `kill -9` never leaves a torn file. The received log is
append-only JSON lines (a torn last line is skipped on load).
"""

import json
import os
import threading


class Store:
    def __init__(self, data_dir: str):
        self.dir = data_dir
        self.sessions_dir = os.path.join(data_dir, "sessions")
        os.makedirs(self.sessions_dir, exist_ok=True)
        self._log_lock = threading.Lock()

    # ── atomic JSON files ──────────────────────────────────────────────────────────────────

    def _write(self, path: str, value) -> None:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(value, f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)

    def _read(self, path: str, default):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return default

    # ── sessions ───────────────────────────────────────────────────────────────────────────

    def save_session(self, record: dict) -> None:
        self._write(os.path.join(self.sessions_dir, record["stored"] + ".json"), record)

    def delete_session(self, stored: str) -> None:
        try:
            os.remove(os.path.join(self.sessions_dir, stored + ".json"))
        except OSError:
            pass

    def load_sessions(self) -> list:
        out = []
        for name in sorted(os.listdir(self.sessions_dir)):
            if name.endswith(".json"):
                record = self._read(os.path.join(self.sessions_dir, name), None)
                if isinstance(record, dict) and "stored" in record:
                    out.append(record)
        return out

    # ── tokens ─────────────────────────────────────────────────────────────────────────────

    def save_tokens(self, tokens: dict) -> None:
        self._write(os.path.join(self.dir, "tokens.json"), tokens)

    def load_tokens(self) -> dict:
        tokens = self._read(os.path.join(self.dir, "tokens.json"), {})
        tokens.setdefault("access", {})
        tokens.setdefault("refresh", {})
        return tokens

    # ── received log (what the client sent, for assertions; survives restarts) ─────────────

    def append_log(self, entry: dict) -> None:
        line = json.dumps(entry, ensure_ascii=False)
        with self._log_lock:
            with open(os.path.join(self.dir, "received.jsonl"), "a", encoding="utf-8") as f:
                f.write(line + "\n")
                f.flush()
                os.fsync(f.fileno())

    def load_log(self) -> list:
        out = []
        try:
            with open(os.path.join(self.dir, "received.jsonl"), encoding="utf-8") as f:
                for line in f:
                    try:
                        out.append(json.loads(line))
                    except ValueError:
                        continue
        except OSError:
            pass
        return out

    def clear(self) -> None:
        """Forget everything (admin reset)."""
        for name in os.listdir(self.sessions_dir):
            os.remove(os.path.join(self.sessions_dir, name))
        for name in ("tokens.json", "received.jsonl"):
            try:
                os.remove(os.path.join(self.dir, name))
            except OSError:
                pass
