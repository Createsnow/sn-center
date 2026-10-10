"""Isolated factory-page fixtures; remove only reserved codes after verifying references."""

import argparse
import hashlib
import json
from pathlib import Path
import time

import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
CONTROL = ROOT / "tools/.runtime/comparison-20261008/k3-control.json"
SEEDS = [f"CMP_F44_L_{i:02d}" for i in range(53)]
CODES = SEEDS + ["CMP_F44_MANUAL", "cmp_f44_manual", "CMP_F44_é", "CMP_F44_e", "🏭" * 64, "CMP_F44_K3", "CMP_F44_CONFLICT"]


def digest(rows):
    return hashlib.sha256(json.dumps(rows, default=str, sort_keys=True).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("action", choices=("prepare", "snapshot", "clean"))
    args = parser.parse_args()
    java = args.kind == "java"
    path = PRIVATE / f"factory-{args.kind}.json"
    code = "code" if java else "factory_code"
    with pymysql.connect(host="127.0.0.1", port=33308, user=args.kind + "_compare",
                         password=(PRIVATE / ("mysql-" + args.kind)).read_text().strip(),
                         database="sn_" + args.kind + "_compare", charset="utf8mb4",
                         cursorclass=pymysql.cursors.DictCursor) as db, db.cursor() as c:
        def factories():
            c.execute(f"SELECT * FROM sn_factory ORDER BY `{code}`")
            return list(c.fetchall())

        def mappings():
            if not java:
                return []
            c.execute("SELECT * FROM sn_org_mapping ORDER BY source_system,source_account,org_id")
            return list(c.fetchall())

        if args.action == "snapshot":
            print(json.dumps(factories(), default=str, ensure_ascii=False))
            return
        if args.action == "prepare":
            assert not path.exists(), "Inspect and clean the active factory fixture first"
            before = factories()
            assert not any(row[code] in CODES for row in before)
            assert json.loads(CONTROL.read_text("utf8")) == {}, "Another K3 scenario is active"
            state = {"before": before, "mappings": mappings(), "codes": CODES}
            path.write_text(json.dumps(state, default=str, ensure_ascii=False), "utf8")
            for i in reversed(range(53)):
                # 固定时间分别使用UTC/上海业务时间；只用于列表排序、容量和时间显示的对照。
                fields = "code,name,source,created_at,updated_at" if java else "factory_code,factory_name,source,created_at,updated_at"
                stamp = "2026-10-09 02:03:04" if java else "2026-10-09 10:03:04"
                c.execute(f"INSERT INTO sn_factory({fields}) VALUES(%s,%s,%s,%s,%s)",
                          (SEEDS[i], f"比较工厂44-{i:02d}", "K3" if i % 2 else "MANUAL", stamp, stamp))
            db.commit()
            print(args.kind + ": 53 reserved factories seeded in reverse code order")
            return
        state = json.loads(path.read_text("utf8"))
        all_rows = factories()
        owned = [r for r in all_rows if r[code] in state["codes"]]
        assert digest([r for r in all_rows if r[code] not in state["codes"]]) == digest(state["before"])
        ids = [r["id"] for r in owned] if java else []
        current_maps = mappings()
        assert digest([r for r in current_maps if r["factory_id"] not in ids]) == digest(state["mappings"])
        # 真实审计保留；业务、成员关系和任务存在引用时拒绝清理，不级联删除。
        c.execute("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND (COLUMN_NAME LIKE '%%factory%%id' OR COLUMN_NAME LIKE '%%factory%%code')")
        for column in c.fetchall():
            table, name = column["TABLE_NAME"], column["COLUMN_NAME"]
            if table in ("sn_factory", "sn_org_mapping") or "audit" in table:
                continue
            values = ids if name.endswith("id") else [r[code] for r in owned]
            if not values:
                continue
            placeholders = ",".join(["%s"] * len(values))
            c.execute(f"SELECT COUNT(*) n FROM `{table}` WHERE `{name}` IN ({placeholders})", values)
            assert c.fetchone()["n"] == 0, f"Owned factories are referenced by {table}.{name}"
        for row in owned:
            if java:
                c.execute("DELETE FROM sn_org_mapping WHERE factory_id=%s", (row["id"],))
            c.execute(f"DELETE FROM sn_factory WHERE `{code}`=%s", (row[code],))
        db.commit()
        assert digest(factories()) == digest(state["before"])
        assert digest(mappings()) == digest(state["mappings"])
        CONTROL.write_text("{}\n", "utf8")
        path.rename(path.with_name(path.stem + f".{time.time_ns()}.completed.json"))
        print(f"{args.kind}: removed {len(owned)} reserved factories; original rows/mappings and real audits retained")


if __name__ == "__main__":
    main()
