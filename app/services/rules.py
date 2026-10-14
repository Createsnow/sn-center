"""SN 规则。生效：按 PI 绑定 ＞ 给该 PI 指定的规则 ＞ 按客户绑定；都没有时，由用户在生成页从规则库选一条规则
（通用规则或别的 PI 的规则），或手动设置一条新规则。

全局不重：
- 前缀区分模板：任何两条规则的前缀不能互为开头（AX 与 AXB、A 与 AX 都不行），前缀必填。
  于是不同规则生成的号在前缀部分必有一位不同。
- 同一条规则被多张 PI 使用时，每张 PI 用自己的一版：最先用它生成的 PI 直接用当前版本；
  别的 PI 再用，另出一个派生版，后缀 = 原后缀 + _v版本号（版本号在规则内统一递增），流水仍从本 PI 自己的最大号接续。
  PI 首次用某条规则生成时固定下所用版本，之后一直沿用；规则改了参数（另出新版）后，各 PI 按上面的办法重新确定。
- 兜底：sn_item.sn 全局唯一键。

归类（category_scope / category_value）：规则库只维护归类——未分类 / 客户 / PI，一个对象下可以有多条，
只用于规则库筛选和生成页查找，不影响生效；绑定给 PI 在生成页完成。规则库新建的规则都是通用规则。

生成页手动设置的规则：前缀 + 进制 + 流水位数 + 后缀（字符表用该进制的系统默认表），
生成成功后自动存为按 PI 绑定的规则（归类为该 PI），名称 PI号_v序号；生成失败或取消不留规则。
按 PI 绑定的规则在生成页解绑后转为通用规则，可作模板给别的 PI 用。

改前缀 / 后缀 / 进制 / 位数 / 字符集：该版本还没生成过号时就地修改，否则另出一版；只改名称不出版本。
新版本只作用于之后新生成的号，已发出的号仍记旧版本。
"""

from __future__ import annotations

import re
import threading
from dataclasses import dataclass, replace

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, orders

GENERAL = "GENERAL"
CUSTOMER = "CUSTOMER"
PI = "PI"
SCOPES = frozenset((GENERAL, CUSTOMER, PI))
#: 归类：未分类 / 客户 / PI
NONE = "NONE"
CATEGORIES = frozenset((NONE, CUSTOMER, PI))
_CODE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
#: 前缀、后缀：字母、数字和 _
_AFFIX = re.compile(r"^[A-Za-z0-9_]*$")
#: 生成页手动设置的规则不选进制时默认 10 进制
MANUAL_BASE = 10

RULE_FIELDS = (
    "id",
    "rule_code",
    "rule_name",
    "bind_scope",
    "bind_value",
    "category_scope",
    "category_value",
    "current_version",
    "created_by",
    "created_at",
    "updated_by",
    "updated_at",
)
VERSION_FIELDS = (
    "id",
    "rule_id",
    "version",
    "prefix",
    "suffix",
    "base",
    "seq_len",
    "charset",
    "used",
    "claimed_pi",
    "derived_from",
    "created_by",
    "created_at",
    "updated_at",
)


def rule_json(r: dict | None) -> dict | None:
    return util.jrow(r, RULE_FIELDS)


def version_json(v: dict | None) -> dict | None:
    return util.jrow(v, VERSION_FIELDS, ("used",))


def spec_of(v: dict) -> codec.Spec:
    return codec.Spec(v["prefix"], v["suffix"], v["base"], v["seq_len"], v["charset"])


#: 规则来源：按 PI 绑定 / 按客户绑定 / 给该 PI 指定的 / 本次所选模板 / 本次手动设置
SRC_PI = "PI"
SRC_CUSTOMER = "CUSTOMER"
SRC_CHOSEN = "CHOSEN"
SRC_PICKED = "PICKED"
SRC_MANUAL = "MANUAL"

#: 本 PI 用哪一版：已固定的 / 该版还没有 PI 用过（直接用）/ 已被别的 PI 用过（另出派生版）/ 手动设置（新建规则）
PLAN_USE = "USE"
PLAN_CLAIM = "CLAIM"
PLAN_DERIVE = "DERIVE"
PLAN_NEW = "NEW"


@dataclass(frozen=True)
class Resolved:
    rule: dict
    #: 派生版 / 手动设置时是尚未落库的版本（id 为 None），生成时由 realize 落库
    version: dict
    source: str = SRC_PICKED
    plan: str = PLAN_USE
    #: 派生版：当前版本已被哪张 PI 使用
    shared_by: str | None = None

    @property
    def fixed(self) -> bool:
        """已确定、生成时不再让用户选：有绑定或该 PI 已指定。"""
        return self.source in (SRC_PI, SRC_CUSTOMER, SRC_CHOSEN)


