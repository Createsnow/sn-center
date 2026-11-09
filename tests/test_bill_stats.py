"""单据汇总 sn_bill_stat（容量与扩展规划 2.1）：标脏随事务、回填、对账。
每个用例结束时 conftest 还会把汇总与号池重新汇总的结果整表比对（assert_bill_stat_consistent）。"""

from __future__ import annotations

from conftest import m, ok, uid

from app.db.session import db
from app.services import bill_stats


def _mk(w, qty: int):
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty, "0100"))
    prefix = "S" + uid("").replace("-", "")[-8:] + "T"
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=prefix, base=10, seq_len=6, with_length=False)))
    w.generate(pi)
    return org, pi, bill


def _stat(sql, pi: str) -> list[dict]:
    return sql.all(
        "SELECT bill_no, org_key, factory_code, qty, pending, to_push, alloc_qty FROM sn_bill_stat WHERE pi_no = %s",
        pi,
    )


def test_stat_follows_generate_and_allocate(w, sql):
    org, pi, bill = _mk(w, 5)
    bill_stats.refresh(budget_seconds=None)
    assert _stat(sql, pi) == [
        {"bill_no": bill, "org_key": "", "factory_code": None, "qty": 5, "pending": 5, "to_push": 0, "alloc_qty": 0}
    ]
    w.allocate(pi)
    bill_stats.refresh(budget_seconds=None)
    assert _stat(sql, pi) == [
        {"bill_no": bill, "org_key": org, "factory_code": org, "qty": 5, "pending": 0, "to_push": 0, "alloc_qty": 5}
    ]
    assert sql.count("SELECT COUNT(*) FROM sn_bill_stat_dirty") == 0


def test_marks_roll_back_with_the_transaction(app_client, sql):
    """改号的事务回滚：标记一起回滚；事务外标脏马上写入。"""
    pi, bill = uid("PI"), uid("MO")
    try:
        with db.tx():
            bill_stats.touch(pi, bill)
            raise RuntimeError("boom")
    except RuntimeError:
        pass
    assert sql.count("SELECT COUNT(*) FROM sn_bill_stat_dirty WHERE pi_no = %s", pi) == 0
    with db.tx():
        bill_stats.touch(pi, bill)
        bill_stats.touch(pi, bill)
    assert sql.scalar("SELECT ver FROM sn_bill_stat_dirty WHERE pi_no = %s", pi) == 1, "同一事务合并成一次"
    bill_stats.touch(pi, bill)
    assert sql.scalar("SELECT ver FROM sn_bill_stat_dirty WHERE pi_no = %s", pi) == 2
    bill_stats.refresh(budget_seconds=None)
    assert sql.count("SELECT COUNT(*) FROM sn_bill_stat_dirty WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_bill_stat WHERE pi_no = %s", pi) == 0, "号池里没有这张单据的号"


def test_backfill_when_table_is_empty(w, sql):
    """汇总表是空的而号池有号（如从备份单独恢复了号池）：启动时按号池整表回填。"""
    _org, pi, bill = _mk(w, 3)
    bill_stats.refresh(budget_seconds=None)
    sql.exec("DELETE FROM sn_bill_stat")
    assert bill_stats.ensure_filled() is True
    assert [(r["bill_no"], r["qty"], r["pending"]) for r in _stat(sql, pi)] == [(bill, 3, 3)]
    assert bill_stats.ensure_filled() is False, "已有数据不再回填"


def test_reconcile_fixes_a_wrong_row(w, sql):
    _org, pi, bill = _mk(w, 4)
    bill_stats.refresh(budget_seconds=None)
    sql.exec("UPDATE sn_bill_stat SET qty = 99 WHERE pi_no = %s", pi)
    assert bill_stats.reconcile() == 1
    assert _stat(sql, pi)[0]["qty"] == 4
    sql.exec("DELETE FROM sn_bill_stat WHERE pi_no = %s", pi)
    assert bill_stats.reconcile(missing=True) == 1, "号池里有、汇总里缺的也找出来"
    assert _stat(sql, pi)[0]["qty"] == 4
    assert bill_stats.reconcile() == 0
