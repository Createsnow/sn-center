"""读取 sn-center/.env。不依赖第三方库，启动脚本和金蝶查询都能用。"""

from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT / ".env"


def read_env_file(path: Path | None = None) -> dict[str, str]:
    file_path = path or ENV_FILE
    if not file_path.is_file():
        return {}
    data: dict[str, str] = {}
    for raw in file_path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        if "=" not in line:
            continue
        key, _, val = line.partition("=")
        key = key.strip()
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in {'"', "'"}:
            val = val[1:-1]
        if key:
            data[key] = val
    return data


def lookup(key: str, default: str | None = None, path: Path | None = None) -> str | None:
    """进程环境变量优先，其次 .env，最后用调用方给出的默认值。"""
    if key in os.environ:
        return os.environ[key]
    data = read_env_file(path)
    if key in data:
        return data[key]
    return default


def lookup_int(key: str, default: int, path: Path | None = None) -> int:
    raw = lookup(key, None, path)
    if raw is None or not str(raw).strip():
        return default
    try:
        return int(str(raw).strip())
    except ValueError:
        return default
