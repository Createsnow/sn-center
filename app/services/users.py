"""登录、改密与账户管理。账户用公司工号创建，只停用不删除。

管理员建账户时给定的密码直接可用，不强制改密；只有管理员重置别人的密码后，下次登录必须改密。
"""

from __future__ import annotations

import re
import secrets
from dataclasses import dataclass
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
    session_version,
    verify_password,
)
from app.db.session import db, marks
from app.services import audit, factories

ACTIVE = "ACTIVE"
DISABLED = "DISABLED"
_EMP_NO = re.compile(r"^[A-Za-z0-9_-]{2,32}$")
LANGS = frozenset(("zh-CN", "en", "vi"))


def _view(u: dict, names: dict[str, str], bound: list[str]) -> dict:
    codes = [u["factory_code"]] if u["factory_code"] is not None else bound
    return {
        "id": u["id"],
        "emp_no": u["emp_no"],
        "name": u["name"],
        "role": u["role"],
        "factory_code": u["factory_code"],
        "factory_name": names.get(u["factory_code"]) if u["factory_code"] is not None else None,
        # 可看的工厂：厂区操作员 = 本厂，查询员 = 绑定的厂；空 = 全部
        "factory_codes": codes,
        "factory_names": [names.get(c, c) for c in codes],
        "status": u["status"],
        "super_admin": util.is_super_admin(u["emp_no"], u["role"]),
        "must_change_pwd": bool(u["must_change_pwd"]),
        "lang": u["lang"],
        "last_login_at": util.fmt(u["last_login_at"]),
        "created_at": util.fmt(u["created_at"]),
        "created_by": u["created_by"],
        "disabled_at": util.fmt(u["disabled_at"]),
        "disabled_by": u["disabled_by"],
    }


def _factory_names() -> dict[str, str]:
    return {f["factory_code"]: f["factory_name"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}


def bound_factories(uid: int) -> list[str]:
    """查询员绑定的工厂（没有 = 全部）。"""
    return db.column("SELECT factory_code FROM sn_user_factory WHERE user_id = %s ORDER BY factory_code", uid)


def read_scope(uid: int, role: str, factory_code: str | None) -> tuple[str, ...]:
    """账户可读的工厂；空 = 全部。"""
    if role == util.FACTORY:
        return (factory_code,) if factory_code else ()
    if role == util.QUERY:
        return tuple(bound_factories(uid))
    return ()


def view(u: dict) -> dict:
    return _view(u, _factory_names(), bound_factories(u["id"]) if u["role"] == util.QUERY else [])


def _by_emp_no(emp_no: str) -> dict | None:
    return db.one("SELECT * FROM sn_user WHERE emp_no = %s", emp_no)


def _must_get(uid: int) -> dict:
    u = db.one("SELECT * FROM sn_user WHERE id = %s", uid)
    if u is None:
        raise biz(ErrorCode.USER_NOT_FOUND)
    return u


def _next_pv(u: dict, now: datetime) -> datetime:
    """同一秒内连改两次时口令版本仍须变化。"""
    return _next_version(u["pwd_changed_at"], now)


def _next_version(prev: datetime | None, now: datetime) -> datetime:
    """版本时间（秒）必须严格递增：不晚于上一次时顺延一秒。"""
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


def login(
    emp_no: str | None,
    password: str | None,
    trace_id: str | None = None,
    client: str | None = None,
    page: bool = False,
) -> dict:
    """登录。成功、失败（含停用）都留痕；不做失败次数锁定。

    page = 页面登录：本次登录后，该账户之前在页面上的令牌全部失效（后登录的页面覆盖先登录的）。
    对接方（MES）登录不改登录版本，既不挤掉页面，也不会被页面登录挤掉。
    """
    emp = util.trim(emp_no)
    where = f"client={client or '-'}"
    u = _by_emp_no(emp) if emp else None
    matched = verify_password(password, _dummy() if u is None else u["password_hash"])
    if u is None or not matched:
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.LOGIN_FAILED), where) from None
    if u["status"] != ACTIVE:
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.ACCOUNT_DISABLED), where) from None
    with db.tx():
        if page:
            # 页面最近登录时间即登录版本
            prev = db.scalar("SELECT last_login_at FROM sn_user WHERE id = %s FOR UPDATE", u["id"])
            u["last_login_at"] = _next_version(prev, util.now())
            db.exec("UPDATE sn_user SET last_login_at = %s WHERE id = %s", u["last_login_at"], u["id"])
        audit.record(_login_actor(emp, u, trace_id), audit.entry(audit.LOGIN).factory(u["factory_code"]).info(where))
    return {"token": _token(u, page), "user": view(u)}


