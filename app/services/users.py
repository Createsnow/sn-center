"""登录、改密与账户管理。账户用公司工号创建，只停用不删除。"""

from __future__ import annotations

import re
import secrets
from datetime import datetime, timedelta

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import (
    PAGE,
    CurrentUser,
    check_policy,
    hash_password,
    issue_token,
    pwd_version,
    verify_password,
)
from app.db.session import db
from app.services import audit, login_guard

ACTIVE = "ACTIVE"
DISABLED = "DISABLED"
_EMP_NO = re.compile(r"^[A-Za-z0-9_-]{2,32}$")
LANGS = frozenset(("zh-CN", "en", "vi"))


def _view(u: dict, factory_name: str | None) -> dict:
    return {
        "id": u["id"],
        "emp_no": u["emp_no"],
        "name": u["name"],
        "role": u["role"],
        "factory_code": u["factory_code"],
        "factory_name": factory_name,
        "status": u["status"],
        "must_change_pwd": bool(u["must_change_pwd"]),
        "lang": u["lang"],
        "last_login_at": util.fmt(u["last_login_at"]),
        "created_at": util.fmt(u["created_at"]),
        "created_by": u["created_by"],
        "disabled_at": util.fmt(u["disabled_at"]),
        "disabled_by": u["disabled_by"],
    }


def view(u: dict) -> dict:
    name = None
    if u["factory_code"] is not None:
        f = db.one("SELECT factory_name FROM sn_factory WHERE factory_code = %s", u["factory_code"])
        name = None if f is None else f["factory_name"]
    return _view(u, name)


def _by_emp_no(emp_no: str) -> dict | None:
    return db.one("SELECT * FROM sn_user WHERE emp_no = %s", emp_no)


def _must_get(uid: int) -> dict:
    u = db.one("SELECT * FROM sn_user WHERE id = %s", uid)
    if u is None:
        raise biz(ErrorCode.USER_NOT_FOUND)
    return u


def _next_pv(u: dict, now: datetime) -> datetime:
    """同一秒内连改两次时口令版本仍须变化。"""
    prev = u["pwd_changed_at"]
    return prev + timedelta(seconds=1) if prev is not None and not now > prev else now


_dummy_hash: str | None = None


def _dummy() -> str:
    """工号不存在时也做一次同样代价的口令校验，避免按响应时间判断工号是否存在。"""
    global _dummy_hash
    if _dummy_hash is None:
        _dummy_hash = hash_password(secrets.token_hex(16))
    return _dummy_hash


def _login_actor(emp: str, u: dict | None, trace_id: str | None) -> CurrentUser:
    if u is None:
        return CurrentUser(None, util.cut(emp, 32) or "-", "", "", None, PAGE, trace_id)
    return CurrentUser(u["id"], u["emp_no"], u["name"], u["role"], u["factory_code"], PAGE, trace_id)


def _login_failed(actor: CurrentUser, e: BizError, detail: str) -> BizError:
    """登录失败：独立事务留痕（不受请求结果影响），返回要抛出的错误。"""
    audit.record_isolated(actor, audit.entry(audit.LOGIN).factory(actor.factory_code).fail(e.message).info(detail))
    return e


def login(emp_no: str | None, password: str | None, trace_id: str | None = None, client: str | None = None) -> dict:
    """登录。成功、失败（含锁定、停用）都留痕；同一工号连续失败达到上限后临时锁定。"""
    emp = util.trim(emp_no)
    key = util.cut(emp, 64) or ""
    where = f"client={client or '-'}"
    try:
        login_guard.check(key)
    except BizError as e:
        raise _login_failed(_login_actor(emp, None, trace_id), e, where + " locked") from None
    u = _by_emp_no(emp) if emp else None
    matched = verify_password(password, _dummy() if u is None else u["password_hash"])
    if u is None or not matched:
        locked = login_guard.fail(key)
        detail = where + (f" locked={settings.login_lock_minutes}m" if locked else "")
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.LOGIN_FAILED), detail) from None
    if u["status"] != ACTIVE:
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.ACCOUNT_DISABLED), where) from None
    login_guard.success(key)
    u["last_login_at"] = util.now()
    with db.tx():
        db.exec("UPDATE sn_user SET last_login_at = %s WHERE id = %s", u["last_login_at"], u["id"])
        audit.record(_login_actor(emp, u, trace_id), audit.entry(audit.LOGIN).factory(u["factory_code"]).info(where))
    return {"token": issue_token(u["id"], pwd_version(u["pwd_changed_at"])), "user": view(u)}


