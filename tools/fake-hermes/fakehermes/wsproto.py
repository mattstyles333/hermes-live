"""Minimal RFC 6455 server side: handshake key, framing, ping/pong, close, abrupt drop.

Client frames are masked; server frames are not. Text and continuation frames are joined into one
message. Nothing here knows about JSON-RPC.
"""

import base64
import hashlib
import socket
import struct
import threading

_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


def accept_key(key: str) -> str:
    """The `Sec-WebSocket-Accept` value for a client's `Sec-WebSocket-Key`."""
    return base64.b64encode(hashlib.sha1((key.strip() + _GUID).encode("ascii")).digest()).decode("ascii")


class WebSocketClosed(Exception):
    """The peer closed (close frame or EOF), or we aborted."""


class WebSocket:
    """One upgraded connection. `recv` on one thread; `send_text` from any thread."""

    def __init__(self, sock: socket.socket, rfile):
        self.sock = sock
        self.rfile = rfile
        self._send_lock = threading.Lock()
        self.close_sent = False
        self.aborted = False

    # ── reading ────────────────────────────────────────────────────────────────────────────

    def _read(self, n: int) -> bytes:
        data = b""
        while len(data) < n:
            chunk = self.rfile.read(n - len(data))
            if not chunk:
                raise WebSocketClosed("eof")
            data += chunk
        return data

    def recv(self) -> str:
        """The next text message. Raises WebSocketClosed on close or EOF."""
        message = bytearray()
        in_message = False
        while True:
            try:
                b1, b2 = self._read(2)
            except (OSError, ValueError) as e:
                raise WebSocketClosed(str(e))
            fin = bool(b1 & 0x80)
            opcode = b1 & 0x0F
            masked = bool(b2 & 0x80)
            length = b2 & 0x7F
            if length == 126:
                (length,) = struct.unpack(">H", self._read(2))
            elif length == 127:
                (length,) = struct.unpack(">Q", self._read(8))
            mask = self._read(4) if masked else b""
            payload = bytearray(self._read(length))
            if masked:
                for i in range(length):
                    payload[i] ^= mask[i % 4]
            if opcode == OP_CLOSE:
                if not self.close_sent:
                    code = bytes(payload[:2]) if len(payload) >= 2 else struct.pack(">H", 1000)
                    self._send_frame(OP_CLOSE, code)
                raise WebSocketClosed("close frame")
            if opcode == OP_PING:
                self._send_frame(OP_PONG, bytes(payload))
                continue
            if opcode == OP_PONG:
                continue
            if opcode in (OP_TEXT, OP_BINARY):
                message = payload
                in_message = True
            elif opcode == OP_CONT and in_message:
                message += payload
            if fin and in_message:
                return bytes(message).decode("utf-8", errors="replace")

    # ── writing ────────────────────────────────────────────────────────────────────────────

    def _send_frame(self, opcode: int, payload: bytes) -> bool:
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(n)
        elif n < 65536:
            header.append(126)
            header += struct.pack(">H", n)
        else:
            header.append(127)
            header += struct.pack(">Q", n)
        with self._send_lock:
            if self.aborted:
                return False
            try:
                self.sock.sendall(bytes(header) + payload)
                return True
            except OSError:
                return False

    def send_text(self, text: str) -> bool:
        """Send one text message. False when the socket is gone (never raises)."""
        if self.close_sent:
            return False
        return self._send_frame(OP_TEXT, text.encode("utf-8"))

    def close(self, code: int = 1000, reason: str = "") -> None:
        """Clean close: send a close frame; the reader sees the peer's echo and ends."""
        if self.close_sent or self.aborted:
            return
        self._send_frame(OP_CLOSE, struct.pack(">H", code) + reason.encode("utf-8")[:120])
        self.close_sent = True

    def abort(self) -> None:
        """Abrupt drop: no close frame, the TCP connection just ends (the client sees a failure)."""
        with self._send_lock:
            self.aborted = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
