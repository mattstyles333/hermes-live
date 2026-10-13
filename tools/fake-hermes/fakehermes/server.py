"""HTTP side: dashboard auth, ws-ticket, voice-live status, the /api/ws upgrade and the admin API."""

import json
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import admin
from .hub import Hub
from .wsproto import WebSocket, accept_key


def serve(args, data_dir: str) -> None:
    hub = Hub(args.scenario, data_dir, args.user, args.password, quiet=args.quiet)

    class Handler(FakeHermesHandler):
        pass

    Handler.hub = hub
    httpd = ThreadingHTTPServer((args.bind, args.port), Handler)
    httpd.daemon_threads = True
    if args.tls_cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.tls_cert, args.tls_key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    port = httpd.server_address[1]
    hub.port = port
    print("FAKE_HERMES_LISTENING port=%d bind=%s data_dir=%s scenario=%s" % (port, args.bind, data_dir, hub.scenario.name), flush=True)
    try:
        httpd.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass


class FakeHermesHandler(BaseHTTPRequestHandler):
    hub: Hub = None
    protocol_version = "HTTP/1.1"
    server_version = "fake-hermes/1"

    def log_message(self, fmt, *args):
        if not self.hub.quiet:
            sys.stdout.write("http %s\n" % (fmt % args))
            sys.stdout.flush()

    # ── plumbing ───────────────────────────────────────────────────────────────────────────

    def _body(self):
        """The JSON object body (read once; {} when empty, None when not JSON)."""
        if hasattr(self, "_parsed"):
            return self._parsed
        self._parsed = self._read_body()
        return self._parsed

    def _read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return {}
        try:
            value = json.loads(raw.decode("utf-8"))
            return value if isinstance(value, dict) else {}
        except ValueError:
            return None

    def send_json(self, code: int, body, cookies=()):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for cookie in cookies:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(data)

    def _bearer(self):
        """The bearer, else the access cookie (an invalid bearer does not fall back to cookies)."""
        auth = self.headers.get("Authorization") or ""
        if auth.lower().startswith("bearer "):
            return auth[7:].strip()
        for part in (self.headers.get("Cookie") or "").split(";"):
            name, _, value = part.strip().partition("=")
            if name in ("hermes_session_at", "__Host-hermes_session_at", "__Secure-hermes_session_at"):
                return value.strip('"')
        return None

    def _unauthenticated(self, token=None):
        if not token:
            return self.send_json(401, {"error": "unauthenticated", "detail": "Unauthorized",
                                        "reason": "no_cookie", "login_url": "/login"})
        self.send_json(401, {"error": "session_expired", "detail": "Unauthorized",
                             "reason": "invalid_or_expired_session", "login_url": "/login"})

    def _authed(self) -> bool:
        token = self._bearer()
        if self.hub.access_ok(token):
            return True
        self._unauthenticated(token)
        return False

    # ── routes ─────────────────────────────────────────────────────────────────────────────

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def _route(self, method: str):
        self.__dict__.pop("_parsed", None)  # the handler lives as long as the keep-alive connection
        url = urlparse(self.path)
        path = url.path
        query = {k: v[-1] for k, v in parse_qs(url.query).items()}
        hub = self.hub
        if path.startswith("/__fake/"):
            body = self._body() if method in ("POST", "DELETE") else {}
            if body is None:
                return self.send_json(400, {"error": "bad json"})
            code, result = admin.handle(hub, method, path[len("/__fake/"):], query, body)
            return self.send_json(code, result)
        hub.record_http(method, path)
        if method == "POST":
            self._body()  # always drain the body: the connection is keep-alive
        if method == "GET" and path == "/api/ws":
            return self._upgrade(query)
        if method == "POST" and path == "/auth/password-login":
            return self._login()
        if method == "POST" and path == "/auth/native/refresh":
            return self._refresh()
        if method == "POST" and path == "/api/auth/ws-ticket":
            return self._ticket()
        if method == "GET" and path == "/api/audio/voice-live/status":
            return self._voice_status()
        if method == "POST" and path == "/api/audio/voice-live/session":
            if not self._authed():
                return
            return self.send_json(503, {"ok": False, "error": "voice_live_unavailable",
                                        "detail": "fake Hermes does not provide voice-live"})
        if method == "GET" and path in ("/", "/healthz", "/api/status"):
            return self.send_json(200, {"ok": True, "fake": True})
        self.send_json(404, {"detail": "Not Found"})

    def _login(self):
        body = self._body()
        if body is None or not isinstance(body.get("provider"), str):
            return self.send_json(422, {"detail": [{"type": "missing", "loc": ["body", "provider"], "msg": "Field required"}]})
        refused = self.hub.faults.get("refuse_logins")
        if refused:
            code = refused if isinstance(refused, int) and refused >= 400 else 401
            return self.send_json(code, self.hub.login_error(code))
        if body["provider"] != "basic":
            return self.send_json(404, {"detail": "Unknown provider"})
        if body.get("username") != self.hub.user or body.get("password") != self.hub.password:
            return self.send_json(401, self.hub.login_error(401))
        access, refresh, ttl = self.hub.mint_tokens(self.hub.user)
        attrs = "HttpOnly; SameSite=Lax; Path=/"
        cookies = [
            "hermes_session_at=%s; Max-Age=%d; %s" % (access, ttl, attrs),
            "hermes_session_rt=%s; Max-Age=%d; %s" % (refresh, self.hub.refresh_ttl_s, attrs),
            "hermes_session_provider=basic; Max-Age=%d; %s" % (self.hub.refresh_ttl_s, attrs),
        ]
        self.send_json(200, {"ok": True, "next": body.get("next") or "/"}, cookies)

    def _refresh(self):
        body = self._body() or {}
        if not body.get("refresh_token"):
            return self.send_json(400, {"detail": "refresh_token required"})
        refused = self.hub.faults.get("refuse_refresh")
        if refused and isinstance(refused, int) and refused >= 500:
            return self.send_json(refused, {"detail": "Auth provider 'basic' unreachable"})
        result = None if refused else self.hub.refresh(body["refresh_token"])
        if result is None:
            return self.send_json(401, {"error": "session_expired",
                                        "detail": "Refresh token expired or invalid; start a new sign-in."})
        self.send_json(200, result)

    def _ticket(self):
        self._body()
        refused = self.hub.faults.get("refuse_tickets")
        if refused:
            code = refused if isinstance(refused, int) and refused >= 400 else 401
            if code == 401:
                return self._unauthenticated("refused")
            return self.send_json(code, {"detail": "Auth provider 'basic' unreachable"})
        if not self._authed():
            return
        self.send_json(200, {"ticket": self.hub.mint_ticket(), "ttl_seconds": 30})

    def _voice_status(self):
        if not self._authed():
            return
        self.send_json(200, {"ok": True, "mode": "disabled", "available": False,
                             "reason": "fake Hermes: voice-live is not provided (the cascade does not need it)",
                             "model": None, "voice": None})

    def _upgrade(self, query):
        hub = self.hub
        key = self.headers.get("Sec-WebSocket-Key")
        if not key or (self.headers.get("Upgrade") or "").lower() != "websocket":
            return self.send_json(400, {"detail": "expected a websocket upgrade"})
        # The ticket rides in ?ticket= or in the subprotocols `hermes-gateway-v1, hermes-gateway-ticket.<t>`.
        protocols = [p.strip() for p in (self.headers.get("Sec-WebSocket-Protocol") or "").split(",") if p.strip()]
        ticket = query.get("ticket") or next(
            (p[len("hermes-gateway-ticket."):] for p in protocols if p.startswith("hermes-gateway-ticket.")), "")
        if hub.faults.get("refuse_upgrade") or not hub.take_ticket(ticket):
            # The real server closes before accept (4401); uvicorn turns that into HTTP 403.
            self.close_connection = True
            return self.send_json(403, {"detail": "Forbidden"})
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept_key(key))
        if "hermes-gateway-v1" in protocols:
            self.send_header("Sec-WebSocket-Protocol", "hermes-gateway-v1")
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        hub.run_connection(WebSocket(self.connection, self.rfile))
