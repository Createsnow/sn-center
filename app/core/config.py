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

#: 未配置 SN_SECRET 时的兜底密钥（仅非生产环境可用）
DEFAULT_SECRET = "change-me-sn-center-secret"
#: 公开出现过的示例密钥：生产环境不允许使用
WEAK_SECRETS = frozenset((DEFAULT_SECRET, "change-me-to-a-long-random-string"))
MIN_SECRET_LEN = 32
#: 内置管理员的默认工号与密码（.env 未配置时）；生产环境不允许用默认密码建 / 重置管理员
DEFAULT_ADMIN_EMP_NO = "admin"
DEFAULT_ADMIN_PASSWORD = "Admin@123"


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
        return self.get("SN_SECRET", "") or DEFAULT_SECRET

    def secret_problem(self) -> str | None:
        """登录令牌签名密钥的问题；None 表示可用。生产环境有问题时拒绝启动。"""
        v = self.get("SN_SECRET", "")
        if not v:
            return "SN_SECRET 未设置"
        if v in WEAK_SECRETS:
            return "SN_SECRET 仍是示例值"
        if len(v) < MIN_SECRET_LEN:
            return f"SN_SECRET 长度不足 {MIN_SECRET_LEN} 位"
        return None

    @property
    def access_token_ttl_seconds(self) -> int:
        return self.get_int("ACCESS_TOKEN_TTL_SECONDS", 12 * 3600)

    @property
    def init_admin_emp_no(self) -> str:
        """内置管理员的工号；空库首次启动按它建管理员，SN_RESET_ADMIN 也重置这个工号。"""
        return self.get("SN_INIT_ADMIN_EMP_NO", "") or DEFAULT_ADMIN_EMP_NO

    @property
    def init_admin_password(self) -> str:
        return self.get("SN_INIT_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD)

    def admin_password_problem(self) -> str | None:
        """内置管理员密码的问题；None 表示可用。生产环境有问题时不建 / 不重置管理员并拒绝启动。"""
        v = self.init_admin_password
        if not v:
            return "SN_INIT_ADMIN_PASSWORD 未设置"
        if v == DEFAULT_ADMIN_PASSWORD:
            return "SN_INIT_ADMIN_PASSWORD 仍是默认值"
        return None

    @property
    def demo_seed(self) -> bool:
        return self.get_bool("SN_DEMO_SEED", False)

    @property
    def audit_retention_days(self) -> int:
        return max(0, self.get_int("SN_AUDIT_RETENTION_DAYS", 1095))

    @property
    def audit_archive_retention_days(self) -> int:
        """痕迹总保存天数上限（按发生时间算，含在线与归档）：超过的归档痕迹每日清理；0 = 归档永久保留。
        不会短于在线保存天数 SN_AUDIT_RETENTION_DAYS。"""
        return max(0, self.get_int("SN_AUDIT_ARCHIVE_RETENTION_DAYS", 1825))

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
    def gen_default_limit(self) -> int:
        """生成弹窗的默认单次上限：额度超过它时数量默认填它，用户可勾选「解除限制」。"""
        return max(1, self.get_int("SN_GEN_DEFAULT_LIMIT", 10_000))

    @property
    def gen_max_per_run(self) -> int:
        """单次生成的硬上限（一次生成在一个事务里写完，过大易超时 / 撑爆事务）。"""
        return max(1, self.get_int("SN_GEN_MAX_PER_RUN", 500_000))

    @property
    def preview_ttl_minutes(self) -> int:
        return self.get_int("SN_PREVIEW_TTL_MINUTES", 30)

    @property
    def sync_interval_minutes(self) -> int:
        """比对同步间隔（分钟）。"""
        return max(1, self.get_int("SN_SYNC_INTERVAL_MINUTES", 10))

    @property
    def push_retry_intervals(self) -> list[int]:
        """推送未确认时自动重试的间隔（分钟）；个数即次数。"""
        raw = self.get("SN_PUSH_RETRY_INTERVALS_MINUTES", "1,5,15")
        out = []
        for part in raw.split(","):
            try:
                v = int(part.strip())
            except ValueError:
                continue
            if v >= 0:
                out.append(v)
        return out

    @property
    def batch_unconfirmed_alert_minutes(self) -> int:
        return max(1, self.get_int("SN_BATCH_UNCONFIRMED_ALERT_MINUTES", 30))

    @property
    def acquire_sync_file_limit(self) -> int:
        """领取文件当场生成上限；更多时页面提示到领取批次中下载。"""
        return max(1, self.get_int("SN_ACQUIRE_SYNC_FILE_LIMIT", 50_000))

    @property
    def mes_auth_max_failures(self) -> int:
        return max(1, self.get_int("SN_MES_AUTH_MAX_FAILURES", 10))

    @property
    def mes_auth_lock_minutes(self) -> int:
        return max(1, self.get_int("SN_MES_AUTH_LOCK_MINUTES", 15))

    @property
    def mes_pull_rate_per_minute(self) -> int:
        """同一生产组织每分钟调用 MES 接口（取批、回执、只读）的次数上限。"""
        return max(1, self.get_int("SN_MES_PULL_RATE_PER_MINUTE", 60))

    @property
    def push_poll_seconds(self) -> int:
        """推送调度检查间隔（秒）。"""
        return max(1, self.get_int("SN_PUSH_POLL_SECONDS", 5))

    @property
    def push_enabled(self) -> bool:
        """后台推送调度开关（测试时可关闭，改为手动驱动）。"""
        return self.get_bool("SN_PUSH_ENABLED", True)

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
        """MySQL 连接目标。按项读 DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD，改哪项就换哪项。

        兼容旧写法 DB_URL（jdbc:mysql://主机:端口/库名?参数，或 mysql://用户:密码@主机:端口/库名）：
        只作为未单独配置项的来源，单项配置总是优先；URL 里的 JDBC 参数不读取。
        """
        url = self.get("DB_URL", "")
        if url.startswith("jdbc:"):
            url = url[len("jdbc:") :]
        parts = urlsplit(url)
        query = parse_qs(parts.query)
        try:
            url_port = parts.port
        except ValueError:
            url_port = None

        def pick(key: str, from_url: str | None, fallback: str) -> str:
            v = self.raw(key)
            if v is not None and v.strip():
                return v.strip()
            return from_url or fallback

        host = pick("DB_HOST", parts.hostname, "127.0.0.1")
        port = self.get_int("DB_PORT", url_port or 3306)
        database = pick("DB_NAME", parts.path.lstrip("/"), "sn_dev")
        user = pick("DB_USER", unquote(parts.username) if parts.username else query.get("user", [""])[0], "appuser")
        # 密码可以含空格、可以为空，不做 strip
        password = self.raw("DB_PASSWORD")
        if password is None:
            password = unquote(parts.password) if parts.password else query.get("password", [""])[0]
        return DbTarget(host=host, port=port, database=database, user=user, password=password)


settings = Settings()


def reload_settings() -> Settings:
    """重新定位仓库根并读取 .env（测试 / 启动时用）。"""
    global settings
    fresh = Settings()
    settings.__dict__.update(fresh.__dict__)
    return settings
