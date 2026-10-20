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
MES_AUTH = "MES_AUTH"
MES_AUTH_LOCK = "MES_AUTH_LOCK"
MES_AUTH_UNLOCK = "MES_AUTH_UNLOCK"
USER_CREATE = "USER_CREATE"
USER_UPDATE = "USER_UPDATE"
USER_DISABLE = "USER_DISABLE"
USER_ENABLE = "USER_ENABLE"
USER_RESET_PWD = "USER_RESET_PWD"
USER_RESET_SECRET = "USER_RESET_SECRET"
FACTORY_SYNC = "FACTORY_SYNC"
FACTORY_MES_CONFIG = "FACTORY_MES_CONFIG"
FACTORY_MES_TOGGLE = "FACTORY_MES_TOGGLE"
FACTORY_MES_TEST = "FACTORY_MES_TEST"
ORDER_SYNC = "ORDER_SYNC"
ORDER_ATTACH_FILL = "ORDER_ATTACH_FILL"
ORDER_LENGTH_FILL = "ORDER_LENGTH_FILL"
ORDER_ALERT = "ORDER_ALERT"
ORDER_ALERT_HANDLE = "ORDER_ALERT_HANDLE"
ORDER_SUSPEND = "ORDER_SUSPEND"
ORDER_RESUME = "ORDER_RESUME"
RULE_CREATE = "RULE_CREATE"
RULE_UPDATE = "RULE_UPDATE"
RULE_VERSION = "RULE_VERSION"
RULE_RENAME = "RULE_RENAME"
RULE_CATEGORY = "RULE_CATEGORY"
RULE_PI_BIND = "RULE_PI_BIND"
RULE_PI_UNBIND = "RULE_PI_UNBIND"
RULE_LINE_SELECT = "RULE_LINE_SELECT"
RULE_PREFIX_VERSION = "RULE_PREFIX_VERSION"
RULE_CUSTOMER_BIND = "RULE_CUSTOMER_BIND"
RULE_CUSTOMER_UNBIND = "RULE_CUSTOMER_UNBIND"
RULE_DELETE = "RULE_DELETE"
SN_GENERATE = "SN_GENERATE"
SN_GEN_CANCEL = "SN_GEN_CANCEL"
SN_REVOKE = "SN_REVOKE"
SN_ALLOCATE = "SN_ALLOCATE"
SN_BATCH_CREATE = "SN_BATCH_CREATE"
SN_PUSH = "SN_PUSH"
SN_PUSH_RESUME = "SN_PUSH_RESUME"
SN_PULL = "SN_PULL"
SN_RECEIPT = "SN_RECEIPT"
SN_BATCH_MANUAL = "SN_BATCH_MANUAL"
SN_BATCH_VOID = "SN_BATCH_VOID"
SN_ALERT_HANDLE = "SN_ALERT_HANDLE"
SN_ACQUIRE = "SN_ACQUIRE"
SN_READ = "SN_READ"
TRANSFER_BILL = "TRANSFER_BILL"
TRANSFER_APPLY = "TRANSFER_APPLY"
TRANSFER_WITHDRAW = "TRANSFER_WITHDRAW"
TRANSFER_APPROVE = "TRANSFER_APPROVE"
TRANSFER_REJECT = "TRANSFER_REJECT"
TRANSFER_DIRECT = "TRANSFER_DIRECT"
AUDIT_ARCHIVE = "AUDIT_ARCHIVE"
AUDIT_ARCHIVE_PURGE = "AUDIT_ARCHIVE_PURGE"
SELF_CHECK = "SELF_CHECK"
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

