"""本机演示数据（SN_DEMO_SEED=true 且库里还没有工厂时才灌）：工厂、规则、生产订单快照、演示账户。

连公司正式库时不要打开。
"""

from __future__ import annotations

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.security import hash_password
from app.db.session import db

log = structlog.get_logger()

_ORDERS = [
    # bill, line, customer, po, pi, material, name, qty, status, demand, org
    (
        "MO-DEMO-001",
        1,
        "0832",
        "PO-0832-01",
        "ADT260730-0832-1711",
        "9043192",
        "智能门锁 A1",
        120,
        "2",
        "SO-0832-01",
        "大磡工厂",
    ),
    (
        "MO-DEMO-001",
        2,
        "0832",
        "PO-0832-01",
        "ADT260730-0832-1711",
        "9043193",
        "智能门锁 A1 配件包",
        80,
        "2",
        "SO-0832-01",
        "大磡工厂",
    ),
    (
        "MO-DEMO-002",
        1,
        "0832",
        "PO-0832-01",
        "ADT260730-0832-1711",
        "9043192",
        "智能门锁 A1",
        60,
        "1",
        "SO-0832-01",
        "大磡工厂",
    ),
    ("MO-DEMO-003", 1, "0832", "PO-0832-02", "ADT260730-0832-1711", "9043192", "智能门锁 A1", 50, "2", "", "泗洪工厂"),
    ("MO-DEMO-004", 1, "1132", "PO-1132-07", "ADT250715-02-1", "925681", "网关 G2", 300, "2", "SO-1132-07", "泗洪工厂"),
    ("MO-DEMO-004", 2, "1132", "PO-1132-07", "ADT250715-02-1", "", "", 20, "2", "SO-1132-07", "泗洪工厂"),
    (
        "MO-DEMO-005",
        1,
        "2001",
        "PO-2001-01",
        "ADT260921-2001-001",
        "902001",
        "大批量摄像头",
        25000,
        "2",
        "",
        "越南工厂",
    ),
    ("MO-DEMO-006", 1, "", "PO-X", "ADT260101-NOCUST", "900001", "无客户订单", 10, "1", "", "大磡工厂"),
]


def _rule(code, name, scope, value, prefix, suffix, base, length, charset, ts) -> None:
    rid = db.insert(
        "INSERT INTO sn_rule(rule_code, rule_name, bind_scope, bind_value, current_version, created_by, created_at, "
        "updated_by, updated_at) VALUES (%s,%s,%s,%s,1,'demo',%s,'demo',%s)",
        code,
        name,
        scope,
        value,
        ts,
        ts,
    )
    db.exec(
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, used, created_by, "
        "created_at, updated_at) VALUES (%s,1,%s,%s,%s,%s,%s,0,'demo',%s,%s)",
        rid,
        prefix,
        suffix,
        base,
        length,
        charset,
        ts,
        ts,
    )


def seed_if_empty() -> None:
    with db.tx():
        if db.count("SELECT COUNT(*) FROM sn_factory") > 0:
            return
        ts = util.now()
        for code, name in (("100.01", "大磡工厂"), ("100.02", "泗洪工厂"), ("100.03", "越南工厂")):
            db.exec(
                "INSERT INTO sn_factory(factory_code, factory_name, enabled, source, created_at, updated_at) "
                "VALUES (%s,%s,1,'MANUAL',%s,%s)",
                code,
                name,
                ts,
                ts,
            )
        # 规则：通用 32 进制 6 位；客户 0832 十进制 5 位
        _rule(
            "R-GENERAL", "通用规则 32 进制 6 位", "GENERAL", "", "SN", "", 32, 6, "0123456789ABCDEFGHIJKLMNOPQRSTUV", ts
        )
        _rule("R-0832", "客户 0832 十进制 5 位", "CUSTOMER", "0832", "812609082", "", 10, 5, "0123456789", ts)
        db.exec_many(
            "INSERT INTO sn_prd_mo(bill_no, line_seq, customer_number, po, pi, material_number, material_name, qty, "
            "status, demand_bill_no, prd_org_name, synced_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            [r + (ts,) for r in _ORDERS],
        )
        pw = hash_password(settings.get("SN_DEMO_PASSWORD", "Demo@1234"))
        for emp, name, role, fac in (
            ("dk01", "大磡操作员", util.FACTORY, "100.01"),
            ("sh01", "泗洪操作员", util.FACTORY, "100.02"),
            ("vn01", "越南操作员", util.FACTORY, "100.03"),
            ("query01", "总部查询员", util.QUERY, None),
        ):
            db.exec(
                "INSERT IGNORE INTO sn_user(emp_no, name, role, factory_code, status, password_hash, must_change_pwd, "
                "created_by, created_at, updated_at) VALUES (%s,%s,%s,%s,'ACTIVE',%s,0,'demo',%s,%s)",
                emp,
                name,
                role,
                fac,
                pw,
                ts,
                ts,
            )
    log.warning("demo_seed_applied", factories=3, bills=6, users=4)
