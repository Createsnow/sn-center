"""Guard one synthetic PI for the real 500000-row paired import UI check."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
PI = "CMP-UI-IMPORT-CAPACITY"
COUNT = 500000
spec = importlib.util.spec_from_file_location(
    "small", Path(__file__).with_name("parity-initialization-fixture.py")
)
small = importlib.util.module_from_spec(spec)
spec.loader.exec_module(small)
small.PIS = (PI, PI)
small.RULE = "CMP_CAP_NO_RULE"
small.BILL = "CMP_CAP_NO_BILL"


def sn(index, unicode=True):
    return f"CAP16-{index:06d}" + ("😀" if unicode else "L") * 52


def files():
    for name, count, unicode in (
        ("capacity-over.txt", COUNT + 1, False),
        ("capacity-valid.txt", COUNT, True),
    ):
        path = RUN / name
        digest = hashlib.sha256()
        with path.open("wb") as f:
            for i in range(count):
                line = (sn(i, unicode) + "\n").encode()
                f.write(line)
                digest.update(line)
        print(
            json.dumps(
                {
                    "file": name,
                    "count": count,
                    "bytes": path.stat().st_size,
                    "sha256": digest.hexdigest(),
                }
            )
        )


def observe(c, java):
    pi = "pi" if java else "pi_no"
    result = {}
    for table in ("sn_item", "sn_key"):
        c.execute(f"SELECT COUNT(*) n FROM {table} WHERE {pi}=%s", (PI,))
        size = c.fetchone()["n"]
        if size not in (0, COUNT):
            raise RuntimeError("Partial or foreign capacity members; preserve fixture")
        digest = hashlib.sha256()
        index = 0
        with c.connection.cursor(pymysql.cursors.SSDictCursor) as stream:
            stream.execute(f"SELECT sn FROM {table} WHERE {pi}=%s ORDER BY sn", (PI,))
            while rows := stream.fetchmany(2000):
                for row in rows:
                    if row["sn"] != sn(index):
                        raise RuntimeError("Unexpected capacity member; preserve fixture")
                    digest.update((row["sn"] + "\n").encode())
                    index += 1
        assert index == size
        result[table] = {"count": size, "sha256": digest.hexdigest()}
    assert result["sn_item"] == result["sn_key"]
    c.execute(
        "SELECT COUNT(*) n FROM sn_item WHERE pi=%s AND (source<>'IMPORT' OR status<>'PRINTED' OR source_pi<>pi OR seq_dec IS NOT NULL OR seq_pi IS NOT NULL OR customer<>'' OR material<>'' OR factory_id IS NOT NULL OR bill_no<>'' OR allocation_id IS NOT NULL OR current_batch_id IS NOT NULL OR current_transfer_id IS NOT NULL OR current_print_id IS NOT NULL)"
        if java
        else "SELECT COUNT(*) n FROM sn_item WHERE pi_no=%s AND (source<>'IMPORT' OR status<>'PRINTED' OR seq_pi_no<>pi_no OR seq_dec IS NOT NULL OR customer_code<>'' OR material_code<>'' OR factory_code IS NOT NULL OR bill_no<>'')",
        (PI,),
    )
    assert c.fetchone()["n"] == 0
    c.execute(
        "SELECT COUNT(*) n FROM sn_key WHERE pi=%s AND (seq_pi IS NOT NULL OR seq_dec IS NOT NULL)"
        if java
        else "SELECT COUNT(*) n FROM sn_key WHERE pi_no=%s AND (seq_pi_no IS NOT NULL OR seq_dec IS NOT NULL)",
        (PI,),
    )
    assert c.fetchone()["n"] == 0
    c.execute(
        "SELECT last_seq,generated_qty,imported_qty,start_seq,start_locked FROM sn_pi_counter WHERE pi=%s"
        if java
        else "SELECT last_seq_dec last_seq,generated_qty,imported_qty,start_seq_dec start_seq,start_locked FROM sn_pi_counter WHERE pi_no=%s",
        (PI,),
    )
    result["counter"] = c.fetchone()
    if result["sn_item"]["count"]:
        assert result["counter"] == {
            "last_seq": 9,
            "generated_qty": 0,
            "imported_qty": COUNT,
            "start_seq": 10,
            "start_locked": 1,
        }
    else:
        assert result["counter"] in (
            None,
            {
                "last_seq": 0,
                "generated_qty": 0,
                "imported_qty": 0,
                "start_seq": None,
                "start_locked": 0,
            },
        )
    c.execute(
        "SELECT quantity qty,start_seq,max_seq,file_name,actor_name operator FROM sn_work WHERE pi=%s AND kind='PI_INIT' AND state='SUCCESS'"
        if java
        else "SELECT import_qty qty,start_seq_dec start_seq,max_seq_dec max_seq,file_name,created_by operator FROM sn_pi_import WHERE pi_no=%s",
        (PI,),
    )
    result["history"] = c.fetchall()
    assert len(result["history"]) == (1 if result["sn_item"]["count"] else 0)
    if result["history"]:
        assert result["history"] == [
            {
                "qty": COUNT,
                "start_seq": 10,
                "max_seq": None,
                "file_name": "capacity-valid.txt",
                "operator": "admin",
            }
        ]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("action", choices=("files", "begin", "observe", "clean"))
    args = parser.parse_args()
    if args.action == "files":
        files()
        return
    java = args.kind == "java"
    snapshot = RUN / f"capacity-fixture-{args.kind}.json"
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            user=args.kind + "_compare",
            password=(PRIVATE / ("mysql-" + args.kind)).read_text().strip(),
            database="sn_" + args.kind + "_compare",
            cursorclass=pymysql.cursors.DictCursor,
        ) as conn,
        conn.cursor() as c,
    ):
        if args.action == "observe":
            print(json.dumps(observe(c, java), ensure_ascii=False))
            return
        c.execute(
            "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('pi','pi_no','seq_pi','seq_pi_no','source_pi','first_pi','from_pi','to_pi')"
        )
        columns = c.fetchall()
        allowed = {"sn_item", "sn_key", "sn_pi_counter", "sn_pi_import", "sn_work"}
        for col in columns:
            if col["TABLE_NAME"] in ("sn_audit", "sn_audit_archive"):
                continue
            if args.action == "begin" or col["TABLE_NAME"] not in allowed:
                c.execute(
                    f"SELECT COUNT(*) n FROM `{col['TABLE_NAME']}` WHERE `{col['COLUMN_NAME']}`=%s",
                    (PI,),
                )
                assert c.fetchone()["n"] == 0, "Unexpected PI reference"
        c.execute(
            "SELECT COUNT(*) n FROM sn_rule WHERE bind_scope='PI' AND bind_value=%s",
            (PI,),
        )
        assert c.fetchone()["n"] == 0, "Unexpected capacity rule"
        if args.action == "begin":
            assert (
                not snapshot.exists()
                and not list(RUN.glob("initialization-fixture-*.json"))
                and not list(RUN.glob("capacity-fixture-*.json"))
            )
            snapshot.write_text(
                json.dumps(small.inventory(c, java), indent=2), encoding="utf-8"
            )
            return
        before = json.loads(snapshot.read_text(encoding="utf-8"))
        assert small.inventory(c, java) == before, "Original rows changed"
        state = observe(c, java)
        if java:
            c.execute("SELECT kind,state FROM sn_work WHERE pi=%s", (PI,))
            assert all(
                r["kind"] == "PI_INIT"
                and r["state"] in ("SUCCESS", "FAILED", "CANCELLED")
                for r in c.fetchall()
            ), "Capacity task still active"
            c.execute("SELECT active_task_id FROM sn_pi_counter WHERE pi=%s", (PI,))
            assert all(r["active_task_id"] is None for r in c.fetchall())
        pi = "pi" if java else "pi_no"
        for table in (
            "sn_key",
            "sn_item",
            "sn_pi_counter",
            "sn_work" if java else "sn_pi_import",
        ):
            c.execute(f"DELETE FROM {table} WHERE {pi}=%s", (PI,))
        assert small.inventory(c, java) == before, (
            "Original rows changed during cleanup"
        )
        conn.commit()
        (RUN / f"capacity-clean-{args.kind}.json").write_text(
            json.dumps(
                {
                    "originalRowsUnchanged": before,
                    "ownedFixtureRemoved": True,
                    "finalOwnedState": state,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        snapshot.unlink()


if __name__ == "__main__":
    main()
