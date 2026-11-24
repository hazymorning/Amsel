#!/usr/bin/env python3
"""Records Music Assistant's answers as JSON fixtures, from a server start.sh and setup.py prepared.

The file format: scripts/test-server/README.md, "Fixtures". Python 3 stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

from fake_player import FakePlayer
from ma_client import HttpError, MassSocket, http_json, log

JWT_RE = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
PASSWORD = "amsel-test-pw"


def redact(obj: Any) -> Any:
    if isinstance(obj, str):
        return JWT_RE.sub("<redacted-jwt>", obj)
    if isinstance(obj, list):
        return [redact(x) for x in obj]
    if isinstance(obj, dict):
        return {k: redact(v) for k, v in obj.items()}
    return obj


def fixture_name(command: str, variant: str | None = None) -> str:
    name = command.replace("/", "_")
    return f"{name}__{variant}" if variant else name


class Recorder:
    def __init__(self, url: str, token: str, out: Path) -> None:
        self.url = url
        self.token = token
        self.out = out
        self.out.mkdir(parents=True, exist_ok=True)
        self.written: list[str] = []
        docs = http_json(f"{url}/api-docs/commands.json")
        self.commands = {c["command"] for c in docs}
        self.ma: MassSocket | None = None

    def save(
        self, name: str, request: Any, frames: list[dict[str, Any]] | dict[str, Any], notes: str | None = None
    ) -> Any:
        if isinstance(frames, dict):
            frames = [frames]
        doc: dict[str, Any] = {"request": request, "response": frames[-1]}
        if len(frames) > 1:
            doc["partials"] = frames[:-1]
        if notes:
            doc["notes"] = notes
        path = self.out / f"{name}.json"
        path.write_text(json.dumps(redact(doc), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        self.written.append(path.name)
        return frames[-1]

    def has(self, command: str) -> bool:
        return command in self.commands

    def rec(
        self,
        command: str,
        args: dict[str, Any] | None = None,
        variant: str | None = None,
        timeout: float = 120,
        notes: str | None = None,
        sock: MassSocket | None = None,
    ) -> Any:
        """Returns the result, or None on an error or where the server lacks the command."""
        sock = sock or self.ma
        assert sock is not None
        if command not in self.commands and command != "auth":
            log(f"  skip {command}: not offered by this server")
            return None
        request, frames = sock.command(command, args, timeout=timeout)
        final = self.save(fixture_name(command, variant), request, frames, notes)
        label = f"{command} [{variant}]" if variant else command
        if "error_code" in final:
            log(f"  {label}: error {final['error_code']} {final.get('details')}")
            return None
        result = final.get("result")
        if len(frames) > 1:  # a streamed listing: join its pages
            result = [x for f in frames for x in (f.get("result") or [])]
        log(f"  {label}: ok")
        return result


def find(items: list[dict[str, Any]], name: str) -> dict[str, Any]:
    for item in items:
        if item.get("name") == name:
            return item
    raise SystemExit(f"'{name}' not found in {[i.get('name') for i in items]}")


def wait_players(r: Recorder, names: set[str], timeout: float = 60) -> list[dict[str, Any]]:
    """Waits until the server has wrapped the fake speakers in visible universal players."""
    assert r.ma is not None
    deadline = time.monotonic() + timeout
    while True:
        _, frames = r.ma.command("players/all")
        players = frames[-1].get("result") or []
        if names <= {p["name"] for p in players if p.get("available")}:
            return players
        if time.monotonic() > deadline:
            raise SystemExit(f"fake players did not show up: {[p['name'] for p in players]}")
        time.sleep(2)


def wait_queue(r: Recorder, queue_id: str, state: str, timeout: float = 30) -> dict[str, Any]:
    assert r.ma is not None
    deadline = time.monotonic() + timeout
    while True:
        _, frames = r.ma.command("player_queues/get", {"queue_id": queue_id})
        queue = frames[-1].get("result") or {}
        if queue.get("state") == state or time.monotonic() > deadline:
            return queue
        time.sleep(1)


RADIO_NAME = "Amsel Test Radio"


def sort_args(r: Recorder, field: str, direction: str, legacy: str) -> dict[str, Any]:
    """sort_field from schema 86, order_by before. See docs/API-NOTES.md, "Versions and schema gates"."""
    if r.has("music/sort_options"):
        return {"sort_field": field, "sort_direction": direction}
    return {"order_by": legacy}


def record_radios(r: Recorder, radio_url: str | None, player_id: str | None) -> None:
    assert r.ma is not None
    paging = {"limit": 50, "offset": 0}
    r.rec("music/radios/count")
    if r.has("music/sort_options"):
        r.rec("music/sort_options", {"listing": "library_radios"}, variant="library_radios")
    empty =r.rec("music/radios/library_items", {**paging, **sort_args(r, "sort_name", "asc", "sort_name")},
                  variant="empty", notes="A fresh test server has no radio stations.")
    if empty:
        log(f"  warning: the server already holds {len(empty)} radio station(s)")
    if not radio_url:
        log("  no --radio-url: adding and playing a radio station is not recorded")
        return
    r.rec("builtin/add_radio", {"url": radio_url, "name": RADIO_NAME},
          notes="Builtin provider item; the library sync that adds it to the library runs about 1 s later.")
    deadline = time.monotonic() + 30
    while True:
        _, frames = r.ma.command("music/radios/library_items", {**paging, "search": RADIO_NAME})
        if frames[-1].get("result") or time.monotonic() > deadline:
            break
        time.sleep(1)
    radios = r.rec("music/radios/library_items",
                   {**paging, **sort_args(r, "timestamp_added", "desc", "timestamp_added_desc")}) or []
    station = find(radios, RADIO_NAME)
    r.rec("music/radios/get", {"item_id": station["item_id"], "provider_instance_id_or_domain": "library"})
    r.rec("music/item_by_uri", {"uri": station["uri"]}, variant="radio")
    if r.has("music/favorites/set_item"):
        r.rec("music/favorites/set_item", {"item": station["uri"], "favorite": True}, variant="radio")
    else:
        r.rec("music/favorites/add_item", {"item": station["uri"]}, variant="radio")
    r.rec("music/radios/library_items", {**paging, "favorite": True}, variant="favorites")
    if player_id is None:
        return
    r.rec("player_queues/play_media", {"queue_id": player_id, "media": [station["uri"]], "option": "replace"},
          variant="radio", timeout=180, notes="A radio station plays through play_media like any other uri.")
    wait_queue(r, player_id, "playing")
    time.sleep(3)
    r.rec("player_queues/get", {"queue_id": player_id}, variant="radio")
    r.rec("players/get", {"player_id": player_id}, variant="radio")
    r.rec("player_queues/stop", {"queue_id": player_id}, variant="radio")


def record_radios_only(r: Recorder, url: str, token: str, radio_url: str | None) -> None:
    r.ma = MassSocket(url)
    r.ma.command("auth", {"token": token})
    kitchen = FakePlayer(url, token, "amsel-fake-kitchen", "Kitchen").start()
    try:
        log("waiting for the fake speaker (about 15 s)...")
        players = wait_players(r, {"Kitchen"})
        record_radios(r, radio_url, find(players, "Kitchen")["player_id"])
    finally:
        kitchen.stop()
    r.ma.close()
    meta_path = r.out / "_meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    meta["files"] = sorted(set(meta["files"]) | set(r.written))
    meta["radios_recorded_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    log(f"wrote {len(r.written)} radio fixtures to {r.out}")


def main() -> None:  # noqa: PLR0915
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8095")
    parser.add_argument("--token", default=os.environ.get("MA_TOKEN"))
    parser.add_argument("--out", required=True)
    parser.add_argument("--radio-url", help="a stream URL the server can reach; records a radio station")
    parser.add_argument("--only", choices=["radios"], help="record only this section into an existing --out")
    args = parser.parse_args()
    if not args.token:
        raise SystemExit("--token or MA_TOKEN is required")
    url = args.url.rstrip("/")
    r = Recorder(url, args.token, Path(args.out))
    if args.only == "radios":
        record_radios_only(r, url, args.token, args.radio_url)
        return

    info = http_json(f"{url}/info")
    r.save("http_info", {"method": "GET", "path": "/info"}, info,
           notes="Unauthenticated HTTP endpoint; same payload as the server_info WebSocket message.")
    log(f"recording {info['server_version']} (schema {info['schema_version']}) into {r.out}")

    pre = MassSocket(url)
    r.save("server_info", None, pre.server_info,
           notes="First frame the server sends on every new /ws connection, before any command.")
    r.rec("auth/providers", sock=pre, notes="Unauthenticated: login providers for the sign-in screen.")
    r.rec("players/all", variant="unauthenticated", sock=pre,
          notes="Any authenticated command sent before 'auth' fails like this.")
    r.rec("auth/login", {"username": "amsel", "password": "wrong-password", "device_name": "Amsel fixtures"},
          variant="wrong_password", sock=pre)
    r.rec("auth/login", {"username": "amsel", "password": PASSWORD, "device_name": "Amsel fixtures"},
          sock=pre, notes="Unauthenticated command; returns a short-lived (auto-renewing) access_token.")
    r.rec("auth", {"token": "not-a-valid-token"}, variant="invalid_token", sock=pre)
    pre.close()

    r.ma = ma = MassSocket(url)
    r.rec("auth", {"token": args.token},
          notes="'auth' is handled by the WebSocket handler itself; it is not in /api-docs. "
                "Events start flowing to this connection after it succeeds.")
    r.rec("info")
    r.rec("auth/me")
    r.rec("auth/scopes")
    r.rec("auth/token/create", {"name": "Amsel fixtures"},
          notes="Long-lived token (1 year, no auto-renew) for the current user.")
    r.rec("providers", notes="Loaded provider instances, e.g. to name the music service in provider_mappings.")
    r.rec("players/all", variant="no_players",
          notes="A fresh test server has no speakers at all: no built-in web player exists until a "
                "browser or Sendspin client connects.")
    r.rec("player_queues/all", variant="no_players")

    paging = {"limit": 50, "offset": 0}
    artists = r.rec("music/artists/library_items", paging)
    albums = r.rec("music/albums/library_items", paging)
    playlists = r.rec("music/playlists/library_items", paging)
    r.rec("music/tracks/library_items", paging)
    for media in ("artists", "albums", "playlists", "tracks"):
        r.rec(f"music/{media}/count")
    if r.has("music/sort_options"):
        for listing in ("library_albums", "library_artists", "library_playlists",
                        "album_tracks", "artist_albums", "artist_tracks", "playlist_tracks"):
            r.rec("music/sort_options", {"listing": listing}, variant=listing)
        r.rec("music/albums/library_items", {**paging, "sort_field": "timestamp_added", "sort_direction": "desc"},
              variant="recently_added")
        r.rec("music/albums/library_items", {**paging, "sort_field": "sort_name", "sort_direction": "asc"},
              variant="a_to_z")
        r.rec("music/albums/library_items", {**paging, "sort_field": "last_played", "sort_direction": "desc"},
              variant="recently_played")
    else:
        r.rec("music/albums/library_items", {**paging, "order_by": "timestamp_added_desc"},
              variant="recently_added", notes="2.10 has no music/sort_options; order_by takes a field name "
                                               "with an optional _desc suffix.")
        r.rec("music/albums/library_items", {**paging, "order_by": "sort_name"}, variant="a_to_z")
        r.rec("music/albums/library_items", {**paging, "order_by": "last_played_desc"}, variant="recently_played")
    r.rec("music/albums/library_items", {"limit": 2, "offset": 2}, variant="page2_limit2")
    r.rec("music/albums/library_items", {**paging, "search": "light"}, variant="search")
    r.rec("music/recently_added_tracks", {"limit": 10})

    album = find(albums, "First Light")
    lib = "library"
    r.rec("music/albums/get", {"item_id": album["item_id"], "provider_instance_id_or_domain": lib})
    r.rec("music/albums/album_tracks", {"item_id": album["item_id"], "provider_instance_id_or_domain": lib})
    artist = find(artists, "Aurora Tone")
    r.rec("music/artists/get", {"item_id": artist["item_id"], "provider_instance_id_or_domain": lib})
    r.rec("music/artists/artist_albums", {"item_id": artist["item_id"], "provider_instance_id_or_domain": lib})
    r.rec("music/artists/artist_tracks", {"item_id": artist["item_id"], "provider_instance_id_or_domain": lib})
    playlist = find(playlists, "Amsel Mix")
    r.rec("music/playlists/get", {"item_id": playlist["item_id"], "provider_instance_id_or_domain": lib})
    r.rec("music/playlists/playlist_tracks", {"item_id": playlist["item_id"], "provider_instance_id_or_domain": lib})
    r.rec("music/item_by_uri", {"uri": album["uri"]})
    r.rec("music/albums/get", {"item_id": "999999", "provider_instance_id_or_domain": lib}, variant="not_found")

    r.rec("music/search", {"search_query": "aurora", "limit": 5})
    r.rec("music/search", {"search_query": "air", "media_types": ["track"], "limit": 10}, variant="tracks_only")
    r.rec("music/search", {"search_query": "zzzz-no-match", "limit": 5}, variant="no_results")
    r.rec("music/recently_played_items", {"limit": 6, "media_types": ["album", "playlist", "artist"]},
          variant="before_playback")

    kitchen = FakePlayer(url, args.token, "amsel-fake-kitchen", "Kitchen").start()
    living = FakePlayer(url, args.token, "amsel-fake-living-room", "Living Room").start()
    try:
        log("waiting for the fake speakers to be wrapped as universal players (about 15 s)...")
        wait_players(r, {"Kitchen", "Living Room"})
        players = r.rec("players/all", notes="Two fake Sendspin speakers (scripts/test-server/fake_player.py). "
                                            "player_id is the universal player id, random per server.")
        k_id = find(players, "Kitchen")["player_id"]
        l_id = find(players, "Living Room")["player_id"]
        r.rec("players/all", {"return_protocol_players": True}, variant="with_protocol_players")
        r.rec("players/get", {"player_id": k_id})
        r.rec("player_queues/all")
        r.rec("player_queues/get", {"queue_id": k_id}, variant="idle")
        r.rec("player_queues/items", {"queue_id": k_id, "limit": 50, "offset": 0}, variant="empty")

        t0 = time.monotonic()
        r.rec("player_queues/play_media", {"queue_id": k_id, "media": album["uri"], "option": "replace"},
              timeout=180, notes="Answered after playback started.")
        log(f"  play_media answered after {time.monotonic() - t0:.1f}s")
        wait_queue(r, k_id, "playing")
        time.sleep(3)
        r.rec("player_queues/get", {"queue_id": k_id}, variant="playing")
        r.rec("players/get", {"player_id": k_id}, variant="playing")
        r.rec("player_queues/items", {"queue_id": k_id, "limit": 50, "offset": 0})
        tracks = r.rec("music/playlists/playlist_tracks",
                       {"item_id": playlist["item_id"], "provider_instance_id_or_domain": lib}, variant="again") or []
        if tracks:
            r.rec("player_queues/play_media", {"queue_id": k_id, "media": tracks[0]["uri"], "option": "next"},
                  variant="option_next")
            r.rec("player_queues/play_media", {"queue_id": k_id, "media": [t["uri"] for t in tracks[1:3]],
                                               "option": "add"}, variant="option_add_list")
        r.rec("player_queues/items", {"queue_id": k_id, "limit": 50, "offset": 0}, variant="after_add")
        r.rec("players/cmd/volume_set", {"player_id": k_id, "volume_level": 25})
        r.rec("player_queues/pause", {"queue_id": k_id})
        wait_queue(r, k_id, "paused", 10)
        r.rec("player_queues/get", {"queue_id": k_id}, variant="paused")
        r.rec("player_queues/play", {"queue_id": k_id})
        r.rec("player_queues/next", {"queue_id": k_id})
        r.rec("player_queues/seek", {"queue_id": k_id, "position": 5})
        r.rec("player_queues/shuffle", {"queue_id": k_id, "shuffle_enabled": False})
        r.rec("player_queues/repeat", {"queue_id": k_id, "repeat_mode": "off"})
        r.rec("player_queues/transfer", {"source_queue_id": k_id, "target_queue_id": l_id, "auto_play": True},
              timeout=180, notes="'Play on': moves the queue (items and position) to another speaker.")
        wait_queue(r, l_id, "playing")
        time.sleep(2)
        r.rec("player_queues/get", {"queue_id": l_id}, variant="after_transfer_target")
        r.rec("player_queues/items", {"queue_id": l_id, "limit": 50, "offset": 0}, variant="after_transfer_target")
        r.rec("player_queues/get", {"queue_id": k_id}, variant="after_transfer_source")
        r.rec("player_queues/play_media", {"queue_id": k_id, "media": playlist["uri"], "option": "replace"},
              variant="playlist_on_second_speaker", timeout=180)
        time.sleep(2)
        r.rec("player_queues/stop", {"queue_id": k_id})
        r.rec("player_queues/stop", {"queue_id": l_id}, variant="second_speaker")
        r.rec("music/recently_played_items", {"limit": 6, "media_types": ["album", "playlist", "artist"]},
              notes="fully_played_only defaults to true: an album started a few seconds ago is not listed.")
        r.rec("music/recently_played_items", {"limit": 6, "media_types": ["album", "playlist", "artist"],
                                              "fully_played_only": False}, variant="include_partial")

        if r.has("music/favorites/set_item"):
            r.rec("music/favorites/set_item", {"item": album["uri"], "favorite": True},
                  notes="2.11: per-user like (true), dislike (false) or clear (null).")
            r.rec("music/favorites/set_item", {"item": tracks[0]["uri"] if tracks else album["uri"], "favorite": False},
                  variant="dislike")
        else:
            r.rec("music/favorites/add_item", {"item": album["uri"]})
        r.rec("music/albums/library_items", {**paging, "favorite": True}, variant="favorites")
        time.sleep(2)

        record_radios(r, args.radio_url, k_id)
    finally:
        kitchen.stop()
        living.stop()

    # Takes in the events still on their way: the predicate accepts none.
    ma.wait_for_event(lambda e: False, timeout=2)
    by_type: dict[str, list[dict[str, Any]]] = {}
    for evt in ma.events:
        by_type.setdefault(evt["event"], []).append(evt)
    for etype, evts in sorted(by_type.items()):
        playing = [e for e in evts if '"playing"' in json.dumps(e)]
        r.save(f"event_{etype}", None, (playing or evts)[0],
               notes=f"Server-pushed event; {len(evts)} of this type arrived during the recording.")
    ma.close()

    meta = {
        "server_version": info["server_version"],
        "schema_version": info["schema_version"],
        "min_supported_schema_version": info["min_supported_schema_version"],
        "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "recorded_with": "scripts/test-server/record_fixtures.py",
        "library": "scripts/test-server/make-library.sh (3 artists, 4 albums, 12 tracks, 1 m3u playlist)",
        "event_counts": {k: len(v) for k, v in sorted(by_type.items())},
        "files": sorted(r.written),
    }
    (r.out / "_meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    log(f"wrote {len(r.written)} fixtures + _meta.json to {r.out}")


if __name__ == "__main__":
    try:
        main()
    except HttpError as err:
        sys.exit(str(err))
