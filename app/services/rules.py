"""SN 规则库与规则选取（F-08）。

全局不撞号（5.2.4）：
- 任何两条规则（含各版本）的前缀不能相同；区分大小写。只是开头相同（如 SD 与 SDD）不算同号，
  万一拼出的完整 SN 相同，由号池唯一约束拦下、整次生成回滚。
- 一个前缀只用于一张 PI：规则版本首次用于生成（或为某张 PI 换前缀另出）时归属该 PI；别的 PI 要用同一条规则，
  须为它填新前缀，按原格式另出属于它的版本（derived_from 记由哪一版而来）。同一 PI 的多个物料行可共用一个版本。
- 一个前缀只对应一种格式：生成过号的版本改格式须另出一版并换新前缀；只改名称或分类不另出版本。

规则选取按物料行：物料行已选规则 ＞ PI 绑定规则 ＞ 客户绑定规则（作模板，每张 PI 首次使用须换前缀）。
选法：规则库（选一条规则，本 PI 用哪一版见 resolve）或自定义（前缀、进制、位数、是否带长度、后缀）；
自定义规则生成成功后存入规则库并归属本 PI（编码 PI-PI号-n，名称 PI号_vn，n 为本 PI 第几条自定义规则），
生成失败或取消不留规则。
物料行生成过号后可改选，已生成的号不变，流水接续。
"""

from __future__ import annotations

import json
import re
import threading
from dataclasses import dataclass, replace

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit

#: 分类
NONE = "NONE"
CUSTOMER = "CUSTOMER"
PI = "PI"
CATEGORIES = frozenset((NONE, CUSTOMER, PI))
_CODE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
#: 自定义规则的流水位数默认（规则库的默认值由页面给出）
CUSTOM_SEQ_LEN = 6

#: 规则来源（生效顺序）
SRC_LINE = "LINE"
SRC_PI = "PI"
SRC_CUSTOMER = "CUSTOMER"
#: 选法
MODE_LIBRARY = "LIBRARY"
MODE_CUSTOM = "CUSTOM"
#: 本 PI 用哪一版：USE 已归属本 PI / CLAIM 尚无 PI（首次生成时归属本 PI）/ NEW 自定义（生成时新建）/
#: PREFIX 须为本 PI 填新前缀
PLAN_USE = "USE"
PLAN_CLAIM = "CLAIM"
PLAN_NEW = "NEW"
PLAN_PREFIX = "PREFIX"

