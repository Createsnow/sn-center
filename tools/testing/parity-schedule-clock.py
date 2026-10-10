"""Advance only the isolated comparison schedule clock; the running worker does the sync."""

import argparse
from pathlib import Path

import pymysql

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("kind", choices=("java", "python"))
kind = parser.parse_args().kind
root = Path(__file__).resolve().parents[2]
private = root / "deploy/.secrets/comparison-20261008"
with pymysql.connect(
    host="127.0.0.1",
    port=33308,
    user=kind + "_compare",
    password=(private / ("mysql-" + kind)).read_text(encoding="utf-8").strip(),
    database="sn_" + kind + "_compare",
) as connection:
    with connection.cursor() as cursor:
        # Python 原型保存本地朴素时间；Java 保存 UTC。仅调整到期时间，不伪造执行结果。
        cursor.execute(
            "SET time_zone = %s", ("+00:00" if kind == "java" else "+08:00",)
        )
        cursor.execute(
            "UPDATE sn_order_sync_schedule SET next_run_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) "
            "WHERE id=1 AND enabled=1 AND interval_minutes=10"
        )
        if cursor.rowcount != 1:
            raise SystemExit(
                "Enable the 10-minute schedule through the comparison UI first."
            )
    connection.commit()
print(kind + ": comparison schedule due; awaiting the existing worker")