def me(cu: CurrentUser) -> dict:
    return view(_must_get(cu.id))


def change_password(cu: CurrentUser, old_password: str | None, new_password: str | None) -> dict:
    """改自己的密码；返回新令牌（旧令牌随口令版本失效）。"""
    with db.tx():
        u = _must_get(cu.id)
        if not verify_password(old_password, u["password_hash"]):
            raise biz(ErrorCode.USER_PASSWORD_WRONG)
        check_policy(new_password)
        if verify_password(new_password, u["password_hash"]):
            raise biz(ErrorCode.USER_PASSWORD_SAME)
        now = util.now()
        db.exec(
            "UPDATE sn_user SET password_hash = %s, must_change_pwd = 0, pwd_changed_at = %s, updated_at = %s "
            "WHERE id = %s",
            hash_password(new_password),
            _next_pv(u, now),
            now,
            u["id"],
        )
        audit.record(cu, audit.entry(audit.PASSWORD_CHANGE).info(u["emp_no"]))
        fresh = _must_get(u["id"])
        return {"token": issue_token(fresh["id"], pwd_version(fresh["pwd_changed_at"])), "user": view(fresh)}


def set_lang(cu: CurrentUser, lang: str | None) -> dict:
    lng = util.trim_or_none(lang)
    if lng is not None and lng not in LANGS:
        lng = None
    db.exec("UPDATE sn_user SET lang = %s WHERE id = %s", lng, cu.id)
    return view(_must_get(cu.id))


def list_users(q: str | None, role: str | None, status: str | None, factory: str | None, page: util.Page) -> dict:
    where = " WHERE 1=1"
    args: list = []
    text = util.trim(q)
    if text:
        where += " AND (emp_no LIKE %s OR name LIKE %s)"
        args += [text + "%", "%" + text + "%"]
    for col, v in (("role", role), ("status", status), ("factory_code", factory)):
        if not util.blank(v):
            where += f" AND {col} = %s"
            args.append(util.trim(v))
    total = db.count("SELECT COUNT(*) FROM sn_user" + where, *args)
    names = {f["factory_code"]: f["factory_name"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}
    rows = db.all(f"SELECT * FROM sn_user{where} ORDER BY emp_no ASC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result([_view(u, names.get(u["factory_code"])) for u in rows], total, page)


def _check_role_factory(role: str, factory_code: str | None) -> str | None:
    if role not in util.ROLES:
        raise biz(ErrorCode.USER_ROLE_INVALID)
    f = util.trim_or_none(factory_code)
    if role == util.ADMIN and f is not None:
        raise biz(ErrorCode.USER_ADMIN_NO_FACTORY)
    if role == util.FACTORY and f is None:
        raise biz(ErrorCode.USER_FACTORY_REQUIRED)
    if f is not None and db.one("SELECT 1 FROM sn_factory WHERE factory_code = %s", f) is None:
        raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=f)
    return f


def _check_not_last_admin(target: dict) -> None:
    if target["role"] != util.ADMIN or target["status"] != ACTIVE:
        return
    admins = db.count("SELECT COUNT(*) FROM sn_user WHERE role = %s AND status = %s", util.ADMIN, ACTIVE)
    if admins <= 1:
        raise biz(ErrorCode.USER_LAST_ADMIN)


def create(
    cu: CurrentUser,
    emp_no: str | None,
    name: str | None,
    role: str | None,
    factory_code: str | None,
    password: str | None,
) -> dict:
    with db.tx():
        emp = util.trim(emp_no)
        if not _EMP_NO.match(emp):
            raise biz(ErrorCode.USER_EMP_NO_INVALID)
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role)
        f = _check_role_factory(r, factory_code)
        check_policy(password)
        if _by_emp_no(emp) is not None:
            raise biz(ErrorCode.USER_EMP_NO_EXISTS, emp_no=emp)
        now = util.now()
        uid = db.insert(
            "INSERT INTO sn_user(emp_no, name, role, factory_code, status, password_hash, must_change_pwd, created_by, "
            "created_at, updated_at) VALUES (%s,%s,%s,%s,%s,%s,1,%s,%s,%s)",
            emp,
            n,
            r,
            f,
            ACTIVE,
            hash_password(password),
            cu.emp_no,
            now,
            now,
        )
        audit.record(cu, audit.entry(audit.USER_CREATE).factory(f).info(f"emp_no={emp} role={r}"))
        return view(_must_get(uid))


