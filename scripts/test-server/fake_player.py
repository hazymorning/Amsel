#!/usr/bin/env python3
"""A fake speaker: a legacy Sendspin player on the server's /sendspin proxy that reads and drops the
audio, so playback advances in real time. --seconds 0 runs until killed; Python 3 stdlib only.

The server takes the legacy protocol while the sendspin provider's allow_legacy_clients is on.
See scripts/test-server/README.md, "Speakers on a test server".
"""

from __future__ import annotations

import argparse
import json
import os
import threading
import time
from typing import Any

from ma_client import WebSocket, log


class FakePlayer:
    def __init__(self, url: str, token: str, client_id: str, name: str, volume: int = 40) -> None:
        self.url = url.rstrip("/")
        self.token = token
        self.client_id = client_id
        self.name = name
        self.volume = volume
        self.muted = False
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.server_hello: dict[str, Any] | None = None
        self.audio_bytes = 0
        self.messages: list[dict[str, Any]] = []
        self.ws: WebSocket | None = None
        self._send_lock = threading.Lock()

    def _send(self, msg: dict[str, Any]) -> None:
        assert self.ws is not None
        with self._send_lock:
            self.ws.send_json(msg)

    def _state(self) -> dict[str, Any]:
        return {
            "type": "client/state",
            "payload": {
                "available": True,
                "player": {
                    "volume": self.volume,
                    "muted": self.muted,
                    "output_delay_ms": 0,
                    "required_lead_time_ms": 250,
                    "min_buffer_ms": 250,
                    "supported_commands": ["volume", "mute"],
                },
            },
        }

    def connect(self) -> None:
        self.ws = WebSocket(self.url.replace("http://", "ws://", 1) + "/sendspin", timeout=15)
        self._send({"type": "auth", "token": self.token, "client_id": self.client_id})
        auth = self.ws.recv_json(15)
        if auth.get("type") != "auth_ok":
            raise ConnectionError(f"sendspin proxy refused auth: {auth}")
        self._send(
            {
                "type": "client/hello",
                "payload": {
                    "client_id": self.client_id,
                    "name": self.name,
                    "version": 1,
                    "supported_roles": ["player@v1"],
                    "device_info": {
                        "product_name": "Amsel fake player",
                        "manufacturer": "Amsel",
                        "software_version": "1",
                    },
                    "player@v1_support": {
                        "supported_formats": [
                            {"codec": "pcm", "channels": 2, "sample_rate": 44100, "bit_depth": 16}
                        ],
                        "buffer_capacity": 2_000_000,
                    },
                },
            }
        )
        self.server_hello = self.ws.recv_json(15)
        log(f"fake player '{self.name}' connected: {self.server_hello.get('type')}")
        self._send(self._state())
        self._send({"type": "client/time", "payload": {"client_transmitted": _now_us()}})

    def _serve(self) -> None:
        assert self.ws is not None
        last_time = time.monotonic()
        while not self.stop_event.is_set():
            if time.monotonic() - last_time > 2:
                self._send({"type": "client/time", "payload": {"client_transmitted": _now_us()}})
                last_time = time.monotonic()
            try:
                opcode, data = self.ws.recv(1.0)
            except TimeoutError:
                continue
            except (ConnectionError, OSError) as err:
                if not self.stop_event.is_set():
                    log(f"fake player '{self.name}' disconnected: {err}")
                return
            if opcode == 0x2:
                self.audio_bytes += len(data)
                continue
            msg = json.loads(data)
            if msg.get("type") != "server/time":
                self.messages.append(msg)
            if msg.get("type") == "server/command":
                cmd = (msg.get("payload") or {}).get("player") or {}
                if cmd.get("command") == "volume" and cmd.get("volume") is not None:
                    self.volume = int(cmd["volume"])
                elif cmd.get("command") == "mute" and cmd.get("mute") is not None:
                    self.muted = bool(cmd["mute"])
                self._send(self._state())

    def start(self) -> FakePlayer:
        self.connect()
        self.thread = threading.Thread(target=self._serve, name=f"fake-{self.client_id}", daemon=True)
        self.thread.start()
        return self

    def stop(self) -> None:
        self.stop_event.set()
        if self.ws is not None:
            try:
                self._send({"type": "client/goodbye", "payload": {"reason": "shutdown"}})
            except OSError:
                pass
            self.ws.close()
        if self.thread is not None:
            self.thread.join(timeout=3)


def _now_us() -> int:
    return time.monotonic_ns() // 1000


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8095")
    parser.add_argument("--token", default=os.environ.get("MA_TOKEN"), help="or set MA_TOKEN")
    parser.add_argument("--id", default="amsel-fake-kitchen")
    parser.add_argument("--name", default="Kitchen")
    parser.add_argument("--seconds", type=float, default=0)
    args = parser.parse_args()
    if not args.token:
        parser.error("--token or MA_TOKEN is required")
    player = FakePlayer(args.url, args.token, args.id, args.name).start()
    try:
        if args.seconds > 0:
            time.sleep(args.seconds)
        else:
            while player.thread is not None and player.thread.is_alive():
                time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        player.stop()


if __name__ == "__main__":
    main()
