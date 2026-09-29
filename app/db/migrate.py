"""建表 / 升级：执行 app/db/migration/V*__*.sql，并写 Flyway 兼容的 flyway_schema_history。

脚本与 Java 后端 backend/src/main/resources/db/migration 完全相同（tests/test_parity.py 校验），
校验和按 Flyway 的算法（逐行 CRC32）计算，所以两个后端可以轮流连同一个库。
"""

from __future__ import annotations

import re
import time
import zlib
from dataclasses import dataclass
from pathlib import Path

import structlog

from app.core.config import settings
from app.db.session import Db

log = structlog.get_logger()

MIGRATION_DIR = Path(__file__).resolve().parent / "migration"
HISTORY = "flyway_schema_history"
_NAME = re.compile(r"^V(\d+(?:[._]\d+)*)__(.+)\.sql$")

HISTORY_DDL = f"""CREATE TABLE `{HISTORY}` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `{HISTORY}_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"""


class MigrationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Migration:
    version: str
    description: str
    script: str
    path: Path

    @property
    def key(self) -> tuple[int, ...]:
        return tuple(int(p) for p in re.split(r"[._]", self.version))

    def text(self) -> str:
        text = self.path.read_bytes().decode("utf-8")
        return text[1:] if text.startswith("﻿") else text

    def checksum(self) -> int:
        """Flyway：去掉 BOM 后逐行（不含换行符）累加 CRC32，结果按有符号 int。"""
        text = self.text()
        lines = re.split(r"\r\n|\r|\n", text)
        if text.endswith(("\n", "\r")):
            lines = lines[:-1]
        crc = 0
        for line in lines:
            crc = zlib.crc32(line.encode("utf-8"), crc)
        return crc - 2**32 if crc >= 2**31 else crc

    def statements(self) -> list[str]:
        body = "\n".join(line for line in self.text().splitlines() if not line.lstrip().startswith("--"))
        return [s.strip() for s in body.split(";") if s.strip()]


def migrations() -> list[Migration]:
    out = []
    for p in MIGRATION_DIR.glob("V*__*.sql"):
        m = _NAME.match(p.name)
        if m:
            out.append(Migration(m.group(1).replace("_", "."), m.group(2).replace("_", " "), p.name, p))
    return sorted(out, key=lambda m: m.key)


def migrate(db: Db) -> int:
    """执行未应用的脚本，返回本次执行的个数。"""
    user = db.target.user
    applied_now = 0
    with db.connection() as conn, conn.cursor() as c:
        c.execute("SELECT GET_LOCK('sn_center_migrate', 120) AS ok")
        if not c.fetchone()["ok"]:
            raise MigrationError("could not acquire migration lock")
        try:
            c.execute("SHOW TABLES")
            tables = {next(iter(r.values())) for r in c.fetchall()}
            if HISTORY not in tables:
                if tables:
                    if not settings.db_baseline_on_migrate:
                        raise MigrationError(
                            f"数据库 {db.target.database} 已有表但没有 {HISTORY}；确认结构与 V1 一致后设置"
                            " DB_BASELINE_ON_MIGRATE=true 再启动"
                        )
                    c.execute(HISTORY_DDL)
                    baseline_version = settings.db_baseline_version
                    c.execute(
                        f"INSERT INTO {HISTORY}(installed_rank, version, description, type, script, checksum, "
                        "installed_by, execution_time, success) VALUES (1,%s,'<< Flyway Baseline >>','BASELINE',"
                        "'<< Flyway Baseline >>',NULL,%s,0,1)",
                        (baseline_version, user),
                    )
                    log.warning("schema_baselined", version=baseline_version)
                else:
                    c.execute(HISTORY_DDL)
            c.execute(f"SELECT installed_rank, version, checksum, success, type FROM {HISTORY} ORDER BY installed_rank")
            history = list(c.fetchall())
            failed = [h for h in history if not h["success"]]
            if failed:
                raise MigrationError(f"迁移 V{failed[0]['version']} 曾经失败，请先人工修复 {HISTORY}")
            done = {h["version"]: h for h in history if h["version"] is not None}
            baseline = max(
                (tuple(int(p) for p in h["version"].split(".")) for h in history if h["type"] == "BASELINE"),
                default=(),
            )
            rank = max((h["installed_rank"] for h in history), default=0)
            for m in migrations():
                prev = done.get(m.version)
                if prev is not None:
                    if prev["type"] == "SQL" and prev["checksum"] != m.checksum():
                        log.warning("migration_checksum_mismatch", version=m.version)
                    continue
                if baseline and m.key <= baseline:
                    continue
                started = time.monotonic()
                for stmt in m.statements():
                    c.execute(stmt)
                rank += 1
                c.execute(
                    f"INSERT INTO {HISTORY}(installed_rank, version, description, type, script, checksum, "
                    "installed_by, execution_time, success) VALUES (%s,%s,%s,'SQL',%s,%s,%s,%s,1)",
                    (
                        rank,
                        m.version,
                        m.description,
                        m.script,
                        m.checksum(),
                        user,
                        int((time.monotonic() - started) * 1000),
                    ),
                )
                applied_now += 1
                log.info("migration_applied", version=m.version, script=m.script)
        finally:
            c.execute("SELECT RELEASE_LOCK('sn_center_migrate')")
    return applied_now