#: 路径 → 痕迹动作（正则全匹配，按顺序）
_PATH_ACTIONS = (
    (r"/api/auth/password", PASSWORD_CHANGE),
    (r"/api/users", USER_CREATE),
    (r"/api/users/\d+/disable", USER_DISABLE),
    (r"/api/users/\d+/enable", USER_ENABLE),
    (r"/api/users/\d+/reset-password", USER_RESET_PWD),
    (r"/api/users/\d+/reset-secret", USER_RESET_SECRET),
    (r"/api/users/\d+", USER_UPDATE),
    (r"/api/factories/sync", FACTORY_SYNC),
    (r"/api/factories/[^/]+/mes-config", FACTORY_MES_CONFIG),
    (r"/api/factories/[^/]+/mes-test", FACTORY_MES_TEST),
    (r"/api/factories/[^/]+/mes-toggle", FACTORY_MES_TOGGLE),
    (r"/api/orders/attach", ORDER_ATTACH_FILL),
    (r"/api/orders/length(/batch)?", ORDER_LENGTH_FILL),
    (r"/api/orders/alerts/\d+/handle", ORDER_ALERT_HANDLE),
    (r"/api/orders/.*", ORDER_SYNC),
    (r"/api/rules/line-(select|clear)", RULE_LINE_SELECT),
    (r"/api/rules/pi-binding", RULE_PI_BIND),
    (r"/api/rules/customer-template", RULE_PI_BIND),
    (r"/api/rules/customer-binding", RULE_CUSTOMER_BIND),
    (r"/api/rules/\d+", RULE_UPDATE),
    (r"/api/rules", RULE_CREATE),
    (r"/api/generate/batches/[^/]+/cancel", SN_GEN_CANCEL),
    (r"/api/generate/batches/[^/]+/revoke", SN_REVOKE),
    (r"/api/generate/revoke", SN_REVOKE),
    (r"/api/generate/.*", SN_GENERATE),
    (r"/api/allocation/.*", SN_ALLOCATE),
    (r"/api/delivery/batches/[^/]+/confirm", SN_BATCH_MANUAL),
    (r"/api/delivery/batches/[^/]+/void", SN_BATCH_VOID),
    (r"/api/delivery/resume", SN_PUSH_RESUME),
    (r"/api/delivery/alerts/\d+/handle", SN_ALERT_HANDLE),
    (r"/api/acquire/.*", SN_ACQUIRE),
    (r"/api/transfers/bill.*", TRANSFER_BILL),
    (r"/api/transfers/org/direct", TRANSFER_DIRECT),
    (r"/api/transfers/org/\d+/approve", TRANSFER_APPROVE),
    (r"/api/transfers/org/\d+/reject", TRANSFER_REJECT),
    (r"/api/transfers/org/\d+/withdraw", TRANSFER_WITHDRAW),
    (r"/api/transfers/org.*", TRANSFER_APPLY),
    (r"/api/mes/v1/batches/next", SN_PULL),
    (r"/api/mes/v1/batches/[^/]+/receipt", SN_RECEIPT),
    (r"/api/mes/v1/.*", SN_READ),
)


def action_for(method: str, path: str) -> str:
    if method == "DELETE" and re.fullmatch(r"/api/rules/\d+", path):
        return RULE_DELETE
    if path == "/api/rules/pi-binding" and method == "DELETE":
        return RULE_PI_UNBIND
    if path == "/api/rules/customer-binding" and method == "DELETE":
        return RULE_CUSTOMER_UNBIND
    for pattern, action in _PATH_ACTIONS:
        if re.fullmatch(pattern, path):
            return action
    return OTHER


def _str(m: dict, key: str) -> str | None:
    v = m.get(key)
    return v.strip() if isinstance(v, str) and v.strip() else None


def record_failure(u: CurrentUser, method: str, path: str, body: bytes | None, message: str) -> None:
    """已登录账户的 /api 写请求被业务拒绝：独立事务记一条 FAIL，PI / 生产组织 / 数量等从请求体抽取。"""
    e = entry(action_for(method, path)).fail(message)
    if body:
        try:
            m = json.loads(body)
        except ValueError:
            m = None  # 非 JSON 请求体：只记动作与原因
        if isinstance(m, dict):
            (
                e.factory(_str(m, "factory_code") or u.factory_code)
                .pi(_str(m, "pi_no") or _str(m, "pi"))
                .bill(_str(m, "bill_no") or _str(m, "billNo") or _str(m, "from_bill"))
                .material(_str(m, "material_code"))
                .request(_str(m, "request_no"))
                .batch(_str(m, "batch_no") or _str(m, "batchNo"))
                .why(_str(m, "reason"))
            )
            q = m.get("qty")
            if isinstance(q, (int, float)) and not isinstance(q, bool):
                e.count(int(q))
    e.info(f"{method} {path}")
    record_isolated(u, e)