def _token(u: dict, page: bool) -> str:
    sv = session_version(u["last_login_at"]) if page else None
    return issue_token(u["id"], pwd_version(u["pwd_changed_at"]), sv)


def me(cu: CurrentUser) -> dict:
    return view(_must_get(cu.id))


def change_password(cu: CurrentUser, old_password: str | None, new_password: str | None, page: bool = False) -> dict:
    """改自己的密码；返回新令牌（旧令牌随口令版本失效；页面令牌沿用当前登录版本）。"""
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
        return {"token": _token(fresh, page), "user": view(fresh)}


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
    for col, v in (("role", role), ("status", status)):
        if not util.blank(v):
            where += f" AND {col} = %s"
            args.append(util.trim(v))
    if not util.blank(factory):
        where += " AND (factory_code = %s OR id IN (SELECT user_id FROM sn_user_factory WHERE factory_code = %s))"
        args += [util.trim(factory), util.trim(factory)]
    total = db.count("SELECT COUNT(*) FROM sn_user" + where, *args)
    names = _factory_names()
    rows = db.all(f"SELECT * FROM sn_user{where} ORDER BY emp_no ASC LIMIT %s,%s", *args, page.offset, page.size)
    bound: dict[int, list[str]] = {}
    if rows:
        ids = [u["id"] for u in rows]
        for r in db.all(
            f"SELECT user_id, factory_code FROM sn_user_factory WHERE user_id IN ({marks(len(ids))}) "
            "ORDER BY factory_code",
            *ids,
        ):
            bound.setdefault(r["user_id"], []).append(r["factory_code"])
    return util.page_result([_view(u, names, bound.get(u["id"], [])) for u in rows], total, page)


@dataclass(frozen=True)
class Binding:
    """factory = 厂区操作员的工厂；query_factories = 查询员绑定的工厂（空 = 全部）。"""

    factory: str | None
    query_factories: tuple[str, ...]

    def codes(self) -> tuple[str, ...]:
        return (self.factory,) if self.factory is not None else self.query_factories

    def text(self) -> str:
        if self.factory is not None:
            return self.factory
        return ",".join(self.query_factories) or "*"


def _check_role_factory(
    role: str, factory_code: str | None, factory_codes: list[str | None] | None, current: tuple[str, ...] = ()
) -> Binding:
    """角色与绑定工厂；新绑定（不在 current 里）的工厂须存在且启用，保留原来已停用的绑定不报错。

    查询员可绑一个、多个或不绑（= 全部工厂）；只传 factory_code 时按绑一个处理。
    """
    if role not in util.ROLES:
        raise biz(ErrorCode.USER_ROLE_INVALID)
    f = util.trim_or_none(factory_code)
    many = list(dict.fromkeys(c for c in (util.trim(x) for x in factory_codes or []) if c))
    if role == util.ADMIN:
        if f is not None or many:
            raise biz(ErrorCode.USER_ADMIN_NO_FACTORY)
        return Binding(None, ())
    if role == util.FACTORY:
        f = f or (many[0] if len(many) == 1 else None)
        if f is None or len(many) > 1:
            raise biz(ErrorCode.USER_FACTORY_REQUIRED)
        if f not in current:
            factories.must_be_enabled(f)
        return Binding(f, ())
    codes = many if factory_codes is not None else ([f] if f is not None else [])
    for c in codes:
        if c not in current:
            factories.must_be_enabled(c)
    return Binding(None, tuple(sorted(codes)))


def _save_binding(uid: int, b: Binding) -> None:
    db.exec("DELETE FROM sn_user_factory WHERE user_id = %s", uid)
    for c in b.query_factories:
        db.exec("INSERT INTO sn_user_factory(user_id, factory_code) VALUES (%s,%s)", uid, c)


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
    factory_codes: list[str | None] | None = None,
) -> dict:
    with db.tx():
        emp = util.trim(emp_no)
        if not _EMP_NO.match(emp):
            raise biz(ErrorCode.USER_EMP_NO_INVALID)
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role)
        b = _check_role_factory(r, factory_code, factory_codes)
        check_policy(password)
        if _by_emp_no(emp) is not None:
            raise biz(ErrorCode.USER_EMP_NO_EXISTS, emp_no=emp)
        now = util.now()
        uid = db.insert(
            "INSERT INTO sn_user(emp_no, name, role, factory_code, status, password_hash, must_change_pwd, created_by, "
            "created_at, updated_at) VALUES (%s,%s,%s,%s,%s,%s,0,%s,%s,%s)",
            emp,
            n,
            r,
            b.factory,
            ACTIVE,
            hash_password(password),
            cu.emp_no,
            now,
            now,
        )
        _save_binding(uid, b)
        audit.record(
            cu, audit.entry(audit.USER_CREATE).factory(b.factory).info(f"emp_no={emp} role={r} factory={b.text()}")
        )
        return view(_must_get(uid))


