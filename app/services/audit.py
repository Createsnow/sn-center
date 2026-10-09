"""操作痕迹写入。

成功操作与业务写入同一事务（回滚则痕迹一起回滚）；被拒绝的操作用独立事务记一条 FAIL，不受业务回滚影响。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

import structlog

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db

log = structlog.get_logger()

# ---------------------------------------------------------------- 动作

LOGIN = "LOGIN"
PASSWORD_CHANGE = "PASSWORD_CHANGE"
USER_CREATE = "USER_CREATE"
USER_UPDATE = "USER_UPDATE"
USER_DISABLE = "USER_DISABLE"
USER_ENABLE = "USER_ENABLE"
USER_RESET_PWD = "USER_RESET_PWD"
FACTORY_SYNC = "FACTORY_SYNC"
FACTORY_CREATE = "FACTORY_CREATE"
ORDER_SYNC = "ORDER_SYNC"
ORDER_SYNC_SCHEDULE = "ORDER_SYNC_SCHEDULE"
RULE_CREATE = "RULE_CREATE"
RULE_UPDATE = "RULE_UPDATE"
RULE_VERSION = "RULE_VERSION"
RULE_RENAME = "RULE_RENAME"
RULE_REBIND = "RULE_REBIND"
RULE_PI_BIND = "RULE_PI_BIND"
RULE_PI_UNBIND = "RULE_PI_UNBIND"
PI_INIT = "PI_INIT"
SN_GENERATE = "SN_GENERATE"
SN_ALLOCATE = "SN_ALLOCATE"
SN_ACQUIRE = "SN_ACQUIRE"
SN_PRINT = "SN_PRINT"
SN_CALLBACK = "SN_CALLBACK"
TRANSFER_APPLY = "TRANSFER_APPLY"
TRANSFER_WITHDRAW = "TRANSFER_WITHDRAW"
TRANSFER_APPROVE = "TRANSFER_APPROVE"
TRANSFER_REJECT = "TRANSFER_REJECT"
TRANSFER_DIRECT = "TRANSFER_DIRECT"
AUDIT_ARCHIVE = "AUDIT_ARCHIVE"
OTHER = "OTHER"


@dataclass
class Entry:
    """一条痕迹的业务字段（操作人 / 时间 / 来源由 record 填）。"""

    action: str
    result: str = "OK"
    factory_code: str | None = None
    pi_no: str | None = None
    customer_code: str | None = None
    material_code: str | None = None
    bill_no: str | None = None
    start_sn: str | None = None
    end_sn: str | None = None
    qty: int | None = None
    before_status: str | None = None
    after_status: str | None = None
    batch_no: str | None = None
    request_no: str | None = None
    transfer_no: str | None = None
    reason: str | None = None
    error_msg: str | None = None
    detail: str | None = None

    def factory(self, v: str | None) -> Entry:
        self.factory_code = v
        return self

    def pi(self, v: str | None) -> Entry:
        self.pi_no = v
        return self

    def customer(self, v: str | None) -> Entry:
        self.customer_code = v
        return self

    def material(self, v: str | None) -> Entry:
        self.material_code = v
        return self

    def bill(self, v: str | None) -> Entry:
        self.bill_no = v
        return self

    def range(self, start: str | None, end: str | None) -> Entry:
        self.start_sn = start
        self.end_sn = end
        return self

    def count(self, v: int) -> Entry:
        self.qty = v
        return self

    def status(self, before: str | None, after: str | None) -> Entry:
        self.before_status = before
        self.after_status = after
        return self

    def batch(self, v: str | None) -> Entry:
        self.batch_no = v
        return self

    def request(self, v: str | None) -> Entry:
        self.request_no = v
        return self

    def transfer(self, v: str | None) -> Entry:
        self.transfer_no = v
        return self

    def why(self, v: str | None) -> Entry:
        self.reason = util.cut(v, 500)
        return self

    def info(self, v: str | None) -> Entry:
        self.detail = util.cut(v, 2000)
        return self

    def fail(self, message: str | None) -> Entry:
        self.result = "FAIL"
        self.error_msg = util.cut(message, 1000)
        return self


def entry(action: str) -> Entry:
    return Entry(action)


_INSERT = """
INSERT INTO sn_audit(created_at, operator, operator_name, role, source, action, result,
  factory_code, pi_no, customer_code, material_code, bill_no, start_sn, end_sn, qty,
  before_status, after_status, batch_no, request_no, transfer_no, reason, error_msg, trace_id, detail)
VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"""


def record(u: CurrentUser, e: Entry) -> None:
    """在当前事务中记一条。"""
    db.exec(
        _INSERT,
        util.now_millis(),
        u.emp_no,
        u.name or "",
        u.role or "",
        u.source,
        e.action,
        e.result,
        e.factory_code,
        e.pi_no,
        e.customer_code,
        e.material_code,
        e.bill_no,
        e.start_sn,
        e.end_sn,
        e.qty,
        e.before_status,
        e.after_status,
        e.batch_no,
        e.request_no,
        e.transfer_no,
        e.reason,
        e.error_msg,
        u.trace_id,
        e.detail,
    )


def record_isolated(u: CurrentUser, e: Entry) -> None:
    """独立事务记一条（失败痕迹、后台任务）。写痕迹本身失败只打日志，不影响调用方。"""
    try:
        with db.new_tx():
            record(u, e)
    except Exception as ex:  # noqa: BLE001
        log.warning("audit_write_failed", action=e.action, err=str(ex))


# ---------------------------------------------------------------- 失败留痕

#: 路径前缀 → 痕迹动作（按顺序匹配）
_PATH_ACTIONS = (
    ("/api/auth/password", PASSWORD_CHANGE),
    ("/api/users", USER_UPDATE),
    ("/api/factories", FACTORY_SYNC),
    ("/api/orders/sync-schedule", ORDER_SYNC_SCHEDULE),
    ("/api/orders", ORDER_SYNC),
    ("/api/rules", RULE_UPDATE),
    ("/api/pi-init", PI_INIT),
    ("/api/generate/allocate", SN_ALLOCATE),
    ("/api/generate", SN_GENERATE),
    ("/api/acquire/take", SN_ACQUIRE),
    ("/api/acquire/print", SN_PRINT),
    ("/api/open/acquire", SN_ACQUIRE),
    ("/api/open/callback", SN_CALLBACK),
    ("/api/transfers/direct", TRANSFER_DIRECT),
    ("/api/transfers/", TRANSFER_APPLY),
)


def action_for(method: str, path: str) -> str:
    if re.fullmatch(r"/api/transfers/\d+/approve", path):
        return TRANSFER_APPROVE
    if re.fullmatch(r"/api/transfers/\d+/reject", path):
        return TRANSFER_REJECT
    if re.fullmatch(r"/api/transfers/\d+/withdraw", path):
        return TRANSFER_WITHDRAW
    if path == "/api/users" and method == "POST":
        return USER_CREATE
    if path.endswith("/disable"):
        return USER_DISABLE
    for prefix, action in _PATH_ACTIONS:
        if path.startswith(prefix):
            return action
    return OTHER


def _str(m: dict, key: str) -> str | None:
    v = m.get(key)
    return v.strip() if isinstance(v, str) and v.strip() else None


def record_failure(u: CurrentUser, method: str, path: str, body: bytes | None, message: str) -> None:
    """已登录账户的 /api 写请求被业务拒绝：独立事务记一条 FAIL，PI / 工厂 / 数量等从请求体抽取。"""
    e = entry(action_for(method, path)).fail(message)
    if body:
        try:
            m = json.loads(body)
        except ValueError:
            m = None  # 非 JSON 请求体：只记动作与原因
        if isinstance(m, dict):
            (
                e.factory(_str(m, "factory_code"))
                .pi(_str(m, "pi_no"))
                .bill(_str(m, "bill_no"))
                .material(_str(m, "material_code"))
                .request(_str(m, "request_no"))
                .batch(_str(m, "batch_no"))
                .why(_str(m, "reason"))
            )
            q = m.get("qty")
            if isinstance(q, (int, float)) and not isinstance(q, bool):
                e.count(int(q))
    e.info(f"{method} {path}")
    record_isolated(u, e)
