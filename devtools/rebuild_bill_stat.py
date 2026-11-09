"""单据汇总 sn_bill_stat：对账或整表重建（容量与扩展规划 2.1；运维手册 8.8）。

单据汇总平时随改号自动标脏、重算，每日维护还会抽查最近的单据对账，一般不用手动处理。下面情况用本工具：
怀疑数字不对（日志有 bill_stat_mismatch）、直接在库里改过号池（没走程序）、从备份恢复过号池。

    uv run python -m devtools.rebuild_bill_stat              # 只对账：抽查最近 2000 张单据，列出不一致的个数
    uv run python -m devtools.rebuild_bill_stat --all        # 对账全部单据，含号池里有、汇总里缺的（较慢，只读号池）
    uv run python -m devtools.rebuild_bill_stat --rebuild    # 按号池整表重建（一致性读，不锁号池，服务可不停）

读仓库根 .env 的 DB_*（与启动服务相同）。重建期间页面单据列表照常可用（重建在一个事务里替换整表）。
"""

from __future__ import annotations

import argparse
import sys

from app.core.config import settings
from app.db.session import db
from app.services import bill_stats


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--all", action="store_true", help="对账全部单据（默认只抽查最近的 2000 张）")
    p.add_argument("--rebuild", action="store_true", help="按号池整表重建")
    a = p.parse_args(argv)
    db.configure(settings.db_target(), 2)
    t = db.target
    print(f"数据库 {t.host}:{t.port}/{t.database}")
    if a.rebuild:
        n = bill_stats.rebuild_all()
        left = bill_stats.refresh(budget_seconds=None, wait=30)
        print(f"已重建单据汇总：{n} 行；重建期间改动的单据另重算 {left} 张")
        return 0
    limit = db.count("SELECT COUNT(*) FROM sn_bill_stat") if a.all else 2000
    bad = bill_stats.reconcile(limit=max(limit, 1), missing=a.all)
    print(f"对账 {'全部' if a.all else '最近'}单据：不一致 {bad} 张" + ("（已重算）" if bad else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