def update(
    cu: CurrentUser,
    uid: int,
    name: str | None,
    role: str | None,
    factory_code: str | None,
    factory_codes: list[str | None] | None = None,
) -> dict:
    with db.tx():
        u = _must_get(uid)
        old = Binding(u["factory_code"], tuple(bound_factories(uid)))
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role)
        b = _check_role_factory(r, factory_code, factory_codes, old.codes())
        if u["emp_no"] == settings.init_admin_emp_no and r != util.ADMIN:
            raise biz(ErrorCode.USER_SUPER_ADMIN_FIXED)
        if u["role"] == util.ADMIN and r != util.ADMIN:
            _check_not_last_admin(u)
        db.exec(
            "UPDATE sn_user SET name = %s, role = %s, factory_code = %s, updated_at = %s WHERE id = %s",
            n,
            r,
            b.factory,
            util.now(),
            uid,
        )
        _save_binding(uid, b)
        audit.record(
            cu,
            audit.entry(audit.USER_UPDATE)
            .factory(b.factory)
            .info(f"emp_no={u['emp_no']} role={u['role']}->{r} factory={old.text()}->{b.text()}"),
        )
        return view(_must_get(uid))


def disable(cu: CurrentUser, uid: int) -> dict:
    with db.tx():
        u = _must_get(uid)
        if u["id"] == cu.id:
            raise biz(ErrorCode.USER_SELF_DISABLE)
        if u["emp_no"] == settings.init_admin_emp_no:
            raise biz(ErrorCode.USER_SUPER_ADMIN_FIXED)
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


def _admin_emp_no() -> str:
    emp = settings.init_admin_emp_no
    if not _EMP_NO.match(emp):
        raise RuntimeError(f"SN_INIT_ADMIN_EMP_NO={emp!r} 不是合法工号（2–32 位字母、数字、_ -）")
    return emp


def _insert_admin(emp: str, password: str, now: datetime) -> None:
    db.insert(
        "INSERT INTO sn_user(emp_no, name, role, status, password_hash, must_change_pwd, created_by, created_at, "
        "updated_at) VALUES (%s,'系统管理员',%s,%s,%s,0,'system',%s,%s)",
        emp,
        util.ADMIN,
        ACTIVE,
        hash_password(password),
        now,
        now,
    )


def reset_admin(password: str) -> None:
    """运维恢复：把内置管理员（SN_INIT_ADMIN_EMP_NO）重置为 .env 里的密码并启用、角色改回管理员；不存在就新建。

    只在服务器上设置 SN_RESET_ADMIN=true 启动时执行一次（需要服务器权限），并留痕。
    """
    emp = _admin_emp_no()
    with db.tx():
        u = _by_emp_no(emp)
        now = util.now()
        if u is None:
            _insert_admin(emp, password, now)
        else:
            db.exec(
                "UPDATE sn_user SET role = %s, factory_code = NULL, status = %s, password_hash = %s, "
                "must_change_pwd = 0, pwd_changed_at = %s, disabled_by = NULL, disabled_at = NULL, updated_at = %s "
                "WHERE id = %s",
                util.ADMIN,
                ACTIVE,
                hash_password(password),
                _next_pv(u, now),
                now,
                u["id"],
            )
            db.exec("DELETE FROM sn_user_factory WHERE user_id = %s", u["id"])
        audit.record(CurrentUser.system(), audit.entry(audit.USER_RESET_PWD).info(f"emp_no={emp} SN_RESET_ADMIN"))


def needs_bootstrap() -> bool:
    return db.count("SELECT COUNT(*) FROM sn_user") == 0


def bootstrap_admin(password: str) -> bool:
    """空库启动时按 .env 建首个管理员（工号 SN_INIT_ADMIN_EMP_NO、密码 SN_INIT_ADMIN_PASSWORD），可直接使用。"""
    emp = _admin_emp_no()
    with db.tx():
        if not needs_bootstrap():
            return False
        _insert_admin(emp, password, util.now())
        audit.record(CurrentUser.system(), audit.entry(audit.USER_CREATE).info(f"emp_no={emp} bootstrap"))
        return True