@dataclass(frozen=True)
class SpecIn:
    prefix: str | None
    suffix: str | None
    base: int | None
    seq_len: int | None
    charset: str | None


# ---------------------------------------------------------------- 查询


def _versions_of(rule_id: int) -> list[dict]:
    return db.all("SELECT * FROM sn_rule_version WHERE rule_id = %s ORDER BY version DESC", rule_id)


def view(r: dict) -> dict:
    vs = _versions_of(r["id"])
    cur = next((v for v in vs if v["version"] == r["current_version"]), None)
    return {"rule": rule_json(r), "current": version_json(cur), "versions": [version_json(v) for v in vs]}


def list_rules(
    scope: str | None, q: str | None, category: str | None = None, category_value: str | None = None
) -> list[dict]:
    sql = "SELECT * FROM sn_rule WHERE 1=1"
    args: list = []
    if not util.blank(scope):
        sql += " AND bind_scope = %s"
        args.append(util.trim(scope))
    if not util.blank(category):
        sql += " AND category_scope = %s"
        args.append(util.trim(category))
    if not util.blank(category_value):
        sql += " AND category_value = %s"
        args.append(util.trim(category_value))
    text = util.trim(q)
    if text:
        like = "%" + text + "%"
        sql += " AND (rule_code LIKE %s OR rule_name LIKE %s OR bind_value LIKE %s OR category_value LIKE %s)"
        args += [like, like, like, like]
    sql += " ORDER BY category_scope = %s DESC, category_scope ASC, category_value ASC, rule_code ASC"
    args.append(NONE)
    return [view(r) for r in db.all(sql, *args)]


def current_version(r: dict) -> dict | None:
    return db.one("SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = %s", r["id"], r["current_version"])


def normalize(s: SpecIn) -> codec.Spec:
    """规范化并校验编码参数；字符集留空取该进制系统默认字符表。"""
    if s.base is None or s.base not in codec.DEFAULT_CHARSETS:
        raise biz(ErrorCode.RULE_BASE_INVALID)
    if s.seq_len is None:
        raise biz(ErrorCode.RULE_SEQ_LEN_INVALID)
    cs = util.trim(s.charset) or codec.DEFAULT_CHARSETS[s.base]
    spec = codec.Spec(util.trim(s.prefix), util.trim(s.suffix), s.base, s.seq_len, cs)
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    return spec


def check_affixes(spec: codec.Spec, self_rule_id: int | None = None) -> None:
    """新参数的前后缀：前缀必填，前后缀只能是字母、数字和 _，前缀不能与别的规则的前缀互为开头。

    比较别的规则的全部版本（含派生版）；没有前缀的旧规则不参与比较（由 SN 全局唯一键兜底）。
    前缀里的 _ 在 LIKE 里是通配符：SQL 只做粗筛，命中后再按 startswith 精确判断。
    """
    if not spec.prefix:
        raise biz(ErrorCode.RULE_PREFIX_REQUIRED)
    if not _AFFIX.match(spec.prefix) or not _AFFIX.match(spec.suffix):
        raise biz(ErrorCode.RULE_AFFIX_INVALID)
    rows = db.all(
        "SELECT DISTINCT r.id, r.rule_name, v.prefix FROM sn_rule_version v JOIN sn_rule r ON r.id = v.rule_id "
        "WHERE v.prefix <> '' AND (%s LIKE CONCAT(v.prefix, '%%') OR v.prefix LIKE CONCAT(%s, '%%')) "
        "ORDER BY r.id",
        spec.prefix,
        spec.prefix,
    )
    for row in rows:
        if row["id"] != self_rule_id and (
            spec.prefix.startswith(row["prefix"]) or row["prefix"].startswith(spec.prefix)
        ):
            raise biz(
                ErrorCode.RULE_PREFIX_CONFLICT, prefix=spec.prefix, rule_name=row["rule_name"], other=row["prefix"]
            )


def preview(s: SpecIn, start: int | None) -> dict:
    spec = normalize(s)
    mx = codec.max_seq(spec)
    st = 1 if start is None or start < 0 else start
    samples = []
    i = st
    while i < st + 5 and i <= mx:
        samples.append({"seq_dec": i, "seq_text": codec.encode_seq(i, spec), "sn": codec.format_sn(i, spec)})
        i += 1
    if mx > st + 5:
        samples.append({"seq_dec": mx, "seq_text": codec.encode_seq(mx, spec), "sn": codec.format_sn(mx, spec)})
    return {"max_seq": mx, "samples": samples}


def describe(s: codec.Spec) -> str:
    return f"prefix={s.prefix} suffix={s.suffix} base={s.base} len={s.seq_len} charset={s.charset}"


