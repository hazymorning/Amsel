#!/usr/bin/env python3
"""Onboards a fresh test server over HTTP and prints a long-lived API token, the only output on stdout.

The steps: scripts/test-server/README.md, "How the headless setup works". Python 3 stdlib only.
"""

from __future__ import annotations

import argparse
import sys
import time
from typing import Any

from ma_client import HttpError, http_json, log, rpc

USERNAME = "amsel"
PASSWORD = "amsel-test-pw"
DISPLAY_NAME = "Amsel Test"
DEVICE_NAME = "amsel-test-setup"
MEDIA_PATH = "/media"
FINISHED = {"success", "partial_success", "failed", "cancelled", "idle"}
TEST_KINDS = ("artists", "albums", "tracks", "podcasts", "audiobooks")
TEST_HOLDS_NOTHING: dict[str, Any] = {f"num_{kind}": 0 for kind in TEST_KINDS} | {
    f"library_sync_{kind}": False for kind in TEST_KINDS
}


def wait_running(url: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while True:
        try:
            info = http_json(f"{url}/info", timeout=5)
            if info.get("status") == "running":
                return info
        except (OSError, HttpError, ValueError):
            pass
        if time.monotonic() > deadline:
            raise SystemExit(f"server at {url} not running after {timeout}s")
        time.sleep(1)


def session_token(url: str) -> str:
    """Creates the first admin, or logs in once one exists."""
    try:
        res = http_json(
            f"{url}/setup",
            {
                "username": USERNAME,
                "password": PASSWORD,
                "display_name": DISPLAY_NAME,
                "device_name": DEVICE_NAME,
            },
        )
        log(f"created admin user '{USERNAME}' via POST /setup")
        return res["token"]
    except HttpError as err:
        # 400 or 409 by version once a user exists. See scripts/test-server/README.md, "How the headless setup works".
        if err.status not in (400, 409) or "already" not in err.body.lower():
            raise
    res = http_json(
        f"{url}/auth/login",
        {
            "provider_id": "builtin",
            "credentials": {"username": USERNAME, "password": PASSWORD},
            "device_name": DEVICE_NAME,
        },
    )
    if not res.get("success"):
        raise SystemExit(f"login failed: {res}")
    log(f"server already onboarded; logged in as '{USERNAME}' via POST /auth/login")
    return res["token"]


def ensure_filesystem_provider(url: str, token: str) -> str:
    for conf in rpc(url, token, "config/providers"):
        if conf.get("domain") == "filesystem_local":
            log(f"filesystem_local already configured: {conf['instance_id']}")
            return conf["instance_id"]
    step = rpc(url, token, "config/providers/setup", provider_domain="filesystem_local")
    for _ in range(5):
        step_type = step.get("type")
        if step_type == "finish":
            instance_id = (step.get("result") or {}).get("instance_id")
            log(f"added filesystem_local provider {instance_id} for {MEDIA_PATH}")
            return instance_id
        if step_type == "abort":
            raise SystemExit(f"provider setup aborted: {step}")
        if step_type == "form":
            keys = {entry["key"] for entry in step.get("entries", [])}
            values: dict[str, Any] = {}
            if "content_type" in keys:
                values["content_type"] = "music"
            if "path" in keys:
                values["path"] = MEDIA_PATH
            if "overlap_choice" in keys:  # 2.11: folder shared with another source
                values["overlap_choice"] = "use_folder"
            if step.get("errors"):
                raise SystemExit(f"provider setup form rejected: {step['errors']}")
            step = rpc(url, token, "config/flows/submit", flow_id=step["flow_id"], values=values)
            continue
        # progress or other non-terminal steps: re-read the current step
        time.sleep(1)
        step = rpc(url, token, "config/flows/get", flow_id=step["flow_id"])
    raise SystemExit(f"provider setup did not finish: {step}")


def sync_library(url: str, token: str, instance_id: str, timeout: float) -> None:
    tasks = rpc(url, token, "music/sync", providers=[instance_id])
    ids = [t["id"] for t in tasks]
    log(f"started {len(ids)} sync task(s): {', '.join(t['name'] for t in tasks)}")
    deadline = time.monotonic() + timeout
    while ids:
        pending = []
        for task_id in ids:
            try:
                task = rpc(url, token, "tasks/get", task_id=task_id)
            except HttpError:
                continue  # finished ad hoc tasks may be pruned
            if task.get("status") not in FINISHED or task.get("finished_at") is None:
                pending.append(task_id)
            elif task.get("status") == "failed":
                raise SystemExit(f"sync task failed: {task.get('name')}: {task.get('last_error')}")
        ids = pending
        if ids and time.monotonic() > deadline:
            raise SystemExit("library sync timed out")
        if ids:
            time.sleep(1)


def ensure_test_provider(url: str, token: str) -> None:
    """The server's "test" music service answers similar songs offline. See docs/API-NOTES.md, "Test server recipe"."""
    instance_id = next(
        (conf["instance_id"] for conf in rpc(url, token, "config/providers") if conf.get("domain") == "test"),
        None,
    )
    if instance_id is None:
        step = rpc(url, token, "config/providers/setup", provider_domain="test")
        if step.get("type") != "finish":
            raise SystemExit(f"test provider setup did not finish: {step}")
        instance_id = (step.get("result") or {}).get("instance_id")
        log(f"added test provider {instance_id}")
    # Emptied, or its first sync fills the library (docs/API-NOTES.md, "Test server recipe"); saved on
    # every run, so a run that stopped in between is repaired.
    rpc(
        url,
        token,
        "config/providers/save",
        provider_domain="test",
        instance_id=instance_id,
        values=TEST_HOLDS_NOTHING,
    )
    log(f"test provider {instance_id} holds nothing and syncs nothing")


def library_count(url: str, token: str, media: str) -> int:
    return len(rpc(url, token, f"music/{media}/library_items", limit=500))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8095")
    parser.add_argument("--timeout", type=float, default=300)
    parser.add_argument("--expect-tracks", type=int, default=12)
    parser.add_argument("--token-file", help="also write the token to this file")
    args = parser.parse_args()
    url = args.url.rstrip("/")

    info = wait_running(url, args.timeout)
    log(f"server {info['server_version']} schema {info['schema_version']} onboard_done={info['onboard_done']}")
    token = rpc(url, session_token(url), "auth/token/create", name="amsel-test")
    instance_id = ensure_filesystem_provider(url, token)
    sync_library(url, token, instance_id, args.timeout)
    # Sync tasks report done slightly before the last library writes land.
    deadline = time.monotonic() + 60
    while (tracks := library_count(url, token, "tracks")) < args.expect_tracks:
        if time.monotonic() > deadline:
            raise SystemExit(f"library has {tracks} tracks, expected {args.expect_tracks}")
        time.sleep(1)
    ensure_test_provider(url, token)
    counts = {m: library_count(url, token, m) for m in ("artists", "albums", "tracks", "playlists")}
    log(f"library ready: {counts}")
    if args.token_file:
        with open(args.token_file, "w", encoding="utf-8") as fh:
            fh.write(token + "\n")
    print(token)


if __name__ == "__main__":
    sys.exit(main())
