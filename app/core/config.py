"""运行参数只从环境变量与仓库根目录 .env 读取（见 .env.example）。

进程环境变量优先，其次 .env；每次读取都看当前环境（测试会在运行中改环境变量）。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.core.envfile import ROOT, read_env_file

DEFAULT_CORS = [
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:8000",
    "http://127.0.0.1:8000",
]


def resolve_root() -> Path:
    """仓库根目录：SN_ROOT 优先（容器里为 /app），否则按代码位置（app/core → 仓库根）。"""
    override = os.environ.get("SN_ROOT", "").strip()
    return Path(override).resolve() if override else ROOT


@dataclass(frozen=True)
class DbTarget:
    host: str
    port: int
    database: str
    user: str
    password: str


class Settings:
    def __init__(self) -> None:
        self.root = resolve_root()
        self._dotenv = read_env_file(self.root / ".env")

    # ------------------------------------------------------------------ 原始读取

    def raw(self, key: str) -> str | None:
        if key in os.environ:
            return os.environ[key]
        return self._dotenv.get(key)

    def get(self, key: str, fallback: str) -> str:
        v = self.raw(key)
        return fallback if v is None else v.strip()

    def get_int(self, key: str, fallback: int) -> int:
        v = self.raw(key)
        if v is None or not v.strip():
            return fallback
        try:
            return int(v.strip())
        except ValueError:
            return fallback

    def get_bool(self, key: str, fallback: bool) -> bool:
        v = self.raw(key)
        if v is None or not v.strip():
            return fallback
        s = v.strip().lower()
        if s in ("1", "true", "yes", "on", "y"):
            return True
        if s in ("0", "false", "no", "off", "n"):
            return False
        return fallback

    # ------------------------------------------------------------------ 业务参数

    @property
    def app_name(self) -> str:
        return self.get("APP_NAME", "多工厂 SN 防重管控")

    @property
    def production(self) -> bool:
        return self.get("ENVIRONMENT", "local") == "production"

    @property
    def docs_enabled(self) -> bool:
        return self.get_bool("DOCS_ENABLED", True)

    @property
    def secret_key(self) -> str:
        return self.get("SN_SECRET", "change-me-sn-center-secret")

    @property
    def access_token_ttl_seconds(self) -> int:
        return self.get_int("ACCESS_TOKEN_TTL_SECONDS", 12 * 3600)

    @property
    def init_admin_password(self) -> str:
        return self.get("SN_INIT_ADMIN_PASSWORD", "Admin@123")

    @property
    def demo_seed(self) -> bool:
        return self.get_bool("SN_DEMO_SEED", False)

    @property
    def audit_retention_days(self) -> int:
        return max(0, self.get_int("SN_AUDIT_RETENTION_DAYS", 1095))

    @property
    def audit_export_limit(self) -> int:
        return self.get_int("AUDIT_EXPORT_LIMIT", 200_000)

    @property
    def sn_export_limit(self) -> int:
        return self.get_int("SN_EXPORT_LIMIT", 1_000_000)

    @property
    def gen_sync_threshold(self) -> int:
        return self.get_int("SN_GEN_SYNC_THRESHOLD", 5000)

    @property
    def preview_ttl_minutes(self) -> int:
        return self.get_int("SN_PREVIEW_TTL_MINUTES", 30)

    @property
    def acquire_require_snapshot(self) -> bool:
        return self.get_bool("SN_ACQUIRE_REQUIRE_SNAPSHOT", True)

    @property
    def import_max(self) -> int:
        return self.get_int("SN_IMPORT_MAX", 500_000)

    @property
    def callback_max(self) -> int:
        return self.get_int("SN_CALLBACK_MAX", 200_000)

    @property
    def partition_ahead_months(self) -> int:
        return max(1, self.get_int("SN_PARTITION_AHEAD_MONTHS", 3))

    @property
    def partition_cron(self) -> str:
        return self.get("SN_PARTITION_CRON", "0 10 2 * * *")

    @property
    def cors_origins(self) -> list[str]:
        raw = self.raw("CORS_ORIGINS")
        if raw is None or not raw.strip():
            return list(DEFAULT_CORS)
        v = raw.strip()
        if v.startswith("["):
            try:
                out = json.loads(v)
                return [str(x) for x in out]
            except ValueError:
                return list(DEFAULT_CORS)
        return [item.strip() for item in v.split(",") if item.strip()]

    @property
    def cors_allow_any_origin(self) -> bool:
        return self.get_bool("CORS_ALLOW_ANY_ORIGIN", False)

    @property
    def zone(self) -> ZoneInfo:
        try:
            return ZoneInfo(self.get("TZ", "Asia/Shanghai"))
        except (ZoneInfoNotFoundError, ValueError):
            return ZoneInfo("Asia/Shanghai")

    @property
    def host(self) -> str:
        return self.get("SN_HOST", "0.0.0.0")

    @property
    def port(self) -> int:
        return self.get_int("SN_PORT", 8000)

    @property
    def db_pool_size(self) -> int:
        return max(1, self.get_int("DB_POOL_SIZE", 20))

    @property
    def db_baseline_on_migrate(self) -> bool:
        return self.get_bool("DB_BASELINE_ON_MIGRATE", False)

    @property
    def db_baseline_version(self) -> str:
        """已有无关表时写入的 Flyway 基线版本。默认 1（跳过 V1）；设为 0 则仍执行 V1。"""
        raw = self.get("DB_BASELINE_VERSION", "1").strip()
        parts = raw.split(".")
        if not parts or any(not part.isdigit() for part in parts):
            return "1"
        return raw

    def db_target(self) -> DbTarget:
        """DB_URL 与 Java 共用 JDBC 写法：jdbc:mysql://host:port/db?参数；也接受 mysql://user:pass@host/db。"""
        url = self.get(
            "DB_URL",
            "jdbc:mysql://127.0.0.1:3306/sndb?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        )
        if url.startswith("jdbc:"):
            url = url[len("jdbc:") :]
        parts = urlsplit(url)
        query = parse_qs(parts.query)
        user = self.raw("DB_USER")
        password = self.raw("DB_PASSWORD")
        if user is None:
            user = unquote(parts.username) if parts.username else query.get("user", ["appuser"])[0]
        if password is None:
            password = unquote(parts.password) if parts.password else query.get("password", [""])[0]
        database = parts.path.lstrip("/") or "sndb"
        return DbTarget(
            host=parts.hostname or "127.0.0.1",
            port=parts.port or 3306,
            database=database,
            user=user.strip(),
            password=password,
        )


settings = Settings()


def reload_settings() -> Settings:
    """重新定位仓库根并读取 .env（测试 / 启动时用）。"""
    global settings
    fresh = Settings()
    settings.__dict__.update(fresh.__dict__)
    return settings
