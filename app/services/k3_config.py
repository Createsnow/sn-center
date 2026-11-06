"""金蝶连接参数（生产订单页「金蝶连接」，只有系统管理员）。

- 页面保存过的参数（sn_k3_config，只有一行）优先；没有保存过时沿用 .env 的 K3_*，页面上带出 .env 的值（密钥除外）。
- 应用密钥加密保存（与 MES 凭证同一套，密钥由 SN_SECRET 派生），不回显、不写进痕迹和日志；留空 = 沿用当前的密钥。
- 保存时先用新参数连接测试（登录 + 读组织机构），通过才保存；保存后下一次调用金蝶即生效，不用重启。
- 连接测试、保存都记痕迹：K3_CONFIG_TEST / K3_CONFIG（参数摘要，密钥只记是否更换）。
- 恢复默认：先用 .env 的 K3_* 连接测试，通过才删掉页面保存的参数，之后按 .env 连接；不会在失败时自动改用 .env。
"""

from __future__ import annotations

import re
import time

from app.core import secretbox
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, k3

#: 文本参数的最大长度（应用密钥另算）
TEXT_MAX = {"server_url": 500, "acct_id": 128, "app_id": 255, "username": 128}
SECRET_MAX = 255
#: 整数参数的取值范围
LIMITS = {
    "login_timeout": (5, 300),
    "query_timeout": (10, 600),
    "view_timeout": (5, 300),
    "page_retries": (0, 10),
    "page_retry_seconds": (0, 120),
}
#: 语言：中文简体 / 英文 / 中文繁体
LCIDS = (2052, 1033, 3076)
_URL = re.compile(r"^https?://\S+$")
_LABEL = {"server_url": "服务地址", "acct_id": "账套 ID", "app_id": "应用 ID", "username": "集成用户"}


def _last_test() -> dict | None:
    r = db.one(
        "SELECT created_at, operator, operator_name, result, error_msg FROM sn_audit WHERE action = %s "
        "ORDER BY created_at DESC LIMIT 1",
        audit.K3_CONFIG_TEST,
    )
    if r is None:
        return None
    return {
        "ok": r["result"] == "OK",
        "at": util.fmt(r["created_at"]),
        "by": r["operator"],
        "by_name": r["operator_name"],
        "message": r["error_msg"] or "",
    }


def view() -> dict:
    """页面显示的参数（不含应用密钥）、来源、最近测试与最后修改。"""
    row = k3.stored_row()
    if row is None:
        env = k3.env_values()
        values = {k: env[k] for k in (*TEXT_MAX, *k3.INT_PARAMS)}
        secret_set, secret_unreadable = bool(env["app_secret"]), False
        updated_at = updated_by = None
    else:
        values = {k: row[k] for k in TEXT_MAX} | {k: int(row[k]) for k in k3.INT_PARAMS}
        secret_set = bool(row["app_secret"])
        secret_unreadable = secretbox.open_(row["app_secret"]) is None
        updated_at, updated_by = util.fmt(row["updated_at"]), row["updated_by"]
    env = k3.env_values()
    return {
        "source": k3.SOURCE_PAGE if row is not None else k3.SOURCE_ENV,
        **values,
        "secret_set": secret_set,
        "secret_unreadable": secret_unreadable,
        "updated_at": updated_at,
        "updated_by": updated_by,
        "last_test": _last_test(),
        "history": _history(),
        # 恢复默认后使用的 .env 参数（不含应用密钥）：恢复前确认用
        "env": {**{k: env[k] for k in TEXT_MAX}, "secret_set": bool(env["app_secret"])},
    }


def _candidate(body: dict) -> tuple[dict, dict]:
    """页面提交的参数合成为一份完整参数（含密钥明文）；未提交的项沿用当前的。返回 (新参数, 当前参数)。"""
    try:
        _, cur = k3.current_values()
    except BizError:
        # 已存的密钥解不开：其余参数照常带出，密钥须重新填写
        row = k3.stored_row() or {}
        cur = {**k3.env_values(), **{k: row[k] for k in (*TEXT_MAX, *k3.INT_PARAMS) if k in row}, "app_secret": ""}
    out = dict(cur)
    for k, n in TEXT_MAX.items():
        v = body.get(k)
        if v is None:
            continue
        if not isinstance(v, str) or not v.strip():
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail=f"{_LABEL[k]}必填")
        if len(v.strip()) > n:
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail=f"{_LABEL[k]}不超过 {n} 字")
        out[k] = v.strip()
    if not _URL.match(out["server_url"] or ""):
        raise biz(ErrorCode.K3_CONFIG_INVALID, detail="服务地址须以 http:// 或 https:// 开头")
    secret_in = body.get("app_secret")
    if isinstance(secret_in, str) and secret_in.strip():
        if len(secret_in.strip()) > SECRET_MAX:
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail=f"应用密钥不超过 {SECRET_MAX} 字")
        out["app_secret"] = secret_in.strip()
    if not out["app_secret"]:
        raise biz(ErrorCode.K3_CONFIG_INVALID, detail="应用密钥必填")
    for k in TEXT_MAX:
        if not out.get(k):
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail=f"{_LABEL[k]}必填")
    lcid = body.get("lcid")
    if lcid is not None:
        if not isinstance(lcid, int) or isinstance(lcid, bool) or lcid not in LCIDS:
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail="语言只能是 2052、1033 或 3076")
        out["lcid"] = lcid
    for k, (lo, hi) in LIMITS.items():
        v = body.get(k)
        if v is None:
            continue
        if not isinstance(v, int) or isinstance(v, bool) or not lo <= v <= hi:
            raise biz(ErrorCode.K3_CONFIG_INVALID, detail=f"{k} 须为 {lo}–{hi}")
        out[k] = v
    return out, cur


