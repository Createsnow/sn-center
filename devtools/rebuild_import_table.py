"""重建 SN 导入表 sn_import_item（导入模板改版后，已建好的库按 V1 里的新结构重建这一张表）。

只动 sn_import_item 一张表，号池等其他表不动。前提：号池里没有导入的号（sn_item.origin = 'IMPORT' 为 0 枚），
否则号池里导入的号（seq_dec 即导入记录 id）会和重建后的导入记录对不上，不重建、直接退出。

    uv run python -m devtools.rebuild_import_table          # 只检查，列出会做什么
    uv run python -m devtools.rebuild_import_table --yes    # 检查通过后删表并按 V1 重建（空表）

读仓库根 .env 的 DB_*（与启动服务相同）。V1 改过后，已建好的库启动时只记 migration_checksum_mismatch 警告，
不会重跑 V1，所以要用本工具重建这一张表。
"""

from __future__ import annotations

import argparse
import sys

from app.core.config import settings
from app.db.migrate import migrations
from app.db.session import db

TABLE = "sn_import_item"


def create_sql() -> str:
    """V1 里 sn_import_item 的建表语句。"""
    for m in migrations():
        for stmt in m.statements():
            if stmt.startswith(f"CREATE TABLE {TABLE} "):
                return stmt
    raise SystemExit(f"迁移脚本里没有 CREATE TABLE {TABLE}")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--yes", action="store_true", help="检查通过后执行删表、重建")
    a = p.parse_args(argv)
    db.configure(settings.db_target(), 1)
    t = db.target
    print(f"数据库 {t.host}:{t.port}/{t.database}")
    pool = db.count("SELECT COUNT(*) FROM sn_item WHERE origin = 'IMPORT'")
    exists = db.one("SHOW TABLES LIKE %s", TABLE) is not None
    rows = db.count(f"SELECT COUNT(*) FROM {TABLE}") if exists else 0
    print(f"号池里导入的号：{pool} 枚；{TABLE}：{'有' if exists else '没有'}这张表，{rows} 行")
    if pool:
        print("号池里还有导入的号，不能重建：先在导入页整单删除（须全部待分配），已交付 MES 的须与 MES 方一起处理")
        return 1
    sql = create_sql()
    if not a.yes:
        print(f"检查通过。加 --yes 执行：DROP TABLE IF EXISTS {TABLE}，再按 V1 建空表（{rows} 行导入记录会删除）")
        return 0
    with db.tx():
        db.exec(f"DROP TABLE IF EXISTS {TABLE}")
        db.exec(sql)
    print(f"已按 V1 重建 {TABLE}（空表）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
