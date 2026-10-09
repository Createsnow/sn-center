"""一键启动：构建前端、单端口对外。公网入口在网关，不在本脚本。"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import venv
import webbrowser
from pathlib import Path

from app.core.config import Settings
from app.core.envfile import lookup, lookup_int

try:
    import uvicorn
except ImportError:  # pragma: no cover - 首次用系统 Python 拉起时尚未装依赖
    uvicorn = None

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT
FRONTEND = ROOT / "frontend"
VENV = ROOT / ".venv"
TOOLS = ROOT / "tools"
RUNTIME = TOOLS / "runtime.json"
IS_WIN = os.name == "nt"
# Windows 上 text=True 默认 GBK；netsh / Go / Node 常输出 UTF-8，未指定 errors 会崩。
_TEXT = dict(text=True, encoding="utf-8", errors="replace")
_LISTEN_PID_RE = re.compile(r":(\d+)\s+\S+\s+LISTENING\s+(\d+)", re.I)
_START_PY_RE = re.compile(r"\bstart\.py\b", re.I)


def venv_python() -> Path:
    return VENV / ("Scripts/python.exe" if IS_WIN else "bin/python")


def npm_cmd() -> str:
    return "npm.cmd" if IS_WIN else "npm"


def run(cmd: list[str], cwd: Path, env: dict | None = None) -> None:
    print(f"\n>> {' '.join(cmd)}  ({cwd})")
    subprocess.check_call(cmd, cwd=str(cwd), env=env)


def ensure_backend() -> None:
    if not VENV.exists():
        print("创建 Python 虚拟环境…")
        venv.EnvBuilder(with_pip=True).create(VENV)
    py = str(venv_python())
    probe = subprocess.run(
        [py, "-c", "import fastapi, uvicorn"],
        capture_output=True,
        **_TEXT,
    )
    if probe.returncode != 0:
        run([py, "-m", "pip", "install", "-U", "pip"], ROOT)
        run([py, "-m", "pip", "install", "-e", "."], ROOT)


def _frontend_source_newer(dist_index: Path) -> bool:
    """dist 比 src 旧时，普通启动也要重新构建，否则页面仍是上一次打包结果。"""
    built = dist_index.stat().st_mtime
    src = FRONTEND / "src"
    if not src.exists():
        return False
    for path in src.rglob("*"):
        if path.is_file() and path.stat().st_mtime > built:
            return True
    return False


def ensure_frontend(rebuild: bool) -> None:
    if not (FRONTEND / "node_modules").exists():
        run([npm_cmd(), "install"], FRONTEND)
    dist = FRONTEND / "dist" / "index.html"
    if rebuild or not dist.exists() or _frontend_source_newer(dist):
        run([npm_cmd(), "run", "build"], FRONTEND)


def open_firewall(port: int) -> None:
    if not IS_WIN:
        return
    try:
        subprocess.run(
            [
                "netsh",
                "advfirewall",
                "firewall",
                "add",
                "rule",
                "name=SN Center Demo",
                "dir=in",
                "action=allow",
                "protocol=TCP",
                f"localport={port}",
            ],
            check=False,
            capture_output=True,
        )
    except Exception:
        pass


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if IS_WIN:
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            capture_output=True,
            **_TEXT,
        )
        text = out.stdout or ""
        if "No tasks" in text or "没有运行的任务" in text:
            return False
        return str(pid) in text
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def wait_pid_gone(pid: int, timeout: float = 8) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not pid_alive(pid):
            return True
        time.sleep(0.2)
    return not pid_alive(pid)


def kill_pid_tree(pid: int) -> None:
    if pid <= 0:
        return
    if IS_WIN:
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T"],
            check=False,
            capture_output=True,
        )
        if wait_pid_gone(pid, timeout=8):
            return
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            check=False,
            capture_output=True,
        )
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        return
    if wait_pid_gone(pid, timeout=8):
        return
    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        pass


def _powershell(script: str) -> subprocess.CompletedProcess[str]:
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    return subprocess.run(
        ["powershell", "-NoProfile", "-EncodedCommand", encoded],
        capture_output=True,
        **_TEXT,
    )


def parse_listening_pids(text: str, port: int) -> list[int]:
    pids: list[int] = []
    seen: set[int] = set()
    for match in _LISTEN_PID_RE.finditer(text):
        if int(match.group(1)) != port:
            continue
        pid = int(match.group(2))
        if pid <= 0 or pid in seen:
            continue
        seen.add(pid)
        pids.append(pid)
    return pids


def pids_listening_on(port: int) -> list[int]:
    if IS_WIN:
        out = subprocess.run(
            ["netstat", "-ano", "-p", "TCP"],
            capture_output=True,
            **_TEXT,
        )
        return parse_listening_pids(out.stdout, port)
    try:
        out = subprocess.run(
            ["lsof", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"],
            capture_output=True,
            **_TEXT,
        )
    except OSError:
        return []
    pids: list[int] = []
    for line in out.stdout.split():
        if line.isdigit():
            pids.append(int(line))
    return pids


def port_can_bind(port: int) -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if IS_WIN and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind(("0.0.0.0", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def wait_port_free(port: int, timeout: float = 20) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if port_can_bind(port):
            return True
        time.sleep(0.25)
    return port_can_bind(port)


def _dedupe_pids(pids: list[int]) -> list[int]:
    seen: set[int] = set()
    unique: list[int] = []
    for pid in pids:
        if pid not in seen:
            seen.add(pid)
            unique.append(pid)
    return unique


def parent_pid(pid: int) -> int:
    if pid <= 0:
        return 0
    if pid == os.getpid():
        return os.getppid()
    if IS_WIN:
        out = _powershell(
            f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').ParentProcessId"
        )
        line = (out.stdout or "").strip()
        return int(line) if line.isdigit() else 0
    try:
        stat = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        close = stat.rfind(")")
        return int(stat[close + 2 :].split()[1])
    except (OSError, IndexError, ValueError):
        return 0


def protected_pids() -> set[int]:
    """当前进程及其祖先。停机绝不能 taskkill /T 这棵树上的节点。"""
    found: set[int] = set()
    pid = os.getpid()
    while pid > 0 and pid not in found:
        found.add(pid)
        pid = parent_pid(pid)
    return found


def stoppable_pids(pids: list[int]) -> list[int]:
    protected = protected_pids()
    return [pid for pid in _dedupe_pids(pids) if pid not in protected]


def _ps_single_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def list_demo_pids() -> list[int]:
    """本仓库的 uvicorn / start.py，以及历史残留的 cloudflared，不含当前 stop 进程。"""
    marker = str(ROOT).replace("/", "\\") if IS_WIN else str(ROOT)
    self_pid = os.getpid()
    pids: list[int] = []
    if IS_WIN:
        ps = (
            "$root = [regex]::Escape("
            + _ps_single_quote(marker)
            + "); "
            "Get-CimInstance Win32_Process | Where-Object { "
            "$_.CommandLine -and $_.CommandLine -match $root -and ("
            "($_.Name -match 'python' -and $_.CommandLine -match 'uvicorn app.main:app') -or "
            "($_.Name -match 'cloudflared') -or "
            "($_.Name -match 'python' -and $_.CommandLine -match '\\bstart\\.py' "
            "-and $_.CommandLine -notmatch '--stop' -and $_.CommandLine -notmatch 'py_compile')"
            ") } | Select-Object -ExpandProperty ProcessId"
        )
        out = _powershell(ps)
        for line in out.stdout.splitlines():
            line = line.strip()
            if line.isdigit():
                pid = int(line)
                if pid != self_pid:
                    pids.append(pid)
        return stoppable_pids(pids)

    try:
        out = subprocess.run(
            ["ps", "-ax", "-ww", "-o", "pid=,command="],
            capture_output=True,
            **_TEXT,
        )
    except OSError:
        return pids
    for line in out.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        pid_s, _, cmd = line.partition(" ")
        if not pid_s.isdigit():
            continue
        pid = int(pid_s)
        if pid == self_pid:
            continue
        if marker not in cmd:
            continue
        if "uvicorn app.main:app" in cmd or "cloudflared" in cmd or (
            _START_PY_RE.search(cmd) and "--stop" not in cmd and "py_compile" not in cmd
        ):
            pids.append(pid)
    return stoppable_pids(pids)


def stop_demo(announce: bool = True, port: int | None = None) -> int:
    pids = []
    runtime_port = None
    if RUNTIME.exists():
        try:
            data = json.loads(RUNTIME.read_text(encoding="utf-8"))
            for key in ("start_pid", "api_pid", "tunnel_pid"):
                pid = int(data.get(key) or 0)
                if pid:
                    pids.append(pid)
            runtime_port = int(data.get("port") or 0) or None
        except (OSError, ValueError, json.JSONDecodeError):
            pass
    listen_port = port or runtime_port or 8000
    pids.extend(list_demo_pids())
    pids.extend(pids_listening_on(listen_port))
    unique = stoppable_pids(pids)
    if not unique and port_can_bind(listen_port):
        try:
            RUNTIME.unlink(missing_ok=True)
        except OSError:
            pass
        if announce:
            print("没有正在运行的 SN Demo。")
        return 0
    if announce:
        print(f"正在统一关闭 SN Demo（{len(unique)} 个进程）…")
    deadline = time.time() + 8
    while time.time() < deadline:
        for pid in unique:
            kill_pid_tree(pid)
        time.sleep(0.35)
        unique = stoppable_pids(list_demo_pids() + pids_listening_on(listen_port))
        if not unique and port_can_bind(listen_port):
            break
    try:
        RUNTIME.unlink(missing_ok=True)
    except OSError:
        pass
    leftover = stoppable_pids(list_demo_pids() + pids_listening_on(listen_port))
    if leftover or not wait_port_free(listen_port, timeout=8):
        print(
            f"仍有残留 PID：{leftover or pids_listening_on(listen_port)}，端口 {listen_port} 未释放",
            file=sys.stderr,
        )
        return 1
    if announce:
        print("已关闭：本机服务已停止。")
    return 0


def save_runtime(*, api_pid: int, port: int) -> None:
    TOOLS.mkdir(parents=True, exist_ok=True)
    payload = {
        "start_pid": os.getpid(),
        "api_pid": api_pid,
        "port": port,
    }
    RUNTIME.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="启动或关闭 SN 发号中心 Demo")
    parser.add_argument(
        "--local",
        action="store_true",
        help="兼容旧用法，无效果（本机启动不再打隧道）",
    )
    parser.add_argument("--rebuild", action="store_true", help="强制重新构建前端")
    parser.add_argument("--port", type=int, default=lookup_int("SN_PORT", 8000))
    parser.add_argument("--stop", action="store_true", help="统一关闭本仓库的本机服务")
    return parser


def reexec_in_venv() -> None:
    target = venv_python()
    if not target.exists():
        return
    if Path(sys.executable).resolve() == target.resolve():
        return
    os.environ.setdefault("PYTHONUTF8", "1")
    os.environ["PYTHONPATH"] = str(ROOT)
    os.execv(str(target), [str(target), str(Path(__file__).resolve()), *sys.argv[1:]])


def run_api(*, host: str, port: int) -> None:
    if uvicorn is None:
        raise RuntimeError("uvicorn 未安装，请先创建虚拟环境")
    uvicorn.run("app.main:app", host=host, port=port)


def main() -> None:
    args = build_parser().parse_args()

    if args.stop:
        raise SystemExit(stop_demo(port=args.port))

    os.environ.setdefault("PYTHONUTF8", "1")
    leftover = list_demo_pids()
    busy = pids_listening_on(args.port)
    if leftover or busy or RUNTIME.exists() or not port_can_bind(args.port):
        print("发现已有实例，先统一关闭再启动…")
        stop_demo(announce=False, port=args.port)
    if not wait_port_free(args.port, timeout=20):
        holders = pids_listening_on(args.port)
        print(
            f"端口 {args.port} 仍被占用（PID {holders}），无法启动。可换端口：python start.py --port 8001",
            file=sys.stderr,
        )
        raise SystemExit(1)
    ensure_backend()
    reexec_in_venv()
    ensure_frontend(args.rebuild)
    open_firewall(args.port)
    os.environ["PYTHONPATH"] = str(ROOT)

    print("\n========================================")
    print(f"  本机    http://127.0.0.1:{args.port}")
    print(f"  局域网  http://<本机IP>:{args.port}")
    print("  公网    未启用（入口在网关：Docker 路径为 nginx）")
    print(f"  文档    http://127.0.0.1:{args.port}/docs")
    admin = lookup("SN_INIT_ADMIN_EMP_NO", "admin") or "admin"
    print(f"  账号    {admin} / {lookup('SN_INIT_ADMIN_PASSWORD', 'Admin@123')}（.env 配置，空库首次启动创建）")
    t = Settings().db_target()
    print(f"  数据库  {t.user}@{t.host}:{t.port}/{t.database}")
    print("  退出    本窗口 Ctrl+C，或另开窗口 python start.py --stop / stop.bat")
    print("========================================\n")

    save_runtime(api_pid=os.getpid(), port=args.port)

    time.sleep(0.4)
    try:
        webbrowser.open(f"http://127.0.0.1:{args.port}")
    except Exception:
        pass

    try:
        run_api(host=lookup("SN_HOST", "0.0.0.0") or "0.0.0.0", port=args.port)
    except KeyboardInterrupt:
        print("\n正在停止…")
    finally:
        stop_demo(announce=False, port=args.port)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        print(f"启动失败：{exc}", file=sys.stderr)
        sys.exit(exc.returncode)