def _insert_version(
    cu: CurrentUser,
    rule_id: int,
    version: int,
    spec: codec.Spec,
    now,
    *,
    used: bool = False,
    claimed_pi: str | None = None,
    derived_from: int | None = None,
) -> int:
    return db.insert(
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, used, claimed_pi, "
        "derived_from, created_by, created_at, updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        rule_id,
        version,
        spec.prefix,
        spec.suffix,
        spec.base,
        spec.seq_len,
        spec.charset,
        1 if used else 0,
        claimed_pi,
        derived_from,
        cu.emp_no,
        now,
        now,
    )


# ---------------------------------------------------------------- 维护


def create(
    cu: CurrentUser,
    code: str | None,
    name: str | None,
    scope: str | None,
    bind_value: str | None,
    s: SpecIn,
    category: str | None = None,
    category_value: str | None = None,
) -> dict:
    """规则库新建：不传绑定方式时为通用规则（绑定在生成页做）；归类不传时同绑定对象，都没有为未分类。"""
    with db.tx():
        c = util.trim(code)
        if not _CODE.match(c):
            raise biz(ErrorCode.RULE_CODE_INVALID)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        sc, bv = _check_bind(GENERAL if util.blank(scope) else scope, bind_value)
        cat = _check_category(category, category_value) if not util.blank(category) else _category_of_bind(sc, bv)
        spec = normalize(s)
        check_affixes(spec)
        if db.count("SELECT COUNT(*) FROM sn_rule WHERE rule_code = %s", c) > 0:
            raise biz(ErrorCode.RULE_CODE_EXISTS, rule_code=c)
        _check_bind_free(sc, bv)
        rid = _insert_rule(cu, c, n, sc, bv, spec, category=cat)
        return view(db.one("SELECT * FROM sn_rule WHERE id = %s", rid))


def _insert_rule(
    cu: CurrentUser,
    code: str,
    name: str,
    sc: str,
    bv: str,
    spec: codec.Spec,
    *,
    claimed_pi: str | None = None,
    note: str = "",
    category: tuple[str, str] | None = None,
) -> int:
    now = util.now()
    cs, cv = category or _category_of_bind(sc, bv)
    rid = db.insert(
        "INSERT INTO sn_rule(rule_code, rule_name, bind_scope, bind_value, category_scope, category_value, "
        "current_version, created_by, created_at, updated_by, updated_at) VALUES (%s,%s,%s,%s,%s,%s,1,%s,%s,%s,%s)",
        code,
        name,
        sc,
        bv,
        cs,
        cv,
        cu.emp_no,
        now,
        cu.emp_no,
        now,
    )
    _insert_version(cu, rid, 1, spec, now, used=claimed_pi is not None, claimed_pi=claimed_pi)
    audit.record(
        cu,
        audit.entry(audit.RULE_CREATE)
        .pi(bv if sc == PI else None)
        .customer(bv if sc == CUSTOMER else None)
        .info(f"{code} v1 {describe(spec)}{note}"),
    )
    return rid


def _check_bind(scope: str | None, bind_value: str | None) -> tuple[str, str]:
    sc = util.trim(scope)
    if sc not in SCOPES:
        raise biz(ErrorCode.RULE_SCOPE_INVALID)
    bv = "" if sc == GENERAL else util.trim(bind_value)
    if sc != GENERAL and not bv:
        raise biz(ErrorCode.RULE_BIND_REQUIRED)
    return sc, bv


def _check_category(category: str | None, value: str | None) -> tuple[str, str]:
    """归类：未分类 / 客户 / PI；客户、PI 须给对象。只是归类，同一对象下可以有多条。"""
    cs = util.trim(category) or NONE
    if cs not in CATEGORIES:
        raise biz(ErrorCode.RULE_CATEGORY_INVALID)
    cv = "" if cs == NONE else util.trim(value)
    if cs != NONE and not cv:
        raise biz(ErrorCode.RULE_CATEGORY_REQUIRED)
    if len(cv) > 64:
        raise biz(ErrorCode.RULE_CATEGORY_REQUIRED)
    return cs, cv


def _category_of_bind(sc: str, bv: str) -> tuple[str, str]:
    return (NONE, "") if sc == GENERAL else (sc, bv)


def _category_text(cs: str, cv: str) -> str:
    return cs if cs == NONE else f"{cs}:{cv}"


def _check_bind_free(sc: str, bv: str, self_id: int | None = None) -> None:
    """按客户 / 按 PI 每个对象只能一条；通用规则可以有多条。"""
    if sc == GENERAL:
        return
    dup = db.one("SELECT id, rule_code FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", sc, bv)
    if dup is not None and dup["id"] != self_id:
        raise biz(ErrorCode.RULE_BIND_EXISTS, rule_code=dup["rule_code"])


def _bind_text(sc: str, bv: str) -> str:
    return sc if sc == GENERAL else f"{sc}:{bv}"