def update(cu: CurrentUser, uid: int, name: str | None, role: str | None, factory_code: str | None) -> dict:
    with db.tx():
        u = _must_get(uid)
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role)
        f = _check_role_factory(r, factory_code)
        if u["role"] == util.ADMIN and r != util.ADMIN:
            _check_not_last_admin(u)
        db.exec(
            "UPDATE sn_user SET name = %s, role = %s, factory_code = %s, updated_at = %s WHERE id = %s",
            n,
            r,
            f,
            util.now(),
            uid,
        )
        audit.record(
            cu,
            audit.entry(audit.USER_UPDATE)
            .factory(f)
            .info(f"emp_no={u['emp_no']} role={u['role']}->{r} factory={_jstr(u['factory_code'])}->{_jstr(f)}"),
        )
        return view(_must_get(uid))


def _jstr(v: str | None) -> str:
    return "null" if v is None else v


def disable(cu: CurrentUser, uid: int) -> dict:
    with db.tx():
        u = _must_get(uid)
        if u["id"] == cu.id:
            raise biz(ErrorCode.USER_SELF_DISABLE)
        if u["status"] == DISABLED:
            return view(u)
        _check_not_last_admin(u)
        now = util.now()
        db.exec(
            "UPDATE sn_user SET status = %s, disabled_by = %s, disabled_at = %s, updated_at = %s WHERE id = %s",
            DISABLED,
            cu.emp_no,
            now,
            now,
            uid,
        )
        audit.record(cu, audit.entry(audit.USER_DISABLE).factory(u["factory_code"]).info(f"emp_no={u['emp_no']}"))
        return view(_must_get(uid))


def enable(cu: CurrentUser, uid: int) -> dict:
    with db.tx():
        u = _must_get(uid)
        if u["status"] == ACTIVE:
            return view(u)
        db.exec(
            "UPDATE sn_user SET status = %s, disabled_by = NULL, disabled_at = NULL, updated_at = %s WHERE id = %s",
            ACTIVE,
            util.now(),
            uid,
        )
        audit.record(cu, audit.entry(audit.USER_ENABLE).factory(u["factory_code"]).info(f"emp_no={u['emp_no']}"))
        return view(_must_get(uid))


def reset_password(cu: CurrentUser, uid: int, password: str | None) -> dict:
    """重置为管理员给定的初始密码；下次登录必须改密，旧令牌失效。"""
    with db.tx():
        u = _must_get(uid)
        check_policy(password)
        now = util.now()
        db.exec(
            "UPDATE sn_user SET password_hash = %s, must_change_pwd = 1, pwd_changed_at = %s, updated_at = %s "
            "WHERE id = %s",
            hash_password(password),
            _next_pv(u, now),
            now,
            uid,
        )
        audit.record(cu, audit.entry(audit.USER_RESET_PWD).factory(u["factory_code"]).info(f"emp_no={u['emp_no']}"))
        return view(_must_get(uid))


def reset_admin(password: str) -> bool:
    """运维恢复：把工号 admin 重置为初始密码并启用、角色改回管理员，下次登录强制改密。

    只在服务器上设置 SN_RESET_ADMIN=true 启动时执行一次（需要服务器权限），并留痕。
    """
    with db.tx():
        u = _by_emp_no("admin")
        if u is None:
            return bootstrap_admin(password)
        now = util.now()
        db.exec(
            "UPDATE sn_user SET role = %s, factory_code = NULL, status = %s, password_hash = %s, must_change_pwd = 1, "
            "pwd_changed_at = %s, disabled_by = NULL, disabled_at = NULL, updated_at = %s WHERE id = %s",
            util.ADMIN,
            ACTIVE,
            hash_password(password),
            now,
            now,
            u["id"],
        )
        audit.record(CurrentUser.system(), audit.entry(audit.USER_RESET_PWD).info("emp_no=admin SN_RESET_ADMIN"))
        return True


def bootstrap_admin(password: str) -> bool:
    """空库启动时建首个管理员（工号 admin），首次登录强制改密。"""
    with db.tx():
        if db.count("SELECT COUNT(*) FROM sn_user") > 0:
            return False
        now = util.now()
        db.insert(
            "INSERT INTO sn_user(emp_no, name, role, status, password_hash, must_change_pwd, created_by, created_at, "
            "updated_at) VALUES ('admin','系统管理员',%s,%s,%s,1,'system',%s,%s)",
            util.ADMIN,
            ACTIVE,
            hash_password(password),
            now,
            now,
        )
        audit.record(CurrentUser.system(), audit.entry(audit.USER_CREATE).info("emp_no=admin bootstrap"))
        return True
