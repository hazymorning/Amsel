"""Tiny stdlib-only Music Assistant client: HTTP JSON-RPC at /api and a WebSocket at /ws.

No third-party packages: CI runners and dev machines only need python3 (3.10+).
The WebSocket client is a minimal RFC 6455 implementation (text frames, fragmentation,
ping/pong, close). It does not negotiate permessage-deflate, so the server sends plain frames.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any
from urllib.parse import urlsplit


def log(*args: Any) -> None:
    """Print progress to stderr so stdout stays machine-readable."""
    print(*args, file=sys.stderr, flush=True)


class HttpError(Exception):
    """A non-2xx HTTP answer."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body


def http_json(
    url: str,
    payload: Any | None = None,
    token: str | None = None,
    method: str | None = None,
    timeout: float = 120,
) -> Any:
    """GET (payload None) or POST JSON and return the decoded JSON answer."""
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method or ("GET" if data is None else "POST"))
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "amsel-test-server/1")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode()
    except urllib.error.HTTPError as err:
        raise HttpError(err.code, err.read().decode(errors="replace")) from None
    return json.loads(body) if body else None


def rpc(base_url: str, token: str, command: str, **args: Any) -> Any:
    """Run one API command over the HTTP JSON-RPC endpoint (POST /api)."""
    return http_json(
        f"{base_url}/api",
        {"message_id": f"rpc-{time.monotonic_ns()}", "command": command, "args": args},
        token=token,
    )


class WebSocket:
    """Blocking WebSocket client, just enough for the Music Assistant API."""

    def __init__(self, url: str, timeout: float = 30) -> None:
        parts = urlsplit(url)
        if parts.scheme != "ws":
            raise ValueError("only ws:// is supported")
        host = parts.hostname or "localhost"
        port = parts.port or 80
        path = parts.path or "/"
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        handshake = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "User-Agent: amsel-test-server/1\r\n"
            "\r\n"
        )
        self.sock.sendall(handshake.encode())
        self._buf = b""
        while b"\r\n\r\n" not in self._buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("connection closed during handshake")
            self._buf += chunk
        head, self._buf = self._buf.split(b"\r\n\r\n", 1)
        status_line = head.split(b"\r\n", 1)[0].decode()
        if " 101 " not in status_line:
            raise ConnectionError(f"WebSocket upgrade failed: {status_line}")

    def _read_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self._buf)))
            if not chunk:
                raise ConnectionError("connection closed")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        header = bytes([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65536:
            header += bytes([0x80 | 126]) + struct.pack("!H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", length)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def send_json(self, obj: Any) -> None:
        """Send one JSON text message."""
        self._send_frame(0x1, json.dumps(obj).encode())

    def recv_json(self, timeout: float | None = None) -> Any:
        """Receive one complete JSON text message (answers pings, skips binary messages)."""
        while True:
            opcode, data = self.recv(timeout)
            if opcode == 0x1:
                return json.loads(data)

    def recv(self, timeout: float | None = None) -> tuple[int, bytes]:
        """Receive one complete message as (opcode, payload): 0x1 text or 0x2 binary."""
        self.sock.settimeout(timeout)
        message = b""
        first_opcode = 0
        while True:
            b1, b2 = self._read_exact(2)
            fin, opcode = b1 & 0x80, b1 & 0x0F
            length = b2 & 0x7F
            if length == 126:
                (length,) = struct.unpack("!H", self._read_exact(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", self._read_exact(8))
            if b2 & 0x80:  # servers never mask, but be tolerant
                mask = self._read_exact(4)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(self._read_exact(length)))
            else:
                data = self._read_exact(length)
            if opcode == 0x9:  # ping
                self._send_frame(0xA, data)
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:  # close
                raise ConnectionError(f"server closed the WebSocket: {data[2:].decode(errors='replace')}")
            if opcode != 0x0:  # first frame of a message; 0x0 is a continuation
                first_opcode = opcode
            message += data
            if fin:
                return first_opcode, message

    def close(self) -> None:
        """Close politely."""
        try:
            self._send_frame(0x8, struct.pack("!H", 1000))
        except OSError:
            pass
        self.sock.close()


class MassSocket:
    """Command/answer matching on top of WebSocket, keeping every frame for recording."""

    def __init__(self, base_url: str, timeout: float = 30) -> None:
        self.ws = WebSocket(base_url.replace("http://", "ws://", 1) + "/ws", timeout=timeout)
        self.server_info = self.ws.recv_json(timeout)
        self.events: list[dict[str, Any]] = []
        self._next_id = 1

    def command(
        self,
        command: str,
        args: dict[str, Any] | None = None,
        timeout: float = 120,
        message_id: str | None = None,
    ) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        """
        Send a command and collect its answer.

        Returns (request, frames): every result frame for this message_id in order; a large
        listing arrives as several frames with "partial": true before the final one.
        Events that arrive meanwhile go to self.events.
        """
        if message_id is None:
            message_id = str(self._next_id)
            self._next_id += 1
        request: dict[str, Any] = {"message_id": message_id, "command": command}
        if args is not None:
            request["args"] = args
        self.ws.send_json(request)
        frames: list[dict[str, Any]] = []
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"no answer to {command} within {timeout}s")
            msg = self.ws.recv_json(remaining)
            if "event" in msg and "message_id" not in msg:
                self.events.append(msg)
                continue
            if str(msg.get("message_id")) != message_id:
                continue
            frames.append(msg)
            if not msg.get("partial"):
                return request, frames

    def wait_for_event(
        self, predicate: Callable[[dict[str, Any]], bool], timeout: float = 30
    ) -> dict[str, Any] | None:
        """Return the first event (already buffered or new) the predicate accepts."""
        for evt in self.events:
            if predicate(evt):
                return evt
        deadline = time.monotonic() + timeout
        while (remaining := deadline - time.monotonic()) > 0:
            try:
                msg = self.ws.recv_json(remaining)
            except TimeoutError:
                return None
            if "event" in msg:
                self.events.append(msg)
                if predicate(msg):
                    return msg
        return None

    def close(self) -> None:
        """Close the socket."""
        self.ws.close()
