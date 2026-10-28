"""本机演示数据（SN_DEMO_SEED=true 且库里还没有生产组织时才灌）：生产组织、规则、生产订单快照、演示账户。

连公司正式库时不要打开。
"""

from __future__ import annotations

import structlog

from app.core import encoding as codec
from app.core import utils as util
from app.core.config import settings
from app.core.security import hash_password
from app.db.session import db

log = structlog.get_logger()

_ORGS = (("110", "光纤连接（越南）有限公司", 420), ("120", "大磡生产组织", 480), ("130", "泗洪生产组织", 480))

_ORDERS = [
    # bill, seq, customer, po, pi, org, attach, material, name, length, qty
    ("VT2026090001", 1, "0001", "PO-0001-01", "000001", "110", "1", "9148363", "带接头光缆线", "0100", 100),
    ("VT2026090001", 2, "0001", "PO-0001-01", "000001", "110", "2", "9148363", "带接头光缆线", "10.50", 50),
    ("VT2026090001", 3, "0001", "PO-0001-01", "000001", "110", "", "9150001", "跳线", "3", 30),
    ("VT2026090002", 1, "0001", "PO-0001-02", "000001", "110", "1", "9148363", "带接头光缆线", "100", 60),
    ("DK2026090001", 1, "0832", "PO-0832-01", "000002", "120", "1", "9043192", "智能门锁 A1", "0", 120),
    ("SH2026090001", 1, "1132", "PO-1132-07", "000003", "130", "1", "925681", "网关 G2", "", 300),
    ("SH2026090002", 1, "", "PO-X", "000004", "130", "1", "900001", "无客户订单", "1", 10),
]


def _rule(code: str, name: str, prefix: str, base: int, seq_len: int, with_length: bool, ts) -> None:
    rid = db.insert(
        "INSERT INTO sn_rule(rule_code, rule_name, category_scope, category_value, current_version, created_by, "
        "created_at, updated_by, updated_at) VALUES (%s,%s,'NONE','',1,'demo',%s,'demo',%s)",
        code,
        name,
        ts,
        ts,
    )
    db.exec(
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, with_length, used, "
        "created_by, created_at, updated_at) VALUES (%s,1,%s,'',%s,%s,%s,%s,0,'demo',%s,%s)",
        rid,
        prefix,
        base,
        seq_len,
        codec.DEFAULT_CHARSETS[base],
        1 if with_length else 0,
        ts,
        ts,
    )


def seed_if_empty() -> None:
    with db.tx():
        if db.count("SELECT COUNT(*) FROM sn_factory") > 0:
            return
        ts = util.now()
        for code, name, tz in _ORGS:
            db.exec(
                "INSERT INTO sn_factory(factory_code, factory_name, enabled, tz_offset_min, created_at, updated_at) "
                "VALUES (%s,%s,1,%s,%s,%s)",
                code,
                name,
                tz,
                ts,
                ts,
            )
        _rule("R-HX", "带米数 10 进制 6 位", "HX", 10, 6, True, ts)
        _rule("R-DK", "不带米数 32 进制 5 位", "DK", 32, 5, False, ts)
        db.exec_many(
            "INSERT INTO sn_prd_mo(bill_no, seq, customer_code, po, pi, org_code, org_name, attach_no, "
            "material_code, material_name, length_raw, qty, modify_time, synced_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            [
                (b, s, c, po, pi, org, dict((o[0], o[1]) for o in _ORGS)[org], att, m, n, ln, q, ts, ts)
                for b, s, c, po, pi, org, att, m, n, ln, q in _ORDERS
            ],
        )
        pw = hash_password(settings.get("SN_DEMO_PASSWORD", "Demo@1234"))
        every = tuple(o[0] for o in _ORGS)
        for emp, name, role, facs in (
            ("vn01", "越南操作员", util.OPERATOR, ("110",)),
            ("dk01", "大磡操作员", util.OPERATOR, ("120",)),
            ("sh01", "泗洪操作员", util.OPERATOR, ("130",)),
            ("query01", "总部审计员", util.AUDITOR, every),
        ):
            uid = db.insert(
                "INSERT IGNORE INTO sn_user(emp_no, name, role, factory_code, status, password_hash, must_change_pwd, "
                "pwd_changed_at, created_by, created_at, updated_at) VALUES "
                "(%s,%s,%s,NULL,'ACTIVE',%s,0,%s,'demo',%s,%s)",
                emp,
                name,
                role,
                pw,
                ts,
                ts,
                ts,
            )
            for fac in facs if uid else ():
                db.exec("INSERT IGNORE INTO sn_user_factory(user_id, factory_code) VALUES (%s,%s)", uid, fac)
    log.warning("demo_seed_applied", factories=len(_ORGS), bills=len({o[0] for o in _ORDERS}), users=4)
