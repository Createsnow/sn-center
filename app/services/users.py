"""登录、改密与账户管理（F-01）。账户可停用、启用或删除（系统管理员和自己不能删）；只有系统管理员能维护账户。

页面账户（系统管理员 / 普通操作员 / 审计员）用工号和密码直接登录，任何时候都不强制改密；
系统管理员只有一个（工号 SN_INIT_ADMIN_EMP_NO）；普通操作员、审计员必须至少授权一个生产组织；
本人改密、系统管理员重置密码后，该账户所有页面的令牌失效，须用新密码重新登录。
MES 接口账户不能登录页面：创建或重置时系统生成密钥（加密保存，系统管理员随时可查看），
MES 每次请求带 Authorization: Bearer <工号>:<密钥>；
连续失败 SN_MES_AUTH_MAX_FAILURES 次锁定 SN_MES_AUTH_LOCK_MINUTES 分钟（锁定期间密钥正确也拒绝），锁定和解锁留痕；
可配置来源地址白名单。重置密钥后旧密钥立即失效。
"""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import re
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta

from app.core import secretbox
from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError, MesError
from app.core.security import (
    API,
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
_EMP_NO = re.compile(r"^[A-Za-z0-9]{4,32}$")
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
        # 授权的生产组织：普通操作员、审计员 = 绑定的组织，MES 接口账户 = 本组织；系统管理员为空 = 全部
        "factory_codes": codes,
        "factory_names": [names.get(c, c) for c in codes],
        "status": u["status"],
        "super_admin": util.is_super_admin(u["emp_no"], u["role"]),
        "ip_whitelist": u["ip_whitelist"] if u["role"] == util.MES else None,
        "locked_until": util.fmt(u["locked_until"]) if u["role"] == util.MES else None,
        "tz_offset_min": factories.tz_offset(tuple(codes)),
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
    """普通操作员、审计员授权的生产组织。"""
    return db.column("SELECT factory_code FROM sn_user_factory WHERE user_id = %s ORDER BY factory_code", uid)


def read_scope(uid: int, role: str, factory_code: str | None) -> tuple[str, ...]:
    """账户授权的生产组织；系统管理员为空 = 全部（其他角色为空 = 一个也没有，鉴权时拒绝）。"""
    if role == util.MES:
        return (factory_code,) if factory_code else ()
    if role in util.SCOPED_ROLES:
        return tuple(bound_factories(uid))
    return ()


def view(u: dict) -> dict:
    return _view(u, _factory_names(), bound_factories(u["id"]) if u["role"] in util.SCOPED_ROLES else [])


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
    if u["role"] == util.MES:
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.MES_NO_PAGE_LOGIN), where) from None
    if u["role"] in util.SCOPED_ROLES and not bound_factories(u["id"]):
        raise _login_failed(_login_actor(emp, u, trace_id), biz(ErrorCode.USER_NO_FACTORY), where) from None
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


def change_password(cu: CurrentUser, old_password: str | None, new_password: str | None) -> dict:
    """改自己的密码。不发新令牌：旧令牌随口令版本全部失效（包括当前页面），须用新密码重新登录。"""
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
        return {"relogin": True}


def set_lang(cu: CurrentUser, lang: str | None) -> dict:
    lng = util.trim_or_none(lang)
    if lng is not None and lng not in LANGS:
        lng = None
    db.exec("UPDATE sn_user SET lang = %s WHERE id = %s", lng, cu.id)
    return view(_must_get(cu.id))


def list_users(
    q: str | None, role: str | None, status: str | None, factory: str | None, page: util.Page, kind: str | None = None
) -> dict:
    """kind：page = 账户页签（页面账户，不含 MES 接口账户），mes = MES 接口账户页签；空 = 全部。"""
    where = " WHERE 1=1"
    args: list = []
    k = util.trim(kind)
    if k == "page":
        where += " AND role <> %s"
        args.append(util.MES)
    elif k == "mes":
        where += " AND role = %s"
        args.append(util.MES)
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
    """factory = MES 接口账户的生产组织；scope = 普通操作员、审计员授权的生产组织（至少一个）。"""

    factory: str | None
    scope: tuple[str, ...]

    def codes(self) -> tuple[str, ...]:
        return (self.factory,) if self.factory is not None else self.scope

    def text(self) -> str:
        if self.factory is not None:
            return self.factory
        return ",".join(self.scope) or "*"