def update(
    cu: CurrentUser,
    rule_id: int,
    name: str | None,
    s: SpecIn,
    scope: str | None = None,
    bind_value: str | None = None,
    category: str | None = None,
    category_value: str | None = None,
) -> dict:
    """修改结果：RENAMED 只改名 / RECATEGORIZED 改了归类 / REBOUND 改了绑定 / UPDATED 就地修改当前版 /
    NEW_VERSION 另出一版 / UNCHANGED。

    不传绑定方式时绑定不变（规则库页不传，绑定在生成页做）；改成通用即解绑。不传归类时归类不变。
    """
    with db.tx():
        r = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", rule_id)
        if r is None:
            raise biz(ErrorCode.RULE_NOT_FOUND)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        spec = normalize(s)
        cur = db.one(
            "SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = %s FOR UPDATE",
            r["id"],
            r["current_version"],
        )
        sc, bv = (r["bind_scope"], r["bind_value"]) if scope is None else _check_bind(scope, bind_value)
        rebound = (sc, bv) != (r["bind_scope"], r["bind_value"])
        cs, cv = (
            (r["category_scope"], r["category_value"])
            if category is None
            else _check_category(category, category_value)
        )
        recategorized = (cs, cv) != (r["category_scope"], r["category_value"])
        if rebound:
            _check_bind_free(sc, bv, r["id"])
        spec_changed = spec != spec_of(cur)
        if spec_changed:
            check_affixes(spec, r["id"])
        before = describe(spec_of(cur))
        renamed = n != r["rule_name"]
        now = util.now()
        action = "UNCHANGED"
        version = r["current_version"]
        if spec_changed:
            if cur["used"]:
                version = _next_version(r["id"])
                _insert_version(cu, r["id"], version, spec, now)
                action = "NEW_VERSION"
                audit.record(
                    cu,
                    audit.entry(audit.RULE_VERSION).info(
                        f"{r['rule_code']} v{version} {describe(spec)} (was {before})"
                    ),
                )
            else:
                db.exec(
                    "UPDATE sn_rule_version SET prefix = %s, suffix = %s, base = %s, seq_len = %s, charset = %s, "
                    "updated_at = %s WHERE id = %s",
                    spec.prefix,
                    spec.suffix,
                    spec.base,
                    spec.seq_len,
                    spec.charset,
                    now,
                    cur["id"],
                )
                _spec_cache.pop(cur["id"], None)
                action = "UPDATED"
                audit.record(
                    cu,
                    audit.entry(audit.RULE_UPDATE).info(
                        f"{r['rule_code']} v{cur['version']} {describe(spec)} (was {before})"
                    ),
                )
        if rebound:
            _audit_rebind(cu, r, sc, bv)
            if action == "UNCHANGED":
                action = "REBOUND"
        if recategorized:
            audit.record(
                cu,
                audit.entry(audit.RULE_CATEGORY)
                .pi(cv if cs == PI else None)
                .customer(cv if cs == CUSTOMER else None)
                .info(
                    f"{r['rule_code']} {_category_text(r['category_scope'], r['category_value'])} -> "
                    f"{_category_text(cs, cv)}"
                ),
            )
            if action == "UNCHANGED":
                action = "RECATEGORIZED"
        if renamed:
            audit.record(cu, audit.entry(audit.RULE_RENAME).info(f"{r['rule_code']} {r['rule_name']} -> {n}"))
            if action == "UNCHANGED":
                action = "RENAMED"
        if spec_changed or renamed or rebound or recategorized:
            db.exec(
                "UPDATE sn_rule SET rule_name = %s, bind_scope = %s, bind_value = %s, category_scope = %s, "
                "category_value = %s, current_version = %s, updated_by = %s, updated_at = %s WHERE id = %s",
                n,
                sc,
                bv,
                cs,
                cv,
                version,
                cu.emp_no,
                now,
                r["id"],
            )
        return {"action": action, "rule": view(db.one("SELECT * FROM sn_rule WHERE id = %s", r["id"]))}