RULE_FIELDS = (
    "id",
    "rule_code",
    "rule_name",
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
    "with_length",
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
    if v is None:
        return None
    out = util.jrow(v, VERSION_FIELDS, ("used", "with_length"))
    out["format"] = format_text(spec_of(v))
    return out


def spec_of(v: dict) -> codec.Spec:
    return codec.Spec(v["prefix"], v["suffix"], v["base"], v["seq_len"], v["charset"], bool(v["with_length"]))


def format_text(s: codec.Spec) -> str:
    """格式说明：HX + 长度 + 6 位 10 进制流水 + A。"""
    parts = [s.prefix]
    if s.with_length:
        parts.append("长度")
    parts.append(f"{s.seq_len} 位 {s.base} 进制流水")
    if s.suffix:
        parts.append(s.suffix)
    return " + ".join(parts)


@dataclass(frozen=True)
class SpecIn:
    prefix: str | None
    suffix: str | None
    base: int | None
    seq_len: int | None
    charset: str | None
    with_length: bool | None = True


def normalize(s: SpecIn) -> codec.Spec:
    """规范化并校验编码参数；字符表留空用该进制默认字符表。"""
    if s.base is None or s.base not in codec.DEFAULT_CHARSETS:
        raise biz(ErrorCode.RULE_BASE_INVALID)
    if s.seq_len is None:
        raise biz(ErrorCode.RULE_SEQ_LEN_INVALID)
    cs = util.trim(s.charset) or codec.DEFAULT_CHARSETS[s.base]
    spec = codec.Spec(
        util.trim(s.prefix),
        util.trim(s.suffix),
        s.base,
        s.seq_len,
        cs,
        True if s.with_length is None else s.with_length,
    )
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    return spec


def check_prefix(prefix: str, exclude_version: int | None = None) -> None:
    """前缀不能与任何规则的任何版本（含本规则旧版本）相同；区分大小写。只是开头相同（如 SD 与 SDD）不算同号。"""
    row = db.one(
        "SELECT v.id, v.version, v.prefix, r.rule_name FROM sn_rule_version v JOIN sn_rule r ON r.id = v.rule_id "
        "WHERE v.prefix = %s AND (%s IS NULL OR v.id <> %s) ORDER BY v.id LIMIT 1",
        prefix,
        exclude_version,
        exclude_version,
    )
    if row is not None:
        raise biz(
            ErrorCode.RULE_PREFIX_CONFLICT,
            prefix=prefix,
            rule_name=row["rule_name"],
            version=row["version"],
        )


def preview(s: SpecIn, length: str | None = None) -> dict:
    """编辑时的示例号：前 5 个和最大号（长度用示例值）、最大流水。"""
    spec = normalize(s)
    sample_len = codec.clean_length(length) or "100"
    mx = codec.max_seq(spec)
    samples = []
    i = 1
    while i <= 5 and i <= mx:
        samples.append(
            {"seq_dec": i, "seq_text": codec.encode_seq(i, spec), "sn": codec.format_sn(i, spec, sample_len)}
        )
        i += 1
    if mx > 5:
        samples.append(
            {"seq_dec": mx, "seq_text": codec.encode_seq(mx, spec), "sn": codec.format_sn(mx, spec, sample_len)}
        )
    return {"max_seq": mx, "format": format_text(spec), "sample_length": sample_len, "samples": samples}


def describe(s: codec.Spec) -> str:
    return (
        f"prefix={s.prefix} suffix={s.suffix} base={s.base} len={s.seq_len} with_length={int(s.with_length)} "
        f"charset={s.charset}"
    )


# ================================================================== 规则库


def _versions_of(rule_id: int) -> list[dict]:
    return db.all("SELECT * FROM sn_rule_version WHERE rule_id = %s ORDER BY version DESC", rule_id)


def _versions_by_rule(rule_ids: list[int] | None = None) -> dict[int, list[dict]]:
    """各规则的全部版本（新版本在前）；rule_ids = None 为全部规则。"""
    sql, args = "SELECT * FROM sn_rule_version", []
    if rule_ids is not None:
        if not rule_ids:
            return {}
        sql += f" WHERE rule_id IN ({marks(len(rule_ids))})"
        args = rule_ids
    out: dict[int, list[dict]] = {}
    for v in db.all(sql + " ORDER BY rule_id, version DESC", *args):
        out.setdefault(v["rule_id"], []).append(v)
    return out


def _current_of(r: dict, vs: list[dict]) -> dict | None:
    return next((v for v in vs if v["version"] == r["current_version"]), None)


def current_version(r: dict) -> dict | None:
    return db.one("SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = %s", r["id"], r["current_version"])


def _column_by_rule(sql: str, ids: list[int]) -> dict[int, list[str]]:
    out: dict[int, list[str]] = {}
    if ids:
        for row in db.all(sql.format(marks(len(ids))), *ids):
            out.setdefault(row["rule_id"], []).append(row["v"])
    return out


def _views(rs: list[dict]) -> list[dict]:
    """规则库列表与详情：每条规则的版本、用过的 PI、PI 与客户绑定（一次取齐，不逐条查）。"""
    ids = [r["id"] for r in rs]
    versions = _versions_by_rule(ids)
    pi_bind = _column_by_rule("SELECT rule_id, pi_no v FROM sn_pi_rule WHERE rule_id IN ({}) ORDER BY pi_no", ids)
    cust_bind = _column_by_rule(
        "SELECT rule_id, customer_code v FROM sn_customer_rule WHERE rule_id IN ({}) ORDER BY customer_code", ids
    )
    out = []
    for r in rs:
        vs = versions.get(r["id"], [])
        out.append(
            {
                "rule": rule_json(r),
                "current": version_json(_current_of(r, vs)),
                "versions": [version_json(v) for v in vs],
                "used": any(v["used"] for v in vs),
                "pis": sorted({v["claimed_pi"] for v in vs if v["claimed_pi"]}),
                "bindings": {"pi": pi_bind.get(r["id"], []), "customer": cust_bind.get(r["id"], [])},
            }
        )
    return out


def view(r: dict) -> dict:
    return _views([r])[0]


def list_rules(q: str | None, category: str | None = None, category_value: str | None = None) -> list[dict]:
    sql = "SELECT * FROM sn_rule WHERE 1=1"
    args: list = []
    if not util.blank(category):
        sql += " AND category_scope = %s"
        args.append(util.trim(category))
    if not util.blank(category_value):
        sql += " AND category_value = %s"
        args.append(util.trim(category_value))
    text = util.trim(q)
    if text:
        like = util.like_any(text)
        sql += (
            " AND (rule_code LIKE %s OR rule_name LIKE %s OR category_value LIKE %s OR id IN "
            "(SELECT rule_id FROM sn_rule_version WHERE claimed_pi LIKE %s) OR id IN "
            "(SELECT rule_id FROM sn_customer_rule WHERE customer_code LIKE %s))"
        )
        args += [like] * 5
    sql += " ORDER BY category_scope = %s DESC, category_scope ASC, category_value ASC, rule_code ASC"
    args.append(NONE)
    return _views(db.all(sql, *args))


def get_rule(rule_id: int | None, lock: bool = False) -> dict:
    r = (
        None
        if rule_id is None
        else db.one("SELECT * FROM sn_rule WHERE id = %s" + (" FOR UPDATE" if lock else ""), rule_id)
    )
    if r is None:
        raise biz(ErrorCode.RULE_NOT_FOUND)
    return r


def _check_category(category: str | None, value: str | None) -> tuple[str, str]:
    cs = util.trim(category) or NONE
    if cs not in CATEGORIES:
        raise biz(ErrorCode.RULE_CATEGORY_INVALID)
    cv = "" if cs == NONE else util.trim(value)
    if cs != NONE and (not cv or len(cv) > 64):
        raise biz(ErrorCode.RULE_CATEGORY_REQUIRED)
    return cs, cv


def _insert_version(
    cu: CurrentUser,
    rule_id: int,
    version: int,
    spec: codec.Spec,
    now,
    *,
    used=False,
    claimed_pi=None,
    derived_from=None,
) -> int:
    return db.insert(
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, with_length, used, "
        "claimed_pi, derived_from, created_by, created_at, updated_at) VALUES "
        "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        rule_id,
        version,
        spec.prefix,
        spec.suffix,
        spec.base,
        spec.seq_len,
        spec.charset,
        1 if spec.with_length else 0,
        1 if used else 0,
        claimed_pi,
        derived_from,
        cu.emp_no,
        now,
        now,
    )


def _insert_rule(
    cu: CurrentUser,
    code: str,
    name: str,
    spec: codec.Spec,
    cat: tuple[str, str],
    note: str = "",
    claimed_pi: str | None = None,
) -> int:
    now = util.now()
    rid = db.insert(
        "INSERT INTO sn_rule(rule_code, rule_name, category_scope, category_value, current_version, created_by, "
        "created_at, updated_by, updated_at) VALUES (%s,%s,%s,%s,1,%s,%s,%s,%s)",
        code,
        name,
        cat[0],
        cat[1],
        cu.emp_no,
        now,
        cu.emp_no,
        now,
    )
    _insert_version(cu, rid, 1, spec, now, used=claimed_pi is not None, claimed_pi=claimed_pi)
    audit.record(
        cu,
        audit.entry(audit.RULE_CREATE)
        .pi(cat[1] if cat[0] == PI else claimed_pi)
        .customer(cat[1] if cat[0] == CUSTOMER else None)
        .info(f"{code} v1 {describe(spec)}{note}"),
    )
    return rid


def create(cu: CurrentUser, code: str | None, name: str | None, s: SpecIn, category=None, category_value=None) -> dict:
    with db.tx():
        c = util.trim(code)
        if not _CODE.match(c):
            raise biz(ErrorCode.RULE_CODE_INVALID)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        cat = _check_category(category, category_value)
        spec = normalize(s)
        check_prefix(spec.prefix)
        if db.exists("SELECT EXISTS(SELECT 1 FROM sn_rule WHERE rule_code = %s)", c):
            raise biz(ErrorCode.RULE_CODE_EXISTS, rule_code=c)
        rid = _insert_rule(cu, c, n, spec, cat)
        return view(get_rule(rid))


def _next_version(rule_id: int) -> int:
    return int(db.scalar("SELECT COALESCE(MAX(version), 0) FROM sn_rule_version WHERE rule_id = %s", rule_id)) + 1


def update(cu: CurrentUser, rule_id: int, name: str | None, s: SpecIn, category=None, category_value=None) -> dict:
    """结果：UPDATED 就地修改（当前版本从没生成过号）/ NEW_VERSION 另出一版（须换新前缀）/ RENAMED / RECATEGORIZED /
    UNCHANGED。"""
    with db.tx():
        r = get_rule(rule_id, lock=True)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        spec = normalize(s)
        cur = db.one(
            "SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = %s FOR UPDATE",
            r["id"],
            r["current_version"],
        )
        cs, cv = (
            (r["category_scope"], r["category_value"])
            if category is None
            else _check_category(category, category_value)
        )
        before = spec_of(cur)
        action = "UNCHANGED"
        version = r["current_version"]
        now = util.now()
        if spec != before:
            if cur["used"]:
                if spec.prefix == before.prefix:
                    raise biz(ErrorCode.RULE_USED_FORMAT, version=cur["version"])
                check_prefix(spec.prefix)
                version = _next_version(r["id"])
                _insert_version(cu, r["id"], version, spec, now)
                action = "NEW_VERSION"
                audit.record(
                    cu,
                    audit.entry(audit.RULE_VERSION).info(
                        f"{r['rule_code']} v{version} {describe(spec)} (was {describe(before)})"
                    ),
                )
            else:
                if spec.prefix != before.prefix:
                    check_prefix(spec.prefix, cur["id"])
                db.exec(
                    "UPDATE sn_rule_version SET prefix = %s, suffix = %s, base = %s, seq_len = %s, charset = %s, "
                    "with_length = %s, updated_at = %s WHERE id = %s",
                    spec.prefix,
                    spec.suffix,
                    spec.base,
                    spec.seq_len,
                    spec.charset,
                    1 if spec.with_length else 0,
                    now,
                    cur["id"],
                )
                _forget(cur["id"])
                action = "UPDATED"
                audit.record(
                    cu,
                    audit.entry(audit.RULE_UPDATE).info(
                        f"{r['rule_code']} v{cur['version']} {describe(spec)} (was {describe(before)})"
                    ),
                )
        if (cs, cv) != (r["category_scope"], r["category_value"]):
            audit.record(
                cu,
                audit.entry(audit.RULE_CATEGORY)
                .pi(cv if cs == PI else None)
                .customer(cv if cs == CUSTOMER else None)
                .info(f"{r['rule_code']} {r['category_scope']}:{r['category_value']} -> {cs}:{cv}"),
            )
            action = "RECATEGORIZED" if action == "UNCHANGED" else action
        if n != r["rule_name"]:
            audit.record(cu, audit.entry(audit.RULE_RENAME).info(f"{r['rule_code']} {r['rule_name']} -> {n}"))
            action = "RENAMED" if action == "UNCHANGED" else action
        db.exec(
            "UPDATE sn_rule SET rule_name = %s, category_scope = %s, category_value = %s, current_version = %s, "
            "updated_by = %s, updated_at = %s WHERE id = %s",
            n,
            cs,
            cv,
            version,
            cu.emp_no,
            now,
            r["id"],
        )
        return {"action": action, "rule": view(get_rule(r["id"]))}


def delete(cu: CurrentUser, rule_id: int) -> dict:
    """只能删从没生成过号、也没指定给任何 PI（PI 绑定、物料行选定、版本归属）的规则（连同全部版本）。"""
    with db.tx():
        r = get_rule(rule_id, lock=True)
        vs = db.all("SELECT * FROM sn_rule_version WHERE rule_id = %s FOR UPDATE", r["id"])
        if any(v["used"] for v in vs):
            raise biz(ErrorCode.RULE_IN_USE, rule_code=r["rule_code"])
        pi = (
            db.scalar("SELECT pi_no FROM sn_pi_rule WHERE rule_id = %s LIMIT 1", r["id"])
            or db.scalar("SELECT pi_no FROM sn_line_rule WHERE rule_id = %s LIMIT 1", r["id"])
            or next((v["claimed_pi"] for v in vs if v["claimed_pi"]), None)
        )
        if pi is not None:
            raise biz(ErrorCode.RULE_ASSIGNED, rule_code=r["rule_code"], pi=pi)
        db.exec("DELETE FROM sn_customer_rule WHERE rule_id = %s", r["id"])
        db.exec("DELETE FROM sn_rule_version WHERE rule_id = %s", r["id"])
        db.exec("DELETE FROM sn_rule WHERE id = %s", r["id"])
        for v in vs:
            _forget(v["id"])
        cur = next((v for v in vs if v["version"] == r["current_version"]), None)
        audit.record(
            cu,
            audit.entry(audit.RULE_DELETE).info(
                f"{r['rule_code']} {r['rule_name']} "
                f"v{r['current_version']}{' ' + describe(spec_of(cur)) if cur else ''}"
            ),
        )
        return {"id": r["id"], "rule_code": r["rule_code"]}


# ================================================================== 本 PI 用哪一版


@dataclass(frozen=True)
class Resolved:
    """某物料行（或 PI 默认）将用的规则。version 为 None 表示须为本 PI 换前缀（plan = PREFIX）。"""

    source: str
    mode: str
    plan: str
    rule: dict | None
    version: dict | None
    #: 自定义规则的参数（plan = NEW）
    custom: dict | None = None
    #: plan = PREFIX：模板版本已归属的 PI（客户模板时为空）
    other_pi: str | None = None

    @property
    def ready(self) -> bool:
        return self.version is not None

    def spec(self) -> codec.Spec:
        assert self.version is not None
        return spec_of(self.version)

    def key(self) -> str:
        """同一次生成里规则相同的物料行共用同一版：自定义规则按参数、规则库按版本。"""
        if self.plan == PLAN_NEW:
            return "C:" + json.dumps(self.custom, sort_keys=True)
        return f"V:{self.version['id'] if self.version else None}"


def resolve(rule: dict, pi: str, source: str) -> Resolved:
    """规则 rule 在 PI 下用哪一版：当前版本尚无 PI 或已归属本 PI → 用它；已归属别的 PI → 用为本 PI 换前缀另出的版本，
    没有则须换前缀。客户模板：每张 PI 首次使用须换前缀（已为本 PI 另出过版本时直接用）。"""
    return _resolve_in(rule, _versions_of(rule["id"]), pi, source)


def _resolve_in(rule: dict, vs: list[dict], pi: str, source: str) -> Resolved:
    """同 resolve，版本已取出（vs 为该规则全部版本，新版本在前）。"""
    t = _current_of(rule, vs)
    if t is None:
        raise biz(ErrorCode.RULE_VERSION_NOT_FOUND)
    own = next((v for v in vs if v["derived_from"] == t["id"] and v["claimed_pi"] == pi), None)
    if source != SRC_CUSTOMER and t["claimed_pi"] == pi:
        return Resolved(source, MODE_LIBRARY, PLAN_USE, rule, t)
    if own is not None:
        return Resolved(source, MODE_LIBRARY, PLAN_USE, rule, own)
    if source != SRC_CUSTOMER and t["claimed_pi"] is None:
        return Resolved(source, MODE_LIBRARY, PLAN_CLAIM, rule, t)
    return Resolved(source, MODE_LIBRARY, PLAN_PREFIX, rule, None, other_pi=t["claimed_pi"])


def _custom_spec(c: dict) -> codec.Spec:
    return normalize(
        SpecIn(
            c.get("prefix"),
            c.get("suffix"),
            c.get("base") or 10,
            c.get("seq_len") or CUSTOM_SEQ_LEN,
            None,
            c.get("with_length", True),
        )
    )


def _custom_resolved(source: str, c: dict) -> Resolved:
    spec = _custom_spec(c)
    draft = {
        "id": None,
        "rule_id": None,
        "version": 1,
        "prefix": spec.prefix,
        "suffix": spec.suffix,
        "base": spec.base,
        "seq_len": spec.seq_len,
        "charset": spec.charset,
        "with_length": 1 if spec.with_length else 0,
        "used": 0,
        "claimed_pi": None,
        "derived_from": None,
    }
    return Resolved(source, MODE_CUSTOM, PLAN_NEW, None, draft, custom=_custom_dict(spec))


def _custom_dict(spec: codec.Spec) -> dict:
    return {
        "prefix": spec.prefix,
        "base": spec.base,
        "seq_len": spec.seq_len,
        "with_length": spec.with_length,
        "suffix": spec.suffix,
    }


def pi_default(pi: str, customer: str | None) -> Resolved | None:
    """PI 绑定 ＞ 客户绑定（模板）。"""
    b = db.one("SELECT rule_id FROM sn_pi_rule WHERE pi_no = %s", pi)
    if b is not None:
        r = db.one("SELECT * FROM sn_rule WHERE id = %s", b["rule_id"])
        if r is not None:
            return resolve(r, pi, SRC_PI)
    if not util.blank(customer):
        c = db.one("SELECT rule_id FROM sn_customer_rule WHERE customer_code = %s", customer)
        if c is not None:
            r = db.one("SELECT * FROM sn_rule WHERE id = %s", c["rule_id"])
            if r is not None:
                return resolve(r, pi, SRC_CUSTOMER)
    return None


def effective(
    pi: str,
    customer: str | None,
    sel: dict | None,
    default: Resolved | None = None,
    use_default: bool = True,
    memo: dict[int, Resolved | None] | None = None,
) -> Resolved | None:
    """物料行生效的规则：物料行已选 ＞ PI 绑定 ＞ 客户绑定；都没有返回 None（须先选规则）。
    memo：同一 PI 的多个物料行选了同一条规则时只解析一次（规则 id → 解析结果）。"""
    if sel is not None:
        if sel.get("custom_json"):
            return _custom_resolved(SRC_LINE, json.loads(sel["custom_json"]))
        rid = sel.get("rule_id")
        if rid is not None:
            if memo is not None and rid in memo:
                res = memo[rid]
            else:
                r = db.one("SELECT * FROM sn_rule WHERE id = %s", rid)
                res = None if r is None else resolve(r, pi, SRC_LINE)
                if memo is not None:
                    memo[rid] = res
            if res is not None:
                return res
    if not use_default:
        return None
    return default if default is not None else pi_default(pi, customer)


def selections(keys: list[tuple[str, int]]) -> dict[tuple[str, int], dict]:
    if not keys:
        return {}
    bills = sorted({k[0] for k in keys})
    return {
        (r["bill_no"], r["seq"]): r
        for r in db.all(f"SELECT * FROM sn_line_rule WHERE bill_no IN ({marks(len(bills))})", *bills)
    }


def info(res: Resolved | None, length: str | None = None, start_seq: int = 1) -> dict | None:
    """规则说明：来源、选法、规则、本 PI 用的版本、格式、第一个号（按本行长度）。"""
    if res is None:
        return None
    out = {
        "source": res.source,
        "mode": res.mode,
        "plan": res.plan,
        "rule_id": None if res.rule is None else res.rule["id"],
        "rule_code": None if res.rule is None else res.rule["rule_code"],
        "rule_name": None if res.rule is None else res.rule["rule_name"],
        "version_id": None if res.version is None else res.version["id"],
        "version": None if res.version is None else res.version["version"],
        "other_pi": res.other_pi,
        "custom": res.custom,
        "format": None,
        "first_sn": None,
        "max_seq": None,
    }
    if res.version is not None:
        s = res.spec()
        out["format"] = format_text(s)
        out["prefix"] = s.prefix
        out["base"] = s.base
        out["seq_len"] = s.seq_len
        out["with_length"] = s.with_length
        out["suffix"] = s.suffix
        out["max_seq"] = codec.max_seq(s)
        if (length is not None or not s.with_length) and 0 < start_seq <= out["max_seq"]:
            out["first_sn"] = codec.format_sn(start_seq, s, length)
    elif res.rule is not None:
        t = current_version(res.rule)
        if t is not None:
            out["format"] = format_text(spec_of(t))
            out["template_prefix"] = t["prefix"]
    return out


def line_rules_brief(pi: str, keys: list[tuple[str, int]]) -> dict[tuple[str, int], dict]:
    """订单页右表的「SN 规则」列。"""
    if not keys or not pi:
        return {}
    sels = selections(keys)
    customer = db.scalar("SELECT customer_code FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", keys[0][0])
    default = pi_default(pi, customer)
    memo: dict[int, Resolved | None] = {}
    return {k: info(effective(pi, customer, sels.get(k), default, memo=memo)) for k in keys}


# ================================================================== 选取 / 绑定


def _derive(cu: CurrentUser, rule: dict, pi: str, new_prefix: str | None) -> dict:
    """为本 PI 换前缀：按当前版本的格式另出属于本 PI 的版本。"""
    t = current_version(rule)
    p = util.trim(new_prefix)
    spec = replace(spec_of(t), prefix=p)
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    check_prefix(p)
    n = _next_version(rule["id"])
    vid = _insert_version(cu, rule["id"], n, spec, util.now(), claimed_pi=pi, derived_from=t["id"])
    audit.record(
        cu,
        audit.entry(audit.RULE_PREFIX_VERSION)
        .pi(pi)
        .info(f"{rule['rule_code']} v{n} {describe(spec)} (由 v{t['version']} 换前缀，归属 {pi})"),
    )
    return db.one("SELECT * FROM sn_rule_version WHERE id = %s", vid)


def _resolve_for_select(cu: CurrentUser, rule: dict, pi: str, source: str, new_prefix: str | None) -> Resolved:
    res = resolve(rule, pi, source)
    if res.plan == PLAN_PREFIX:
        if util.blank(new_prefix):
            t = current_version(rule)
            raise biz(
                ErrorCode.RULE_PREFIX_CHANGE_REQUIRED,
                rule_code=rule["rule_code"],
                version=t["version"],
                other_pi=res.other_pi or "（客户模板）",
            )
        _derive(cu, rule, pi, new_prefix)
        res = resolve(rule, pi, source)
    return res


def _line_keys(pi: str, lines: list[dict] | None) -> list[tuple[str, int, dict]]:
    keys = []
    for x in lines or []:
        no = util.trim(x.get("bill_no"))
        seq = x.get("seq")
        line = db.one("SELECT * FROM sn_prd_mo WHERE bill_no = %s AND seq = %s", no, seq)
        if line is None:
            raise biz(ErrorCode.ORDER_LINE_NOT_FOUND, bill_no=no, seq=seq)
        if line["pi"] != pi:
            raise biz(ErrorCode.GEN_SCOPE_INVALID)
        keys.append((no, int(seq), line))
    if not keys:
        raise biz(ErrorCode.RULE_LINE_REQUIRED)
    return keys


def select_for_lines(
    cu: CurrentUser,
    pi_in: str | None,
    lines: list[dict] | None,
    rule_id: int | None,
    custom: dict | None,
    new_prefix: str | None = None,
) -> dict:
    """物料行选定 / 改选规则：规则库（rule_id；版本已归属别的 PI 时须填 new_prefix）或自定义（custom）。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        keys = _line_keys(pi, lines)
        if custom is not None:
            res = _custom_resolved(SRC_LINE, custom)
            check_prefix(res.version["prefix"])
            rid, cj = None, json.dumps(res.custom, ensure_ascii=False, sort_keys=True)
            label = f"custom {format_text(res.spec())}"
        else:
            rule = get_rule(rule_id)
            res = _resolve_for_select(cu, rule, pi, SRC_LINE, new_prefix)
            rid, cj = rule["id"], None
            label = f"{rule['rule_code']} v{res.version['version']}"
        now = util.now()
        prev = selections([(k[0], k[1]) for k in keys])
        for no, seq, line in keys:
            db.exec(
                "INSERT INTO sn_line_rule(bill_no, seq, pi_no, rule_id, custom_json, set_by, set_at) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE pi_no = VALUES(pi_no), rule_id = "
                "VALUES(rule_id), "
                "custom_json = VALUES(custom_json), set_by = VALUES(set_by), set_at = VALUES(set_at)",
                no,
                seq,
                pi,
                rid,
                cj,
                cu.emp_no,
                now,
            )
            generated = db.count(
                "SELECT COALESCE(SUM(own_generated), 0) FROM sn_line_count WHERE bill_no = %s AND seq = %s", no, seq
            )
            audit.record(
                cu,
                audit.entry(audit.RULE_LINE_SELECT)
                .pi(pi)
                .bill(no)
                .material(line["material_code"])
                .info(f"seq={seq} {label}" + (" (改选，已生成过号)" if (no, seq) in prev and generated else "")),
            )
        return {"pi_no": pi, "rule": info(res)}


def clear_lines(cu: CurrentUser, pi_in: str | None, lines: list[dict] | None) -> dict:
    """物料行改回按 PI / 客户默认。"""
    pi = util.trim(pi_in)
    with db.tx():
        keys = _line_keys(pi, lines)
        for no, seq, line in keys:
            if db.exec("DELETE FROM sn_line_rule WHERE bill_no = %s AND seq = %s", no, seq):
                audit.record(
                    cu,
                    audit.entry(audit.RULE_LINE_SELECT)
                    .pi(pi)
                    .bill(no)
                    .material(line["material_code"])
                    .info(f"seq={seq} cleared"),
                )
    return {"pi_no": pi}


def bind_pi(cu: CurrentUser, pi_in: str | None, rule_id: int | None, new_prefix: str | None = None) -> dict:
    """给 PI 绑定规则作各物料行的默认（单行另选优先）；版本已归属别的 PI 时须填新前缀。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        rule = get_rule(rule_id)
        res = _resolve_for_select(cu, rule, pi, SRC_PI, new_prefix)
        now = util.now()
        db.exec(
            "INSERT INTO sn_pi_rule(pi_no, rule_id, bound_by, bound_at) VALUES (%s,%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE rule_id = VALUES(rule_id), bound_by = VALUES(bound_by), bound_at = "
            "VALUES(bound_at)",
            pi,
            rule["id"],
            cu.emp_no,
            now,
        )
        audit.record(cu, audit.entry(audit.RULE_PI_BIND).pi(pi).info(f"{rule['rule_code']} v{res.version['version']}"))
        return {"pi_no": pi, "rule": info(res)}


def adopt_customer_template(cu: CurrentUser, pi_in: str | None, new_prefix: str | None) -> dict:
    """客户模板：为本 PI 换前缀另出版本，并绑定给本 PI。"""
    pi = util.trim(pi_in)
    from app.services import orders

    customer = orders.customer_of_pi(pi)
    c = None if customer is None else db.one("SELECT rule_id FROM sn_customer_rule WHERE customer_code = %s", customer)
    if c is None:
        raise biz(ErrorCode.RULE_NOT_FOUND)
    return bind_pi(cu, pi, c["rule_id"], new_prefix)


def unbind_pi(cu: CurrentUser, pi_in: str | None) -> dict:
    """解除 PI 绑定：规则转为通用模板，已选过规则的物料行不受影响。"""
    pi = util.trim(pi_in)
    with db.tx():
        b = db.one("SELECT * FROM sn_pi_rule WHERE pi_no = %s FOR UPDATE", pi)
        if b is not None:
            db.exec("DELETE FROM sn_pi_rule WHERE pi_no = %s", pi)
            r = db.one("SELECT rule_code FROM sn_rule WHERE id = %s", b["rule_id"])
            audit.record(cu, audit.entry(audit.RULE_PI_UNBIND).pi(pi).info(r["rule_code"] if r else str(b["rule_id"])))
    return {"pi_no": pi}


def bind_customer(cu: CurrentUser, customer_in: str | None, rule_id: int | None, rule_code: str | None) -> dict:
    """按客户绑定规则（F-22，唯一入口是接口）：每个客户只能绑一条；规则不存在或客户已绑其他规则时拒绝。"""
    customer = util.trim(customer_in)
    if not customer or len(customer) > 64:
        raise biz(ErrorCode.VALIDATION, detail="customer_code")
    with db.tx():
        if rule_id is not None:
            r = get_rule(rule_id)
        else:
            r = db.one("SELECT * FROM sn_rule WHERE rule_code = %s", util.trim(rule_code))
            if r is None:
                raise biz(ErrorCode.RULE_NOT_FOUND)
        cur = db.one("SELECT * FROM sn_customer_rule WHERE customer_code = %s FOR UPDATE", customer)
        if cur is not None:
            if cur["rule_id"] == r["id"]:
                return {"customer_code": customer, "rule_id": r["id"], "rule_code": r["rule_code"]}
            other = db.one("SELECT rule_code FROM sn_rule WHERE id = %s", cur["rule_id"])
            raise biz(ErrorCode.RULE_CUSTOMER_BOUND, customer=customer, rule_code=other["rule_code"] if other else "?")
        db.exec(
            "INSERT INTO sn_customer_rule(customer_code, rule_id, bound_by, bound_at) VALUES (%s,%s,%s,%s)",
            customer,
            r["id"],
            cu.emp_no,
            util.now(),
        )
        audit.record(cu, audit.entry(audit.RULE_CUSTOMER_BIND).customer(customer).info(r["rule_code"]))
        return {"customer_code": customer, "rule_id": r["id"], "rule_code": r["rule_code"]}


def unbind_customer(cu: CurrentUser, customer_in: str | None) -> dict:
    """在生成页解除客户绑定：规则转为通用模板。"""
    customer = util.trim(customer_in)
    with db.tx():
        cur = db.one("SELECT * FROM sn_customer_rule WHERE customer_code = %s FOR UPDATE", customer)
        if cur is not None:
            db.exec("DELETE FROM sn_customer_rule WHERE customer_code = %s", customer)
            r = db.one("SELECT rule_code FROM sn_rule WHERE id = %s", cur["rule_id"])
            audit.record(
                cu, audit.entry(audit.RULE_CUSTOMER_UNBIND).customer(customer).info(r["rule_code"] if r else "")
            )
    return {"customer_code": customer}


def options(pi: str | None, customer: str | None) -> list[dict]:
    """生成页规则库选项：分类与本 PI 或本 PI 客户相同的标「推荐」排前面；给出本 PI 用这条规则时的版本与是否须换前缀。"""
    out = []
    versions = _versions_by_rule()
    for r in db.all("SELECT * FROM sn_rule ORDER BY rule_code"):
        vs = versions.get(r["id"], [])
        res = _resolve_in(r, vs, pi, SRC_LINE) if pi else None
        recommended = (r["category_scope"] == PI and r["category_value"] == pi) or (
            r["category_scope"] == CUSTOMER and customer and r["category_value"] == customer
        )
        t = _current_of(r, vs)
        item = {
            "rule_id": r["id"],
            "rule_code": r["rule_code"],
            "rule_name": r["rule_name"],
            "category_scope": r["category_scope"],
            "category_value": r["category_value"],
            "recommended": bool(recommended),
            "format": None if t is None else format_text(spec_of(t)),
            "current_prefix": None if t is None else t["prefix"],
            "used_pis": len({v["claimed_pi"] for v in vs if v["claimed_pi"] is not None}),
            "plan": None if res is None else res.plan,
            "other_pi": None if res is None else res.other_pi,
        }
        out.append(item)
    out.sort(key=lambda x: (not x["recommended"], x["rule_code"]))
    return out


# ================================================================== 生成事务内落库


_CODE_UNSAFE = re.compile(r"[^A-Za-z0-9_-]")


def _custom_names(pi: str) -> tuple[str, str]:
    """自定义规则：编码 PI-PI号-n，名称 PI号_vn（n 为本 PI 第几条自定义规则，两者一致）。"""
    stem = "PI-" + _CODE_UNSAFE.sub("_", pi)[:50] + "-"
    taken = set(db.column("SELECT rule_code FROM sn_rule WHERE rule_code LIKE %s", util.like_prefix(stem)))
    k = 1
    while f"{stem}{k}" in taken:
        k += 1
    return f"{stem}{k}", util.cut(f"{pi}_v{k}", 128)


def realize(cu: CurrentUser, pi: str, res: Resolved, lines: list[tuple[str, int]]) -> dict:
    """生成事务内：把本次要用的版本落库、标记已使用并归属本 PI，返回版本行。
    与预演时的判断不一致（期间被别的 PI 先用、前缀被占用）时报预演已失效。"""
    if res.plan == PLAN_NEW:
        spec = res.spec()
        check_prefix(spec.prefix)
        code, name = _custom_names(pi)
        rid = _insert_rule(cu, code, name, spec, (PI, pi), " (自定义规则，生成成功后存入规则库)", claimed_pi=pi)
        # 物料行改记为这条规则
        for no, seq in lines:
            db.exec(
                "UPDATE sn_line_rule SET rule_id = %s, custom_json = NULL WHERE bill_no = %s AND seq = %s", rid, no, seq
            )
        return db.one("SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = 1", rid)
    if res.version is None:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    n = db.exec(
        "UPDATE sn_rule_version SET used = 1, claimed_pi = %s WHERE id = %s AND (claimed_pi IS NULL OR claimed_pi = "
        "%s)",
        pi,
        res.version["id"],
        pi,
    )
    if n == 0 and not db.exists(
        "SELECT EXISTS(SELECT 1 FROM sn_rule_version WHERE id = %s AND claimed_pi = %s)", res.version["id"], pi
    ):
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    return db.one("SELECT * FROM sn_rule_version WHERE id = %s", res.version["id"])


# ================================================================== 进制流水显示


# 已被号引用的版本参数不可变，可放心缓存
_spec_cache: dict[int, codec.Spec | None] = {}
_cache_lock = threading.Lock()


def _forget(version_id: int) -> None:
    with _cache_lock:
        _spec_cache.pop(version_id, None)


def cached_spec(version_id: int | None) -> codec.Spec | None:
    if version_id is None:
        return None
    with _cache_lock:
        if version_id in _spec_cache:
            return _spec_cache[version_id]
    v = db.one("SELECT * FROM sn_rule_version WHERE id = %s", version_id)
    s = None if v is None else spec_of(v)
    if s is not None:
        with _cache_lock:
            _spec_cache[version_id] = s
    return s


def seq_text(version_id: int | None, seq_dec: int | None) -> str:
    s = cached_spec(version_id)
    if s is None or seq_dec is None:
        return ""
    try:
        return codec.encode_seq(seq_dec, s)
    except (codec.SeqOverflow, ValueError):
        return ""