def _check_role_factory(
    role: str,
    factory_code: str | None,
    factory_codes: list[str | None] | None,
    current: tuple[str, ...] = (),
    admin_ok: bool = False,
) -> Binding:
    """角色与授权生产组织；新授权（不在 current 里）的须在列表中且金蝶启用，保留原来已禁用的授权不报错。

    系统管理员只有一个、不绑组织（admin_ok = 修改的正是它）；普通操作员、审计员可多选、至少一个；
    MES 接口账户绑一个生产组织，不要求已开启「对接 MES」（先建账户交给 MES 方联调，联调通过后再开启；
    未开启时取批只返回本组织的转出批次，回执和只读查询照常）。
    """
    if role not in util.ROLES:
        raise biz(ErrorCode.USER_ROLE_INVALID)
    f = util.trim_or_none(factory_code)
    many = list(dict.fromkeys(c for c in (util.trim(x) for x in factory_codes or []) if c))
    if role == util.ADMIN:
        if not admin_ok:
            raise biz(ErrorCode.USER_ADMIN_UNIQUE)
        if f is not None or many:
            raise biz(ErrorCode.USER_ADMIN_NO_FACTORY)
        return Binding(None, ())
    if role == util.MES:
        f = f or (many[0] if len(many) == 1 else None)
        if f is None or len(many) > 1:
            raise biz(ErrorCode.USER_MES_FACTORY_REQUIRED)
        if f not in current:
            factories.must_be_enabled(f)
        return Binding(f, ())
    codes = many if factory_codes is not None else ([f] if f is not None else [])
    if not codes:
        raise biz(ErrorCode.USER_FACTORY_REQUIRED)
    for c in codes:
        if c not in current:
            factories.must_be_enabled(c)
    return Binding(None, tuple(sorted(codes)))


_IP = re.compile(r"^[0-9A-Fa-f:.]+(/\d{1,3})?$")


def _ip_list(raw: str | None) -> str:
    """来源地址白名单：逗号或空白分隔的 IP / CIDR；空 = 不限。"""
    items = [x for x in re.split(r"[\s,;]+", util.trim(raw)) if x]
    for x in items:
        try:
            if not _IP.match(x):
                raise ValueError
            ipaddress.ip_network(x, strict=False)
        except ValueError:
            raise biz(ErrorCode.USER_IP_LIST_INVALID, value=x) from None
    out = ",".join(dict.fromkeys(items))
    if len(out) > 1000:
        raise biz(ErrorCode.USER_IP_LIST_INVALID, value=out[:40] + "…")
    return out


def ip_allowed(whitelist: str, client: str | None) -> bool:
    if not whitelist:
        return True
    if not client:
        return False
    try:
        addr = ipaddress.ip_address(client)
    except ValueError:
        return False
    for x in whitelist.split(","):
        try:
            if addr in ipaddress.ip_network(x, strict=False):
                return True
        except ValueError:
            continue
    return False


def new_secret() -> str:
    return secrets.token_urlsafe(24)


def _save_binding(uid: int, b: Binding) -> None:
    db.exec("DELETE FROM sn_user_factory WHERE user_id = %s", uid)
    for c in b.scope:
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
    ip_whitelist: str | None = None,
) -> dict:
    """新建账户。MES 接口账户不填密码：系统生成密钥，本次返回（secret），之后可查看、可重置。"""
    with db.tx():
        emp = util.trim(emp_no)
        if not _EMP_NO.match(emp):
            raise biz(ErrorCode.USER_EMP_NO_INVALID)
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role)
        b = _check_role_factory(r, factory_code, factory_codes)
        secret = None
        if r == util.MES:
            secret = new_secret()
            password = secret
            ips = _ip_list(ip_whitelist)
        else:
            check_policy(password)
            ips = ""
        if _by_emp_no(emp) is not None:
            raise biz(ErrorCode.USER_EMP_NO_EXISTS, emp_no=emp)
        now = util.now()
        uid = db.insert(
            "INSERT INTO sn_user(emp_no, name, role, factory_code, status, password_hash, secret_sealed, "
            "must_change_pwd, pwd_changed_at, ip_whitelist, created_by, created_at, updated_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s)",
            emp,
            n,
            r,
            b.factory,
            ACTIVE,
            hash_password(password),
            secretbox.seal(secret) if secret else "",
            now,
            ips,
            cu.emp_no,
            now,
            now,
        )
        _save_binding(uid, b)
        audit.record(
            cu,
            audit.entry(audit.USER_CREATE)
            .factory(b.factory)
            .info(f"emp_no={emp} role={r} factory={b.text()}" + (" secret=generated" if secret else "")),
        )
        out = view(_must_get(uid))
        if secret is not None:
            out["secret"] = secret
        return out