def delete(cu: CurrentUser, rule_id: int) -> dict:
    """删除规则（连同全部版本）。只能删从没生成过号、也没被指定给任何 PI 的规则；已发出的号永远能查到所用规则。"""
    with db.tx():
        r = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", rule_id)
        if r is None:
            raise biz(ErrorCode.RULE_NOT_FOUND)
        vs = db.all("SELECT * FROM sn_rule_version WHERE rule_id = %s FOR UPDATE", r["id"])
        # used 一经置上不再清：生成成功时在锁住本规则的同一事务里置上，所以这里锁住规则后判断即可
        if any(v["used"] for v in vs):
            raise biz(ErrorCode.RULE_IN_USE, rule_code=r["rule_code"])
        pi = db.scalar("SELECT pi_no FROM sn_pi_counter WHERE rule_id = %s LIMIT 1", r["id"])
        if pi is not None:
            raise biz(ErrorCode.RULE_ASSIGNED, rule_code=r["rule_code"], pi=pi)
        ids = [v["id"] for v in vs]
        db.exec("DELETE FROM sn_rule_version WHERE rule_id = %s", r["id"])
        db.exec("DELETE FROM sn_rule WHERE id = %s", r["id"])
        with _cache_lock:
            for i in ids:
                _spec_cache.pop(i, None)
        cur = next((v for v in vs if v["version"] == r["current_version"]), None)
        audit.record(
            cu,
            audit.entry(audit.RULE_DELETE)
            .pi(r["bind_value"] if r["bind_scope"] == PI else None)
            .customer(r["bind_value"] if r["bind_scope"] == CUSTOMER else None)
            .info(
                f"{r['rule_code']} {r['rule_name']} v{r['current_version']}"
                f"{' ' + describe(spec_of(cur)) if cur else ''} {_bind_text(r['bind_scope'], r['bind_value'])}"
            ),
        )
        return {"id": r["id"], "rule_code": r["rule_code"]}


def _audit_rebind(cu: CurrentUser, r: dict, sc: str, bv: str) -> None:
    old = _bind_text(r["bind_scope"], r["bind_value"])
    audit.record(
        cu,
        audit.entry(audit.RULE_REBIND)
        .pi(bv if sc == PI else r["bind_value"] if r["bind_scope"] == PI else None)
        .customer(bv if sc == CUSTOMER else r["bind_value"] if r["bind_scope"] == CUSTOMER else None)
        .info(f"{r['rule_code']} {old} -> {_bind_text(sc, bv)}"),
    )


def _next_version(rule_id: int) -> int:
    """规则内统一递增的下一个版本号（派生版也占号）。"""
    return int(db.scalar("SELECT COALESCE(MAX(version), 0) FROM sn_rule_version WHERE rule_id = %s", rule_id)) + 1


# ---------------------------------------------------------------- 生效规则


def _counter_row(pi: str) -> dict:
    c = db.one("SELECT rule_id, rule_version_id FROM sn_pi_counter WHERE pi_no = %s", pi)
    return c or {"rule_id": None, "rule_version_id": None}


def _derived_spec(base: dict, version: int) -> codec.Spec:
    """派生版的参数：后缀 = 原后缀 + _v版本号，其余同原版。"""
    s = spec_of(base)
    return replace(s, suffix=f"{s.suffix}_v{version}")


def plan_for(pi: str, r: dict, source: str, ctr: dict | None = None) -> Resolved:
    """本 PI 用规则 r 时用哪一版（见模块说明）。"""
    ctr = ctr or _counter_row(pi)
    base = current_version(r)
    pinned = None if ctr.get("rule_version_id") is None else _version(ctr["rule_version_id"])
    if pinned is not None and pinned["rule_id"] == r["id"] and base["id"] in (pinned["id"], pinned["derived_from"]):
        return Resolved(r, pinned, source, PLAN_USE)
    if base["claimed_pi"] is None:
        return Resolved(r, base, source, PLAN_CLAIM)
    if base["claimed_pi"] == pi:
        return Resolved(r, base, source, PLAN_USE)
    d = db.one(
        "SELECT * FROM sn_rule_version WHERE derived_from = %s AND claimed_pi = %s ORDER BY version DESC LIMIT 1",
        base["id"],
        pi,
    )
    if d is not None:
        return Resolved(r, d, source, PLAN_USE)
    n = _next_version(r["id"])
    spec = _derived_spec(base, n)
    if codec.validate(spec) is not None:
        raise biz(ErrorCode.RULE_SN_TOO_LONG)
    v = _draft_version(r["id"], n, spec, derived_from=base["id"])
    return Resolved(r, v, source, PLAN_DERIVE, shared_by=base["claimed_pi"])


def _version(version_id: int) -> dict | None:
    return db.one("SELECT * FROM sn_rule_version WHERE id = %s", version_id)


def _draft_version(rule_id: int | None, version: int, spec: codec.Spec, derived_from: int | None = None) -> dict:
    return {
        "id": None,
        "rule_id": rule_id,
        "version": version,
        "prefix": spec.prefix,
        "suffix": spec.suffix,
        "base": spec.base,
        "seq_len": spec.seq_len,
        "charset": spec.charset,
        "used": 0,
        "claimed_pi": None,
        "derived_from": derived_from,
    }


