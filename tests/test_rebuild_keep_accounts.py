"""devtools/rebuild_keep_accounts：保留账户、按 V1 重建其他表。用单独的库，不碰集成测试的会话库。"""

from __future__ import annotations

import pytest

from app.db.migrate import HISTORY, HISTORY_DDL, migrations
from devtools import rebuild_keep_accounts as rk
from tests.conftest import DB, mysql_available, raw_conn

REBUILD_DB = DB + "_rebuild"

pytestmark = pytest.mark.skipif(not mysql_available(), reason="MySQL 不可用")

NOW = "2026-11-01 00:00:00"


def _build_old_db(c) -> None:
    """按 V1 建库后模拟「测试阶段的旧库」：业务表结构有变、有旧表、有账户和业务数据、V1 校验和是旧的。"""
    c.execute(f"DROP DATABASE IF EXISTS {REBUILD_DB}")
    c.execute(f"CREATE DATABASE {REBUILD_DB} CHARACTER SET utf8mb4")
    c.execute(f"USE {REBUILD_DB}")
    m = migrations()[0]
    for s in m.statements():
        c.execute(s)
    c.execute(HISTORY_DDL)
    c.execute(f"INSERT INTO {HISTORY} VALUES (1,'1','init','SQL','V1__init.sql',12345,'appuser',NOW(),10,1)")
    # 旧结构：号池多一列、导入表少一列、旧版本留下的表
    c.execute("ALTER TABLE sn_item ADD COLUMN old_col INT NULL")
    c.execute("ALTER TABLE sn_import_item DROP COLUMN serial_text")
    c.execute("CREATE TABLE sn_batch (id INT PRIMARY KEY) ENGINE=InnoDB")
    c.execute("INSERT INTO sn_batch VALUES (1)")
    # 账户、组织、金蝶连接
    c.execute(
        "INSERT INTO sn_factory(factory_code, factory_name, mes_enabled, mes_url, created_at, updated_at) "
        "VALUES ('110', '一厂', 1, 'http://mes/110', %s, %s)",
        (NOW, NOW),
    )
    for emp, role, org in (("admin", "admin", None), ("op1", "operator", None), ("mes110", "mes", "110")):
        c.execute(
            "INSERT INTO sn_user(emp_no, name, role, factory_code, password_hash, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, 'h', %s, %s)",
            (emp, emp, role, org, NOW, NOW),
        )
    c.execute("INSERT INTO sn_user_factory(user_id, factory_code) SELECT id, '110' FROM sn_user WHERE emp_no='op1'")
    # 业务数据与序号
    c.execute("INSERT INTO sn_seq(name, val) VALUES ('PS-110-20261101', 7), ('PL-110-20261101', 2), ('#daily', 9)")
    c.execute("UPDATE sn_sync_state SET last_message = 'old'")


@pytest.fixture
def cur():
    with raw_conn(None) as conn, conn.cursor() as c:
        _build_old_db(c)
        yield c
        c.execute(f"DROP DATABASE IF EXISTS {REBUILD_DB}")


def test_rebuild_keeps_accounts_and_rebuilds_others(cur) -> None:
    plan = rk.inspect(cur)
    assert rk.problems(plan) == []
    assert plan.diffs == {}
    assert "sn_item" in plan.drop and "sn_import_item" in plan.drop and "sn_batch" in plan.obsolete
    assert not set(rk.KEEP) & set(plan.drop)
    assert [r["name"] for r in plan.seq_rows] == ["PL-110-20261101", "PS-110-20261101"]

    rk.execute(cur, plan)

    after = rk.inspect(cur)
    assert rk.problems(after) == []
    v1_tables = [t for t, _ in after.creates]
    assert sorted(after.existing) == sorted(v1_tables)
    sql = dict(after.creates)
    # 重建过的表结构与 V1 一致
    assert rk.diff_table(cur, "sn_item", sql["sn_item"]) == []
    assert rk.diff_table(cur, "sn_import_item", sql["sn_import_item"]) == []
    # 账户、组织原样
    cur.execute("SELECT emp_no, role, factory_code FROM sn_user ORDER BY emp_no")
    assert [(r["emp_no"], r["role"], r["factory_code"]) for r in cur.fetchall()] == [
        ("admin", "admin", None),
        ("mes110", "mes", "110"),
        ("op1", "operator", None),
    ]
    cur.execute("SELECT COUNT(*) n FROM sn_user_factory")
    assert cur.fetchone()["n"] == 1
    cur.execute("SELECT mes_url FROM sn_factory")
    assert cur.fetchone()["mes_url"] == "http://mes/110"
    # 批次号序号写回，其他序号清掉；订单同步状态回到初始一行
    cur.execute("SELECT name, val FROM sn_seq ORDER BY name")
    assert [(r["name"], r["val"]) for r in cur.fetchall()] == [("PL-110-20261101", 2), ("PS-110-20261101", 7)]
    cur.execute("SELECT id, last_message FROM sn_sync_state")
    assert [(r["id"], r["last_message"]) for r in cur.fetchall()] == [(1, "")]
    # 迁移记录：V1 校验和改成当前脚本的
    cur.execute(f"SELECT checksum FROM {HISTORY} WHERE version = '1'")
    assert cur.fetchone()["checksum"] == migrations()[0].checksum()
    # 比对用的临时表不留
    cur.execute("SHOW TABLES LIKE 'tmp\\_rebuild\\_chk\\_%'")
    assert cur.fetchall() == ()


def test_refuses_when_kept_table_differs(cur) -> None:
    cur.execute("ALTER TABLE sn_user ADD COLUMN nickname VARCHAR(10) NULL")
    cur.execute("ALTER TABLE sn_factory DROP INDEX `PRIMARY`, ADD UNIQUE KEY ux_code (factory_code)")
    plan = rk.inspect(cur)
    bad = rk.problems(plan)
    assert set(plan.diffs) == {"sn_user", "sn_factory"}
    assert any("sn_user 与 V1 结构不一致" in b and "nickname" in b for b in bad)
    assert any("sn_factory" in b and "PRIMARY" in b for b in bad)
    # 不保留 sn_factory 时可以执行：它也按 V1 重建
    plan2 = rk.inspect(cur, ("sn_user", "sn_user_factory", "sn_k3_config"))
    assert "sn_factory" in plan2.drop and "sn_factory" not in plan2.diffs


def test_missing_kept_table_is_created(cur) -> None:
    cur.execute("DROP TABLE sn_k3_config")
    plan = rk.inspect(cur)
    assert rk.problems(plan) == []
    assert "sn_k3_config" in plan.create
    rk.execute(cur, plan)
    cur.execute("SHOW TABLES LIKE 'sn_k3_config'")
    assert cur.fetchall() != ()


def test_refuses_without_history(cur) -> None:
    cur.execute(f"DROP TABLE {HISTORY}")
    assert any(HISTORY in b for b in rk.problems(rk.inspect(cur)))
