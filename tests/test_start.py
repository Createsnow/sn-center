"""start.py 必须能找到占用端口的本仓库进程，并以单进程优雅停机。"""

from __future__ import annotations

import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

SN_CENTER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SN_CENTER))

import start as start_mod  # noqa: E402


def test_parser_rejects_tunnel_flag() -> None:
    with pytest.raises(SystemExit):
        start_mod.build_parser().parse_args(["--tunnel"])


def test_start_module_has_no_tunnel_api() -> None:
    dests = {action.dest for action in start_mod.build_parser()._actions}
    assert "tunnel" not in dests
    assert not hasattr(start_mod, "want_tunnel")
    assert not hasattr(start_mod, "start_tunnel")
    assert not hasattr(start_mod, "start_cloudflared")
    assert not hasattr(start_mod, "ensure_cloudflared")


def test_stoppable_pids_excludes_self_and_ancestors() -> None:
    leftover = [os.getpid(), os.getppid(), 424242]
    assert start_mod.stoppable_pids(leftover) == [424242]


def test_list_demo_pids_does_not_match_test_module_name() -> None:
    proc = subprocess.Popen(
        [
            sys.executable,
            "-c",
            "import time; time.sleep(30)",
            "test_start.py",
            str(SN_CENTER),
        ]
    )
    try:
        time.sleep(0.4)
        pids = start_mod.list_demo_pids()
        assert proc.pid not in pids, pids
    finally:
        proc.kill()
        proc.wait(timeout=5)


def test_run_api_uses_inprocess_uvicorn(monkeypatch) -> None:
    called: dict = {}

    def fake_run(app, **kwargs):
        called["app"] = app
        called.update(kwargs)

    monkeypatch.setattr(start_mod.uvicorn, "run", fake_run)
    start_mod.run_api(host="0.0.0.0", port=8000)
    assert called["app"] == "app.main:app"
    assert called["host"] == "0.0.0.0"
    assert called["port"] == 8000


def test_kill_pid_tree_skips_force_if_graceful_works(monkeypatch) -> None:
    seen: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        seen.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0)

    kills: list[int] = []
    monkeypatch.setattr(start_mod.subprocess, "run", fake_run)
    monkeypatch.setattr(start_mod, "wait_pid_gone", lambda pid, timeout=8: True)
    monkeypatch.setattr(start_mod.os, "kill", lambda pid, sig: kills.append(sig))
    start_mod.kill_pid_tree(12345)
    if start_mod.IS_WIN:
        assert any("taskkill" in cmd[0] and "/F" not in cmd for cmd in seen)
        assert not any("/F" in cmd for cmd in seen)
    else:
        assert signal.SIGTERM in kills
        assert signal.SIGKILL not in kills


def test_list_demo_pids_finds_matching_python_process() -> None:
    proc = subprocess.Popen(
        [
            sys.executable,
            "-c",
            "import time; time.sleep(30)",
            "uvicorn app.main:app",
            str(SN_CENTER),
        ]
    )
    try:
        time.sleep(0.4)
        pids = start_mod.list_demo_pids()
        assert proc.pid in pids, pids
    finally:
        proc.kill()
        proc.wait(timeout=5)


def test_parse_listening_pids_from_netstat() -> None:
    text = (
        "  TCP    0.0.0.0:8000           0.0.0.0:0              LISTENING       28480\r\n"
        "  TCP    127.0.0.1:8000         0.0.0.0:0              LISTENING       24956\r\n"
        "  TCP    127.0.0.1:8000         127.0.0.1:50743        TIME_WAIT       0\r\n"
        "  TCP    0.0.0.0:8001           0.0.0.0:0              LISTENING       1\r\n"
    )
    assert start_mod.parse_listening_pids(text, 8000) == [28480, 24956]


def test_pids_listening_on_finds_bound_socket() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    sock.listen(1)
    port = sock.getsockname()[1]
    try:
        pids = start_mod.pids_listening_on(port)
        assert os.getpid() in pids, pids
    finally:
        sock.close()
