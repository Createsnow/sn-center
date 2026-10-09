"""SN 规则。绑定：按单张 PI ＞ 按客户；都没绑定时，由用户在首次生成时选一条规则（默认通用规则），
之后该 PI 一直沿用这条（同一 PI 一套流水、一种格式）。按客户 / 按 PI 每个对象只能绑一条；通用规则可以有多条，
未绑定的 PI 选规则时都能看到。

改前缀 / 后缀 / 进制 / 位数 / 字符集：该版本还没生成过号时就地修改，否则另出一版；只改名称不出版本。
新版本只作用于之后新生成的号，已发出的号仍记旧版本。
"""

from __future__ import annotations

import re
import threading
from dataclasses import dataclass

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit

GENERAL = "GENERAL"
CUSTOMER = "CUSTOMER"
PI = "PI"
SCOPES = frozenset((GENERAL, CUSTOMER, PI))
_CODE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

RULE_FIELDS = (
    "id",
    "rule_code",
    "rule_name",
    "bind_scope",
    "bind_value",
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


#: 规则来源：按 PI 绑定 / 按客户绑定 / 该 PI 首次生成时选定 / 本次所选 / 未选定时默认的通用规则
SRC_PI = "PI"
SRC_CUSTOMER = "CUSTOMER"
SRC_CHOSEN = "CHOSEN"
SRC_PICKED = "PICKED"
SRC_GENERAL = "GENERAL"


@dataclass(frozen=True)
class Resolved:
    rule: dict
    version: dict
    source: str = SRC_GENERAL

    @property
    def fixed(self) -> bool:
        """已确定、生成时不再让用户选：有绑定或该 PI 已选定过。"""
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


def list_rules(scope: str | None, q: str | None) -> list[dict]:
    sql = "SELECT * FROM sn_rule WHERE 1=1"
    args: list = []
    if not util.blank(scope):
        sql += " AND bind_scope = %s"
        args.append(util.trim(scope))
    text = util.trim(q)
    if text:
        like = "%" + text + "%"
        sql += " AND (rule_code LIKE %s OR rule_name LIKE %s OR bind_value LIKE %s)"
        args += [like, like, like]
    sql += " ORDER BY bind_scope ASC, bind_value ASC"
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


def _insert_version(cu: CurrentUser, rule_id: int, version: int, spec: codec.Spec, now) -> None:
    db.insert(
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, used, created_by, "
        "created_at, updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s)",
        rule_id,
        version,
        spec.prefix,
        spec.suffix,
        spec.base,
        spec.seq_len,
        spec.charset,
        cu.emp_no,
        now,
        now,
    )


# ---------------------------------------------------------------- 维护


def create(
    cu: CurrentUser, code: str | None, name: str | None, scope: str | None, bind_value: str | None, s: SpecIn
) -> dict:
    with db.tx():
        c = util.trim(code)
        if not _CODE.match(c):
            raise biz(ErrorCode.RULE_CODE_INVALID)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        sc = util.trim(scope)
        if sc not in SCOPES:
            raise biz(ErrorCode.RULE_SCOPE_INVALID)
        bv = "" if sc == GENERAL else util.trim(bind_value)
        if sc != GENERAL and not bv:
            raise biz(ErrorCode.RULE_BIND_REQUIRED)
        spec = normalize(s)
        if db.count("SELECT COUNT(*) FROM sn_rule WHERE rule_code = %s", c) > 0:
            raise biz(ErrorCode.RULE_CODE_EXISTS, rule_code=c)
        # 通用规则可以有多条；按客户 / 按 PI 每个对象只能一条
        dup = None
        if sc != GENERAL:
            dup = db.one("SELECT rule_code FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", sc, bv)
        if dup is not None:
            raise biz(ErrorCode.RULE_BIND_EXISTS, rule_code=dup["rule_code"])
        now = util.now()
        rid = db.insert(
            "INSERT INTO sn_rule(rule_code, rule_name, bind_scope, bind_value, current_version, created_by, "
            "created_at, updated_by, updated_at) VALUES (%s,%s,%s,%s,1,%s,%s,%s,%s)",
            c,
            n,
            sc,
            bv,
            cu.emp_no,
            now,
            cu.emp_no,
            now,
        )
        _insert_version(cu, rid, 1, spec, now)
        audit.record(
            cu,
            audit.entry(audit.RULE_CREATE)
            .pi(bv if sc == PI else None)
            .customer(bv if sc == CUSTOMER else None)
            .info(f"{c} v1 {describe(spec)}"),
        )
        return view(db.one("SELECT * FROM sn_rule WHERE id = %s", rid))


def update(cu: CurrentUser, rule_id: int, name: str | None, s: SpecIn) -> dict:
    """修改结果：RENAMED 只改名 / UPDATED 就地修改当前版 / NEW_VERSION 另出一版 / UNCHANGED。"""
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
        spec_changed = spec != spec_of(cur)
        before = describe(spec_of(cur))
        renamed = n != r["rule_name"]
        now = util.now()
        action = "UNCHANGED"
        version = r["current_version"]
        if spec_changed:
            if cur["used"]:
                version = r["current_version"] + 1
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
        if renamed:
            audit.record(cu, audit.entry(audit.RULE_RENAME).info(f"{r['rule_code']} {r['rule_name']} -> {n}"))
            if action == "UNCHANGED":
                action = "RENAMED"
        if spec_changed or renamed:
            db.exec(
                "UPDATE sn_rule SET rule_name = %s, current_version = %s, updated_by = %s, updated_at = %s "
                "WHERE id = %s",
                n,
                version,
                cu.emp_no,
                now,
                r["id"],
            )
        return {"action": action, "rule": view(db.one("SELECT * FROM sn_rule WHERE id = %s", r["id"]))}


# ---------------------------------------------------------------- 生效规则


def bound(pi: str, customer: str | None) -> Resolved | None:
    """绑定到该 PI 的规则：按 PI ＞ 按客户。没有返回 None。"""
    r = db.one("SELECT * FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", PI, pi)
    if r is not None:
        return Resolved(r, current_version(r), SRC_PI)
    if not util.blank(customer):
        r = db.one("SELECT * FROM sn_rule WHERE bind_scope = %s AND bind_value = %s", CUSTOMER, customer)
        if r is not None:
            return Resolved(r, current_version(r), SRC_CUSTOMER)
    return None


def chosen_rule_id(pi: str, counter_rule_id: int | None = None) -> int | None:
    """没有绑定的 PI 首次生成时选定的规则；早于「选定」记录的 PI 取其最近一次成功生成所用的规则。"""
    if counter_rule_id is not None:
        return counter_rule_id
    rid = db.scalar("SELECT rule_id FROM sn_pi_counter WHERE pi_no = %s", pi)
    if rid is not None:
        return rid
    return db.scalar(
        "SELECT v.rule_id FROM sn_gen_job j JOIN sn_rule_version v ON v.id = j.rule_version_id "
        "WHERE j.pi_no = %s AND j.status = 'SUCCESS' ORDER BY j.id DESC LIMIT 1",
        pi,
    )


def by_id(rule_id: int, source: str) -> Resolved:
    r = db.one("SELECT * FROM sn_rule WHERE id = %s", rule_id)
    if r is None:
        raise biz(ErrorCode.RULE_NOT_FOUND)
    return Resolved(r, current_version(r), source)


def fixed(pi: str, customer: str | None, counter_rule_id: int | None = None) -> Resolved | None:
    """已确定的规则：绑定 ＞ 该 PI 已选定的规则。没有返回 None（生成时须由用户选）。"""
    r = bound(pi, customer)
    if r is not None:
        return r
    rid = chosen_rule_id(pi, counter_rule_id)
    return None if rid is None else by_id(rid, SRC_CHOSEN)


def general() -> Resolved | None:
    """未选定时的默认：通用规则中编码最小的一条（与选项列表的顺序一致）。"""
    r = db.one("SELECT * FROM sn_rule WHERE bind_scope = %s ORDER BY rule_code ASC LIMIT 1", GENERAL)
    return None if r is None else Resolved(r, current_version(r), SRC_GENERAL)


def resolve(pi: str, customer: str | None) -> Resolved | None:
    """该 PI 生效的规则：按 PI ＞ 按客户 ＞ 已选定 ＞ 通用（尚未选定时的默认）。没有返回 None。"""
    return fixed(pi, customer) or general()


def for_generation(pi: str, customer: str | None, rule_id: int | None, counter_rule_id: int | None = None) -> Resolved:
    """本次生成用的规则：已确定的规则优先（忽略所选）；否则必须由用户选一条。"""
    r = fixed(pi, customer, counter_rule_id)
    if r is not None:
        return r
    if rule_id is None:
        if db.count("SELECT COUNT(*) FROM sn_rule") == 0:
            raise biz(ErrorCode.RULE_MISSING, pi=pi, customer=customer or "")
        raise biz(ErrorCode.RULE_CHOICE_REQUIRED, pi=pi)
    return by_id(rule_id, SRC_PICKED)


def options() -> list[dict]:
    """可供选择的规则（各取当前版本）：全部通用规则排最前，再是其它规则（作模板用）。"""
    rows = db.all("SELECT * FROM sn_rule ORDER BY CASE bind_scope WHEN %s THEN 0 ELSE 1 END, rule_code ASC", GENERAL)
    return [rule_info(Resolved(r, current_version(r))) for r in rows]


def lock_for_generation(version_id: int) -> dict:
    """生成事务内：锁住并标记版本已使用，返回最新参数。"""
    db.exec("UPDATE sn_rule_version SET used = 1 WHERE id = %s", version_id)
    return db.one("SELECT * FROM sn_rule_version WHERE id = %s", version_id)


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


def rule_info(r: Resolved) -> dict:
    s = spec_of(r.version)
    return {
        "rule_id": r.rule["id"],
        "source": r.source,
        "rule_code": r.rule["rule_code"],
        "rule_name": r.rule["rule_name"],
        "bind_scope": r.rule["bind_scope"],
        "bind_value": r.rule["bind_value"],
        "version_id": r.version["id"],
        "version": r.version["version"],
        "prefix": s.prefix,
        "suffix": s.suffix,
        "base": s.base,
        "seq_len": s.seq_len,
        "charset": s.charset,
        "max_seq": codec.max_seq(s),
        "sample_sn": codec.format_sn(1, s),
    }