def bound(pi: str, customer: str | None, ctr: dict | None = None) -> Resolved | None:
    """绑定到该 PI 的规则：按 PI ＞ 给该 PI 指定的 ＞ 按客户。没有返回 None。"""
    ctr = ctr or _counter_row(pi)
    r = db.one("SELECT * FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", PI, pi)
    if r is not None:
        return plan_for(pi, r, SRC_PI, ctr)
    if ctr.get("rule_id") is not None:
        r = db.one("SELECT * FROM sn_rule WHERE id = %s", ctr["rule_id"])
        if r is not None:
            return plan_for(pi, r, SRC_CHOSEN, ctr)
    if not util.blank(customer):
        r = db.one("SELECT * FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", CUSTOMER, customer)
        if r is not None:
            return plan_for(pi, r, SRC_CUSTOMER, ctr)
    return None


def fixed(pi: str, customer: str | None, ctr: dict | None = None) -> Resolved | None:
    """已确定的规则（按 PI ＞ 指定 ＞ 按客户）。没有返回 None（生成时须由用户选模板或手动设置）。"""
    return bound(pi, customer, ctr)


def resolve(pi: str, customer: str | None) -> Resolved | None:
    """该 PI 生效的规则；尚未确定时为第一条可选模板（与选项列表的顺序一致）。没有返回 None。"""
    r = fixed(pi, customer)
    if r is not None:
        return r
    opts = _pickable()
    return None if not opts else plan_for(pi, opts[0], SRC_PICKED)


def _pickable() -> list[dict]:
    """可作模板的规则：通用规则在前，其后是各 PI 的规则；各按编码排序。按客户绑定的规则不能选。"""
    return db.all(
        "SELECT * FROM sn_rule WHERE bind_scope IN (%s, %s) ORDER BY bind_scope = %s DESC, rule_code ASC",
        GENERAL,
        PI,
        GENERAL,
    )


@dataclass(frozen=True)
class ManualIn:
    prefix: str | None
    seq_len: int | None
    suffix: str | None
    base: int | None = None


def manual_plan(pi: str, m: ManualIn) -> Resolved:
    """生成页手动设置：校验参数，给出生成成功后要新建的规则（按 PI 绑定，名称 PI号_v序号）。"""
    spec = normalize(SpecIn(m.prefix, m.suffix, MANUAL_BASE if m.base is None else m.base, m.seq_len, None))
    check_affixes(spec)
    _check_bind_free(PI, pi)
    code, name = _manual_names(pi)
    rule = {
        "id": None,
        "rule_code": code,
        "rule_name": name,
        "bind_scope": PI,
        "bind_value": pi,
        "category_scope": PI,
        "category_value": pi,
        "current_version": 1,
    }
    return Resolved(rule, _draft_version(None, 1, spec), SRC_MANUAL, PLAN_NEW)


_CODE_UNSAFE = re.compile(r"[^A-Za-z0-9_-]")


def _manual_names(pi: str) -> tuple[str, str]:
    """手动规则的编码与名称：PI-<PI号>、PI号_v1；同一 PI 再手动设置时序号递增（PI-<PI号>-2、PI号_v2）。"""
    stem = "PI-" + _CODE_UNSAFE.sub("_", pi)[:52]
    taken = set(db.column("SELECT rule_code FROM sn_rule WHERE rule_code = %s OR rule_code LIKE %s", stem, stem + "-%"))
    k = 1
    while (stem if k == 1 else f"{stem}-{k}") in taken:
        k += 1
    return (stem if k == 1 else f"{stem}-{k}"), util.cut(f"{pi}_v{k}", 128)


def for_generation(
    pi: str,
    customer: str | None,
    rule_id: int | None,
    manual: ManualIn | None = None,
    ctr: dict | None = None,
) -> Resolved:
    """本次生成用的规则：已确定的规则优先（忽略所选）；否则用所选模板或手动设置的规则。

    按客户绑定的规则可以被本 PI 单独覆盖：传了别的模板或手动参数时用它们（生成后记为本 PI 的规则）。
    """
    ctr = ctr or _counter_row(pi)
    r = fixed(pi, customer, ctr)
    overriding = manual is not None or (rule_id is not None and r is not None and rule_id != r.rule["id"])
    if r is not None and not (r.source == SRC_CUSTOMER and overriding):
        return r
    if manual is not None:
        return manual_plan(pi, manual)
    if rule_id is None:
        if db.count("SELECT COUNT(*) FROM sn_rule") == 0:
            raise biz(ErrorCode.RULE_MISSING, pi=pi, customer=customer or "")
        raise biz(ErrorCode.RULE_CHOICE_REQUIRED, pi=pi)
    return plan_for(pi, pickable_rule(rule_id), SRC_PICKED, ctr)


def pickable_rule(rule_id: int | None) -> dict:
    r = None if rule_id is None else db.one("SELECT * FROM sn_rule WHERE id = %s", rule_id)
    if r is None:
        raise biz(ErrorCode.RULE_NOT_FOUND)
    if r["bind_scope"] == CUSTOMER:
        raise biz(ErrorCode.RULE_NOT_GENERAL, rule_code=r["rule_code"])
    return r