def _summary(c: dict, secret_changed: bool) -> str:
    """痕迹里的参数摘要：不含应用密钥，只记是否更换。"""
    return (
        f"url={c['server_url']} acct={c['acct_id']} app={c['app_id']} user={c['username']} lcid={c['lcid']} "
        f"secret={'changed' if secret_changed else 'kept'} timeout={c['login_timeout']}/{c['query_timeout']}/"
        f"{c['view_timeout']} retries={c['page_retries']}x{c['page_retry_seconds']}s"
    )


def _changed(c: dict, cur: dict) -> list[str]:
    """与当前参数相比改了哪些项（应用密钥比明文，不记内容）。"""
    return [k for k in (*k3.TEXT_PARAMS, *k3.INT_PARAMS) if c[k] != cur.get(k)]


def _history(limit: int = 5) -> list[dict]:
    """最近几次保存：时间、操作人、改了哪些项（来自痕迹 K3_CONFIG 的 changed=…）。"""
    out = []
    for r in db.all(
        "SELECT created_at, operator, operator_name, detail FROM sn_audit WHERE action = %s AND result = 'OK' "
        "ORDER BY created_at DESC LIMIT %s",
        audit.K3_CONFIG,
        limit,
    ):
        parts = dict(p.split("=", 1) for p in (r["detail"] or "").split() if "=" in p)
        fields = [f for f in parts.get("changed", "").split(",") if f and f != "-"]
        out.append(
            {
                "at": util.fmt(r["created_at"]),
                "by": r["operator"],
                "by_name": r["operator_name"],
                "fields": fields,
                "from_env": parts.get("from") == "env",
                "reset": parts.get("reset") == "env",
            }
        )
    return out


def _probe(c: dict) -> tuple[bool, str, int, int]:
    """用这份参数登录金蝶并读组织机构；返回 (通过, 失败原因, 组织数, 用时毫秒)。"""
    started = time.monotonic()
    try:
        orgs = k3.fetch_organizations(k3.config_of(c))
    except BizError as e:
        return False, e.message, 0, int((time.monotonic() - started) * 1000)
    return True, "", len(orgs), int((time.monotonic() - started) * 1000)


def test_connection(cu: CurrentUser, body: dict) -> dict:
    """连接测试：用页面上的参数（密钥留空 = 当前的）登录并读组织机构，只提示结果、不保存。"""
    c, cur = _candidate(body)
    ok, message, orgs, ms = _probe(c)
    secret_changed = c["app_secret"] != cur["app_secret"]
    e = audit.entry(audit.K3_CONFIG_TEST).count(orgs).info(_summary(c, secret_changed) + f" ms={ms}")
    audit.record_isolated(cu, e if ok else e.fail(message))
    return {"ok": ok, "message": message, "orgs": orgs, "elapsed_ms": ms, "checked_at": util.fmt(util.now())}


def save(cu: CurrentUser, body: dict) -> dict:
    """保存：先用新参数连接测试，通过才写入；之后调用金蝶一律用页面保存的参数。"""
    c, cur = _candidate(body)
    ok, message, orgs, ms = _probe(c)
    secret_changed = c["app_secret"] != cur["app_secret"]
    e = audit.entry(audit.K3_CONFIG_TEST).count(orgs).info(_summary(c, secret_changed) + f" ms={ms}")
    audit.record_isolated(cu, e if ok else e.fail(message))
    if not ok:
        raise biz(ErrorCode.K3_TEST_FAILED, message=message)
    cols = ("server_url", "acct_id", "app_id", "app_secret", "username", *k3.INT_PARAMS)
    vals = [secretbox.seal(c[k]) if k == "app_secret" else c[k] for k in cols] + [cu.emp_no, util.now()]
    with db.tx():
        first = db.one("SELECT id FROM sn_k3_config WHERE id = 1 FOR UPDATE") is None
        if first:
            db.exec(
                f"INSERT INTO sn_k3_config(id, {', '.join(cols)}, updated_by, updated_at) "
                f"VALUES (1, {', '.join(['%s'] * (len(cols) + 2))})",
                *vals,
            )
        else:
            db.exec(
                f"UPDATE sn_k3_config SET {', '.join(f'{k} = %s' for k in cols)}, updated_by = %s, updated_at = %s "
                "WHERE id = 1",
                *vals,
            )
        fields = ",".join(_changed(c, cur)) or "-"
        audit.record(
            cu,
            audit.entry(audit.K3_CONFIG).info(
                f"changed={fields} " + ("from=env " if first else "") + _summary(c, secret_changed)
            ),
        )
    return view()


def reset_to_env(cu: CurrentUser) -> dict:
    """恢复默认：先用 .env 的 K3_* 连接测试，通过才删掉页面保存的参数；本来就在用 .env 时不做任何事。"""
    if k3.stored_row() is None:
        return view()
    env = k3.env_values()
    ok, message, orgs, ms = _probe(env)
    e = audit.entry(audit.K3_CONFIG_TEST).count(orgs).info(_summary(env, False) + f" source=env ms={ms}")
    audit.record_isolated(cu, e if ok else e.fail(message))
    if not ok:
        raise biz(ErrorCode.K3_RESET_FAILED, message=message)
    with db.tx():
        db.exec("DELETE FROM sn_k3_config WHERE id = 1")
        audit.record(cu, audit.entry(audit.K3_CONFIG).info("changed=- reset=env " + _summary(env, False)))
    return view()