def update(
    cu: CurrentUser,
    uid: int,
    name: str | None,
    role: str | None,
    factory_code: str | None,
    factory_codes: list[str | None] | None = None,
    ip_whitelist: str | None = None,
) -> dict:
    with db.tx():
        u = _must_get(uid)
        old = Binding(u["factory_code"], tuple(bound_factories(uid)))
        n = util.trim(name)
        if not n or len(n) > 64:
            raise biz(ErrorCode.USER_NAME_REQUIRED)
        r = util.trim(role) or u["role"]
        if (r == util.MES) != (u["role"] == util.MES):
            raise biz(ErrorCode.USER_ROLE_FIXED)
        super_admin = u["emp_no"] == settings.init_admin_emp_no
        if super_admin and r != util.ADMIN:
            raise biz(ErrorCode.USER_SUPER_ADMIN_FIXED)
        b = _check_role_factory(r, factory_code, factory_codes, old.codes(), admin_ok=super_admin)
        if u["role"] == util.ADMIN and r != util.ADMIN:
            _check_not_last_admin(u)
        ips = _ip_list(ip_whitelist) if r == util.MES and ip_whitelist is not None else u["ip_whitelist"]
        db.exec(
            "UPDATE sn_user SET name = %s, role = %s, factory_code = %s, ip_whitelist = %s, updated_at = %s "
            "WHERE id = %s",
            n,
            r,
            b.factory,
            ips,
            util.now(),
            uid,
        )
        _save_binding(uid, b)
        audit.record(
            cu,
            audit.entry(audit.USER_UPDATE)
            .factory(b.factory)
            .info(
                f"emp_no={u['emp_no']} role={u['role']}->{r} factory={old.text()}->{b.text()}"
                + (f" ip={u['ip_whitelist'] or '*'}->{ips or '*'}" if r == util.MES else "")
            ),
        )
        return view(_must_get(uid))


def reset_secret(cu: CurrentUser, uid: int) -> dict:
    """重置 MES 接口账户密钥：生成新密钥（之后仍可查看）；旧密钥立即失效，解除锁定。"""
    with db.tx():
        u = _must_get(uid)
        if u["role"] != util.MES:
            raise biz(ErrorCode.USER_NOT_MES)
        secret = new_secret()
        now = util.now()
        db.exec(
            "UPDATE sn_user SET password_hash = %s, secret_sealed = %s, pwd_changed_at = %s, auth_failures = 0, "
            "locked_until = NULL, updated_at = %s WHERE id = %s",
            hash_password(secret),
            secretbox.seal(secret),
            _next_pv(u, now),
            now,
            uid,
        )
        audit.record(cu, audit.entry(audit.USER_RESET_SECRET).factory(u["factory_code"]).info(f"emp_no={u['emp_no']}"))
        out = view(_must_get(uid))
        out["secret"] = secret
        return out


def view_secret(cu: CurrentUser, uid: int) -> dict:
    """查看 MES 接口账户当前密钥（留痕）。解不开（更换过 SN_SECRET 或早于加密保存）时须重置。"""
    u = _must_get(uid)
    if u["role"] != util.MES:
        raise biz(ErrorCode.USER_NOT_MES)
    secret = secretbox.open_(u["secret_sealed"])
    if not secret:
        raise biz(ErrorCode.USER_SECRET_UNAVAILABLE)
    audit.record_isolated(
        cu, audit.entry(audit.USER_VIEW_SECRET).factory(u["factory_code"]).info(f"emp_no={u['emp_no']}")
    )
    return {"id": u["id"], "emp_no": u["emp_no"], "secret": secret}


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


def delete(cu: CurrentUser, uid: int) -> dict:
    """删除账户（页面账户或 MES 接口账户）：自己、系统管理员不能删。

    删除后该账户的令牌、MES 密钥立即失效；痕迹与业务记录按工号保存，不随账户删除。
    """
    with db.tx():
        u = _must_get(uid)
        if u["id"] == cu.id:
            raise biz(ErrorCode.USER_SELF_DELETE)
        if u["emp_no"] == settings.init_admin_emp_no:
            raise biz(ErrorCode.USER_SUPER_ADMIN_FIXED)
        _check_not_last_admin(u)
        out = view(u)
        db.exec("DELETE FROM sn_user_factory WHERE user_id = %s", uid)
        db.exec("DELETE FROM sn_user WHERE id = %s", uid)
        audit.record(
            cu,
            audit.entry(audit.USER_DELETE)
            .factory(u["factory_code"])
            .info(f"emp_no={u['emp_no']} role={u['role']} factory={','.join(out['factory_codes']) or '-'}"),
        )
    _verified.pop(uid, None)
    return out