def options(pi: str | None = None, start_seq: int = 1) -> list[dict]:
    """规则库中可选的规则（见 _pickable），第一条即默认；给了 PI 时按该 PI 算出各自要用的版本与第一个号。"""
    rows = _pickable()
    if not rows:
        return []
    ids = [r["id"] for r in rows]
    marks = ",".join(["%s"] * len(ids))
    vs = db.all(f"SELECT * FROM sn_rule_version WHERE rule_id IN ({marks})", *ids)
    by_rule: dict[int, dict[int, dict]] = {}
    for v in vs:
        by_rule.setdefault(v["rule_id"], {})[v["version"]] = v
    ctr = {"rule_id": None, "rule_version_id": None} if not pi else _counter_row(pi)
    pinned = (
        None if ctr["rule_version_id"] is None else next((v for v in vs if v["id"] == ctr["rule_version_id"]), None)
    )
    out = []
    for r in rows:
        mine = by_rule.get(r["id"], {})
        base = mine.get(r["current_version"])
        if base is None:
            continue
        info = rule_info(_plan_from(pi, r, base, mine.values(), pinned), start_seq)
        # 已被几张 PI 用过（各版本归属的 PI 去重），供生成页规则库列表展示
        info["used_pis"] = len({v["claimed_pi"] for v in mine.values() if v["claimed_pi"]})
        out.append(info)
    return out


def _plan_from(pi: str | None, r: dict, base: dict, versions, pinned: dict | None) -> Resolved:
    """plan_for 的批量版（版本已一次查出）。"""
    if pinned is not None and pinned["rule_id"] == r["id"] and base["id"] in (pinned["id"], pinned["derived_from"]):
        return Resolved(r, pinned, SRC_PICKED, PLAN_USE)
    if base["claimed_pi"] is None:
        return Resolved(r, base, SRC_PICKED, PLAN_CLAIM)
    if pi is None or base["claimed_pi"] == pi:
        return Resolved(r, base, SRC_PICKED, PLAN_USE)
    mine = [v for v in versions if v["derived_from"] == base["id"] and v["claimed_pi"] == pi]
    if mine:
        return Resolved(r, max(mine, key=lambda v: v["version"]), SRC_PICKED, PLAN_USE)
    n = max(v["version"] for v in versions) + 1
    v = _draft_version(r["id"], n, _derived_spec(base, n), derived_from=base["id"])
    return Resolved(r, v, SRC_PICKED, PLAN_DERIVE, shared_by=base["claimed_pi"])


def realize(cu: CurrentUser, pi: str, r: Resolved) -> dict:
    """生成事务内：把本次要用的版本落库并标记已使用、归属本 PI，返回该版本。

    与预演时的判断不一致（期间别的 PI 抢先用了这一版、别人也派生了同号的版本、前缀被占用）时报预演已变化。
    """
    if r.plan == PLAN_NEW:
        spec = spec_of(r.version)
        check_affixes(spec)
        _check_bind_free(PI, pi)
        code, name = _manual_names(pi)
        if (code, name) != (r.rule["rule_code"], r.rule["rule_name"]):
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        rid = _insert_rule(cu, code, name, PI, pi, spec, claimed_pi=pi, note=" (生成页手动设置)")
        return db.one("SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = 1", rid)
    rule = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", r.rule["id"])
    if rule is None:
        raise biz(ErrorCode.RULE_NOT_FOUND)
    if r.plan == PLAN_DERIVE:
        base = current_version(rule)
        n = _next_version(rule["id"])
        if base["id"] != r.version["derived_from"] or base["claimed_pi"] in (None, pi) or n != r.version["version"]:
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        vid = _insert_version(
            cu, rule["id"], n, spec_of(r.version), util.now(), used=True, claimed_pi=pi, derived_from=base["id"]
        )
        audit.record(
            cu,
            audit.entry(audit.RULE_VERSION)
            .pi(pi)
            .info(f"{rule['rule_code']} v{n} {describe(spec_of(r.version))} (沿用 v{base['version']}，派生给 {pi})"),
        )
        return _version(vid)
    if r.plan == PLAN_CLAIM:
        # 预演时这一版还没有 PI 用过：期间被别的 PI 抢先用了就要重新预演（会改成派生版）
        n = db.exec(
            "UPDATE sn_rule_version SET used = 1, claimed_pi = %s "
            "WHERE id = %s AND (claimed_pi IS NULL OR claimed_pi = %s)",
            pi,
            r.version["id"],
            pi,
        )
        if n == 0:
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    else:
        db.exec("UPDATE sn_rule_version SET used = 1 WHERE id = %s", r.version["id"])
    return _version(r.version["id"])


def bind_pi(cu: CurrentUser, pi_in: str | None, rule_id: int | None) -> dict:
    """给 PI 指定规则库中的规则（通用规则或别的 PI 的规则）：之后生成默认用它；已指定过的直接换成这条。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        r = pickable_rule(rule_id)
        own = db.one("SELECT rule_code FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", PI, pi)
        if own is not None:
            raise biz(ErrorCode.RULE_BIND_EXISTS, rule_code=own["rule_code"])
        now = util.now()
        db.exec(
            "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, rule_id, updated_at) "
            "VALUES (%s,0,0,%s,%s) ON DUPLICATE KEY UPDATE rule_id = %s, updated_at = %s",
            pi,
            r["id"],
            now,
            r["id"],
            now,
        )
        audit.record(cu, audit.entry(audit.RULE_PI_BIND).pi(pi).info(f"{r['rule_code']}"))
    return _effective_info(pi)


def unbind_pi(cu: CurrentUser, pi_in: str | None) -> dict:
    """解除该 PI 当前生效的绑定：按 PI 绑定的规则转为通用规则（可作模板）；给该 PI 指定的规则取消指定；
    按客户绑定的规则转为通用规则（该客户的全部 PI 都解除）。归类不变。下次生成前重新选择。
    """
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        r = fixed(pi, orders.customer_of_pi(pi))
        if r is None:
            pass
        elif r.source == SRC_PI:
            rule = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", r.rule["id"])
            db.exec(
                "UPDATE sn_rule SET bind_scope = %s, bind_value = '', updated_by = %s, updated_at = %s WHERE id = %s",
                GENERAL,
                cu.emp_no,
                util.now(),
                rule["id"],
            )
            _audit_rebind(cu, rule, GENERAL, "")
        elif r.source == SRC_CHOSEN:
            db.exec("UPDATE sn_pi_counter SET rule_id = NULL, updated_at = %s WHERE pi_no = %s", util.now(), pi)
            audit.record(cu, audit.entry(audit.RULE_PI_UNBIND).pi(pi).info(f"{r.rule['rule_code']}"))
        else:
            # 按客户绑定：规则库不再管绑定，在这里解除，该客户全部 PI 都不再默认用它；规则转为通用、归类不变
            rule = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", r.rule["id"])
            db.exec(
                "UPDATE sn_rule SET bind_scope = %s, bind_value = '', updated_by = %s, updated_at = %s WHERE id = %s",
                GENERAL,
                cu.emp_no,
                util.now(),
                rule["id"],
            )
            _audit_rebind(cu, rule, GENERAL, "")
    return _effective_info(pi)


def _effective_info(pi: str) -> dict:
    r = fixed(pi, orders.customer_of_pi(pi))
    return {"pi_no": pi, "rule": None if r is None else rule_info(r)}


# 已被号引用的版本参数不可变，可放心缓存（用于把十进制流水显示成进制流水）
_spec_cache: dict[int, codec.Spec | None] = {}
_cache_lock = threading.Lock()


def cached_spec(version_id: int | None) -> codec.Spec | None:
    if version_id is None:
        return None
    with _cache_lock:
        if version_id in _spec_cache:
            return _spec_cache[version_id]
    v = db.one("SELECT prefix, suffix, base, seq_len, charset FROM sn_rule_version WHERE id = %s", version_id)
    s = None if v is None else spec_of(v)
    if s is not None:
        with _cache_lock:
            _spec_cache[version_id] = s
    return s


def seq_text(version_id: int | None, seq_dec: int | None) -> str:
    """进制流水文本；无规则或无十进制流水时为空串。"""
    s = cached_spec(version_id)
    if s is None or seq_dec is None:
        return ""
    try:
        return codec.encode_seq(seq_dec, s)
    except (codec.SeqOverflow, ValueError):
        return ""


def rule_info(r: Resolved, start_seq: int = 1) -> dict:
    s = spec_of(r.version)
    mx = codec.max_seq(s)
    return {
        "rule_id": r.rule["id"],
        "source": r.source,
        "plan": r.plan,
        "shared_by": r.shared_by,
        "rule_code": r.rule["rule_code"],
        "rule_name": r.rule["rule_name"],
        "bind_scope": r.rule["bind_scope"],
        "bind_value": r.rule["bind_value"],
        "category_scope": r.rule.get("category_scope", NONE),
        "category_value": r.rule.get("category_value", ""),
        "version_id": r.version["id"],
        "version": r.version["version"],
        "derived": r.version.get("derived_from") is not None,
        "prefix": s.prefix,
        "suffix": s.suffix,
        "base": s.base,
        "seq_len": s.seq_len,
        "charset": s.charset,
        "max_seq": mx,
        "sample_sn": codec.format_sn(1, s),
        "start_sn": codec.format_sn(start_seq, s) if 0 < start_seq <= mx else None,
    }