def reset_password(cu: CurrentUser, uid: int, password: str | None) -> dict:
    """重置为系统管理员给定的密码：旧令牌全部失效，该账户用新密码重新登录（不强制改密）。"""
    with db.tx():
        u = _must_get(uid)
        if u["role"] == util.MES:
            raise biz(ErrorCode.USER_ROLE_FIXED)
        check_policy(password)
        now = util.now()
        db.exec(
            "UPDATE sn_user SET password_hash = %s, must_change_pwd = 0, pwd_changed_at = %s, updated_at = %s "
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
        raise RuntimeError(f"SN_INIT_ADMIN_EMP_NO={emp!r} 不是合法工号（4–32 位字母和数字）")
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
    """运维恢复：把系统管理员（SN_INIT_ADMIN_EMP_NO）重置为 .env 里的密码并启用、角色改回管理员；不存在就新建。

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


# ================================================================== MES 接口账户认证

#: 已验过的密钥：账户 id -> (口令版本, 密钥摘要)。PBKDF2 每次要上百毫秒，验过一次后按摘要比对；
#: 重置密钥会改口令版本，缓存随之失效。
_verified: dict[int, tuple[int, bytes]] = {}
#: 「最近认证」时间最多每分钟写一次
_SEEN_INTERVAL = timedelta(minutes=1)


def mes_authenticate(emp_no: str | None, secret: str | None, client: str | None, trace_id: str | None) -> dict:
    """MES 接口 ②～⑤ 的认证：工号 + 密钥（请求头 Authorization: Bearer <工号>:<密钥>），每次请求都验，返回账户行。

    错误：401 INVALID_CREDENTIALS；连续错 N 次锁定 M 分钟，锁定期间返回 423 ACCOUNT_LOCKED（密钥正确也拒绝）；
    403 IP_NOT_ALLOWED / ACCOUNT_DISABLED。失败、锁定、解锁都留痕。
    """
    emp = util.trim(emp_no)
    u = _by_emp_no(emp) if emp else None
    if u is not None and u["role"] != util.MES:
        u = None
    actor = CurrentUser(
        None if u is None else u["id"],
        util.cut(emp, 32) or "-",
        "" if u is None else u["name"],
        util.MES,
        None if u is None else u["factory_code"],
        API,
        trace_id,
    )
    where = f"client={client or '-'}"

    def fail(status: int, code: str, message: str) -> MesError:
        audit.record_isolated(actor, audit.entry(audit.MES_AUTH).factory(actor.factory_code).fail(message).info(where))
        return MesError(status, code, message)

    pv = None if u is None else pwd_version(u["pwd_changed_at"])
    digest = hashlib.sha256((secret or "").encode("utf-8")).digest()
    cached = None if u is None else _verified.get(u["id"])
    if cached is not None and cached[0] == pv and hmac.compare_digest(cached[1], digest):
        matched = True
    else:
        matched = verify_password(secret, _dummy() if u is None else u["password_hash"])
    if u is None:
        raise fail(401, "INVALID_CREDENTIALS", "工号或密钥错误")
    if u["status"] != ACTIVE:
        raise fail(403, "ACCOUNT_DISABLED", "账户已停用")
    if not ip_allowed(u["ip_whitelist"], client):
        raise fail(403, "IP_NOT_ALLOWED", f"来源地址 {client or '-'} 不在白名单内")
    now = util.now()
    locked = u["locked_until"]
    if matched and not u["auth_failures"] and locked is None:
        # 常规路径：不加锁；「最近认证」每分钟最多写一次
        _verified[u["id"]] = (pv, digest)
        if u["last_login_at"] is None or now - u["last_login_at"] >= _SEEN_INTERVAL:
            db.exec("UPDATE sn_user SET last_login_at = %s WHERE id = %s", now, u["id"])
        return u
    with db.tx():
        row = db.one("SELECT auth_failures, locked_until FROM sn_user WHERE id = %s FOR UPDATE", u["id"])
        locked = row["locked_until"]
        if locked is not None and locked > now:
            raise fail(423, "ACCOUNT_LOCKED", f"连续认证失败已锁定，{util.fmt(locked)} 后解锁")
        failures = row["auth_failures"]
        if locked is not None:
            # 锁定期已过：解锁（留痕），重新计数
            db.exec("UPDATE sn_user SET auth_failures = 0, locked_until = NULL WHERE id = %s", u["id"])
            audit.record(actor, audit.entry(audit.MES_AUTH_UNLOCK).factory(u["factory_code"]).info(where))
            failures = 0
        if not matched:
            failures += 1
            lock = failures >= settings.mes_auth_max_failures
            until = now + timedelta(minutes=settings.mes_auth_lock_minutes) if lock else None
            db.exec(
                "UPDATE sn_user SET auth_failures = %s, locked_until = %s WHERE id = %s",
                0 if lock else failures,
                until,
                u["id"],
            )
            if lock:
                audit.record(
                    actor,
                    audit.entry(audit.MES_AUTH_LOCK)
                    .factory(u["factory_code"])
                    .info(f"{where} until={util.fmt(until)}"),
                )
        else:
            db.exec("UPDATE sn_user SET auth_failures = 0, last_login_at = %s WHERE id = %s", now, u["id"])
    if not matched:
        raise fail(401, "INVALID_CREDENTIALS", "工号或密钥错误")
    _verified[u["id"]] = (pv, digest)
    return u
