"""SN 规则库与规则选取（F-08）。

全局不撞号（5.2.4）：前缀归属 PI + PI 流水唯一（同一 PI 内一个十进制流水同一时刻只有一枚号）。
- 一个前缀只归属一张 PI：规则版本首次用于生成（或为某张 PI 换前缀另出、自定义规则生成）时归属该 PI；
  别的 PI 要用同一条规则，须为它填新前缀，按原格式另出属于它的版本（derived_from 记由哪一版而来）。
  同一 PI 的多个物料行可共用一个版本。
- 同一前缀下进制、流水位数、字符表锁定（流水紧跟前缀、定长，前缀 + 流水才能唯一区分号）；米数（补 0、前后分隔符）、
  单位、后缀可改：版本生成过号后改这几项，另出一版沿用同一前缀（仍归属原 PI）；改进制、位数或字符表须换新前缀。
  同一前缀可有多个版本（同一 PI 的不同规则也可共用），都须归属同一张 PI、格式的流水部分相同；
  尚未归属 PI 的版本独占前缀。
- 不同 PI（或尚未归属）的前缀不能相同；任何两个不同的前缀都不能一个是另一个的开头（如 SD 与 SDD，同一 PI 内也不行：
  流水不同也可能拼出相同的号）。万一拼出的完整 SN 相同，仍由号池唯一约束拦下、整次生成回滚。

规则按 PI 维度绑定（不按客户绑定）：新建规则须填 PI（分类只能是 PI）；早先按未分类 / 客户分类的规则原样保留，
修改时须补填 PI。规则选取按物料行：物料行已选规则 ＞ PI 绑定规则，都没有时须先选规则。
选法：规则库（选一条规则，本 PI 用哪一版见 resolve）或自定义（前缀、进制、位数、米数及其补 0 位数与前后分隔符、
是否带单位、后缀）；
自定义规则生成成功后存入规则库并归属本 PI（编码 PI-PI号-n，名称 PI号_vn，n 为本 PI 第几条自定义规则），
本 PI 选了同一自定义规则、这次没生成的物料行一并改记为这条规则；生成失败或取消不留规则。
自定义前缀默认取点选的第一个单据编码后 7 位；本 PI 已生成的号还在时默认沿用已用的前缀（见 pi_prefix）。
前缀已被本 PI 同格式的版本占用时
沿用该版本（own_version；这时填的起始号另记在物料行，下次生成从 max(PI 下一个号, 起始号) 起，跳过的流水成为空号段），
被别的 PI、别的规则或别的格式占用时报前缀冲突；
自定义规则的前缀在选定后被别的规则先占用时，预演标「规则须换前缀」，确认生成时报前缀冲突。
物料行生成过号后可改选，已生成的号不变，流水接续。

流水上限自动突破（自定义规则的开关，默认开启；记在物料行上，不是格式的一部分）：开启时流水用完规则位数后自动加位
（4 位用到 9999 后接 5 位 10000，以此类推，最多 12 位）。每个位数各是一个版本：生成时按需为本规则另出
「同前缀、同格式、只有流水位数不同」的版本（突破版本：derived_from 为原版本且前缀相同，与换前缀另出的版本区分；
归属同一 PI，不改规则的当前版本），号挂在自己位数的版本上；前缀校验时突破版本按原版本的流水格式比较。
关闭时保持原逻辑（用完报规则位数已用尽）。
"""

from __future__ import annotations

import json
import re
import threading
from dataclasses import dataclass, replace

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, factories, locks

#: 分类：新建、修改只能是 PI；NONE 未分类 / CUSTOMER 客户只出现在早先的规则上
NONE = "NONE"
CUSTOMER = "CUSTOMER"
PI = "PI"
_CODE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
#: 自定义规则的流水位数默认（规则库的默认值由页面给出）
CUSTOM_SEQ_LEN = 4

#: 规则来源（生效顺序）
SRC_LINE = "LINE"
SRC_PI = "PI"
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
    "factory_code",
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
    "length_pad",
    "length_sep_before",
    "length_sep_after",
    "with_unit",
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
    out = util.jrow(v, VERSION_FIELDS, ("used", "with_length", "with_unit"))
    out["format"] = format_text(spec_of(v))
    return out


def spec_of(v: dict) -> codec.Spec:
    return codec.Spec(
        v["prefix"],
        v["suffix"],
        v["base"],
        v["seq_len"],
        v["charset"],
        bool(v["with_length"]),
        int(v.get("length_pad") or 0),
        v.get("length_sep_before") or "",
        v.get("length_sep_after") or "",
        bool(v.get("with_unit")),
    )


def format_text(s: codec.Spec) -> str:
    """格式说明：HX + 6 位 10 进制流水 + - + 米数（补足 5 位）+ M + 单位 + A。"""
    parts = [s.prefix, f"{s.seq_len} 位 {s.base} 进制流水"]
    if s.with_length:
        if s.sep_before:
            parts.append(s.sep_before)
        parts.append(f"米数（补足 {s.length_pad} 位）" if s.length_pad else "米数")
        if s.sep_after:
            parts.append(s.sep_after)
    if s.with_unit:
        parts.append("单位")
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
    length_pad: int | None = 0
    sep_before: str | None = ""
    sep_after: str | None = ""
    with_unit: bool | None = False


def normalize(s: SpecIn) -> codec.Spec:
    """规范化并校验编码参数；字符表留空用该进制默认字符表。前缀、后缀、分隔符不限制字符，只去首尾空格。"""
    if s.base is None or s.base not in codec.DEFAULT_CHARSETS:
        raise biz(ErrorCode.RULE_BASE_INVALID)
    if s.seq_len is None:
        raise biz(ErrorCode.RULE_SEQ_LEN_INVALID)
    cs = util.trim(s.charset) or codec.DEFAULT_CHARSETS[s.base]
    with_length = True if s.with_length is None else s.with_length
    spec = codec.Spec(
        util.trim(s.prefix),
        util.trim(s.suffix),
        s.base,
        s.seq_len,
        cs,
        with_length,
        # 不带米数时补 0 位数、分隔符都不起作用，统一存空，免得同一格式存成两样
        (s.length_pad or 0) if with_length else 0,
        util.trim(s.sep_before) if with_length else "",
        util.trim(s.sep_after) if with_length else "",
        bool(s.with_unit),
    )
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    return spec


def prefix_taken(prefix: str, memo: dict[str, bool] | None = None) -> bool:
    """前缀是否已被某条规则的某个版本占用；memo：同一次判断里同一前缀只查一次。"""
    if memo is not None and prefix in memo:
        return memo[prefix]
    hit = db.exists("SELECT EXISTS(SELECT 1 FROM sn_rule_version WHERE prefix = %s)", prefix)
    if memo is not None:
        memo[prefix] = hit
    return hit


def same_seq_format(a: codec.Spec, b: codec.Spec) -> bool:
    """流水部分相同：进制、流水位数、字符表（同一前缀下锁定）。"""
    return (a.base, a.seq_len, a.charset) == (b.base, b.seq_len, b.charset)


def check_prefix(
    prefix: str,
    exclude_version: int | None = None,
    spec: codec.Spec | None = None,
    pi: str | None = None,
    gen: bool = False,
) -> None:
    """前缀能否给一个（归属 pi 的；pi = None 为尚未归属）版本用；区分大小写。
    - 已有同前缀的版本（不含 exclude_version）：都须归属同一张 PI（pi 非空），且进制、流水位数、字符表与 spec 相同，
      否则 RULE_PREFIX_CONFLICT（别的 PI 或尚未归属的版本在用）/ RULE_PREFIX_FORMAT_LOCKED（本 PI 但流水部分不同）。
    - 与任何别的前缀一个是另一个的开头：RULE_PREFIX_OVERLAP。
    在事务内调用：先拿这个前缀的前缀锁（锁到事务结束），并发占用同一前缀时后到的等前一个提交。
    规则库保存等短事务另对同前缀的版本加锁（FOR UPDATE，没有时锁住索引间隙）。
    gen（生成事务内）：不加 FOR UPDATE，按本事务所见与最新已提交的数据各判断一次。生成事务长（写几千到几十万个号），
    对还不存在的前缀 FOR UPDATE 锁住的索引间隙要到写完号才放；几张 PI 同时首次生成、各自新建前缀时，
    新前缀落在同一个间隙里，各自持有间隙锁又都要往里插入，成为死锁（报告第一轮 8 户 6 户失败即此）。"""
    locks.hold_prefix(prefix)
    if not gen:
        _prefix_rules(prefix, spec, pi, exclude_version, lock=True)
        return
    # 本事务里自己写过的（未提交）
    _prefix_rules(prefix, spec, pi, exclude_version, lock=False)
    # 最新已提交的：独立短事务里读（本事务的一致性快照可能早于别的生成提交；同前缀的提交已由前缀锁排在前面）
    with db.new_tx():
        _prefix_rules(prefix, spec, pi, exclude_version, lock=False)


def _prefix_rules(
    prefix: str, spec: codec.Spec | None, pi: str | None, exclude_version: int | None, lock: bool
) -> None:
    every = db.all("SELECT * FROM sn_rule_version WHERE prefix = %s" + (" FOR UPDATE" if lock else ""), prefix)
    by_id = {v["id"]: v for v in every}
    for v in every:
        if v["id"] == exclude_version:
            continue
        if pi is None or v["claimed_pi"] != pi:
            raise biz(ErrorCode.RULE_PREFIX_CONFLICT, prefix=prefix)
        # 突破版本（同前缀、由原版本加位而来）按原版本的流水格式比较
        b = by_id.get(v["derived_from"]) or v
        if spec is not None and not same_seq_format(spec_of(b), spec):
            raise biz(ErrorCode.RULE_PREFIX_FORMAT_LOCKED, prefix=prefix, base=b["base"], seq_len=b["seq_len"])
    for v in _overlapping(prefix):
        # 不论是否同一 PI：不同前缀一个是另一个的开头时，流水不同也可能拼出相同的号（如 P1+03+后缀 5 与 P103+5）
        raise biz(ErrorCode.RULE_PREFIX_OVERLAP, prefix=prefix, other=v["prefix"], pi=v["claimed_pi"] or "-")


def _overlapping(prefix: str) -> list[dict]:
    """与 prefix 一个是另一个开头的别的前缀（最多一行，取最早建的版本，与原来整表扫描时报的是同一个）。
    两头都走前缀索引 ix_rule_prefix，不随规则版本数变慢：以 prefix 开头的更长前缀按 LIKE 'prefix%' 取索引区间；
    是 prefix 开头的更短前缀就是 prefix 的各个真前缀，按等值查。"""
    heads = [prefix[:k] for k in range(1, len(prefix))]
    longer = "SELECT id, prefix, claimed_pi FROM sn_rule_version WHERE prefix LIKE %s AND prefix <> %s"
    shorter = f" UNION ALL SELECT id, prefix, claimed_pi FROM sn_rule_version WHERE prefix IN ({marks(len(heads))})"
    return db.all(
        "SELECT prefix, claimed_pi FROM (" + longer + (shorter if heads else "") + ") o ORDER BY id LIMIT 1",
        util.like_prefix(prefix),
        prefix,
        *heads,
    )


def prefix_usable(prefix: str, spec: codec.Spec, pi: str, exclude_version: int | None = None) -> bool:
    """check_prefix 的判断版（不加锁、不抛错）：前缀能否给归属 pi、格式为 spec 的版本用。"""
    try:
        _prefix_rules(prefix, spec, pi, exclude_version, lock=False)
    except BizError:
        return False
    return True


def own_version(pi: str, spec: codec.Spec) -> Resolved | None:
    """自定义规则与本 PI 已有的版本完全同格式（如先生成的单据存入规则库的自定义规则）：同一 PI 的各张单据
    共用这一版，不再另建规则。没有完全同格式、且规则在本 PI 下正用这一版的版本时 None
    （前缀能否共用见 check_prefix）。"""
    for v in db.all("SELECT * FROM sn_rule_version WHERE prefix = %s AND claimed_pi = %s", spec.prefix, pi):
        if spec_of(v) != spec:
            continue
        r = db.one("SELECT * FROM sn_rule WHERE id = %s", v["rule_id"])
        if r is None:
            continue
        res = resolve(r, pi, SRC_LINE)
        # 规则在本 PI 下用的须正是这一版（规则另出过新版时不沿用，免得之后按新版生成）
        if res.version is not None and res.version["id"] == v["id"]:
            return res
    return None


def seq_label(seq: int, spec: codec.Spec, grow: bool = False) -> str:
    """流水号文本（留痕用）；上限自动突破时按突破后的位数。"""
    return codec.encode_seq(seq, codec.widen(spec, codec.seq_width(seq, spec)) if grow else spec)


def is_grow_version(v: dict, parent: dict | None) -> bool:
    """v 是否为 parent 上限自动突破另出的版本（derived_from 为 parent 且前缀相同；换前缀另出的版本前缀不同）。"""
    return parent is not None and v.get("derived_from") == parent["id"] and v["prefix"] == parent["prefix"]


def base_of(v: dict) -> dict:
    """突破版本的原版本；不是突破版本时为它自己。"""
    if v.get("derived_from") is None:
        return v
    p = db.one("SELECT * FROM sn_rule_version WHERE id = %s", v["derived_from"])
    return p if is_grow_version(v, p) else v


def grow_version(cu: CurrentUser, pi: str, base: dict, width: int) -> dict:
    """生成事务内：版本 base 的流水上限自动突破到 width 位时用的版本。同规则、同前缀、同格式，只有流水位数为 width，
    归属本 PI、derived_from = base；已有则沿用，没有则另出一版（版本号接着排，不改规则的当前版本）。
    前缀已归属本 PI（base 已落库归属），不另查前缀、不另拿前缀锁（命名锁各占一条连接，同一事务再拿会等自己；
    同一 PI 的生成由 PI 操作锁串行）；先锁规则行，与规则库改版排队（版本号不撞）。"""
    if width == base["seq_len"]:
        return base
    db.one("SELECT id FROM sn_rule WHERE id = %s FOR UPDATE", base["rule_id"])
    v = db.one(
        "SELECT * FROM sn_rule_version WHERE rule_id = %s AND derived_from = %s AND prefix = %s AND seq_len = %s "
        "AND claimed_pi = %s",
        base["rule_id"],
        base["id"],
        base["prefix"],
        width,
        pi,
    )
    if v is not None:
        return v
    spec = codec.widen(spec_of(base), width)
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    n = _next_version(base["rule_id"])
    vid = _insert_version(cu, base["rule_id"], n, spec, util.now(), used=True, claimed_pi=pi, derived_from=base["id"])
    code = db.scalar("SELECT rule_code FROM sn_rule WHERE id = %s", base["rule_id"])
    audit.record(
        cu,
        audit.entry(audit.RULE_VERSION)
        .pi(pi)
        .info(
            f"{code} v{n} {describe(spec)} (v{base['version']} 流水用完 {base['seq_len']} 位，自动突破到 {width} 位)"
        ),
    )
    return db.one("SELECT * FROM sn_rule_version WHERE id = %s", vid)


def pi_prefix(pi: str) -> dict:
    """生成页「选规则」的默认前缀（返回结构不变：prefix、custom）。
    - 本 PI 已生成的号（流水归属本 PI，含已转到别的单据 / PI 的）还在：custom 为这个前缀的格式，prefix 为它，
      之后各张单据都沿用（保存时沿用那一版、流水接续）；不论这个前缀是单据编码后 7 位还是手填的。
      用过几个前缀时取流水最小的号所在的那个（还在的号里最早生成的）。
    - 还没有生成的号，或已生成的号全部删掉了：custom 为 None，页面按默认规则取点选的第一个单据编码后 7 位；
      prefix 为 PI 第一张单据（单据编号最小）编码后 7 位，只在取不到点选的单据时兜底。
      只给物料行保存了规则、还没有生成的，不算已用。"""
    used = db.one(
        "SELECT v.* FROM sn_item i JOIN sn_rule_version v ON v.id = i.rule_version_id "
        "WHERE i.seq_pi_no = %s AND i.origin = 'GEN' ORDER BY i.seq_dec LIMIT 1",
        pi,
    )
    if used is None:
        first = db.scalar("SELECT MIN(bill_no) FROM sn_prd_mo WHERE pi = %s", pi) or ""
        return {"prefix": first[-7:], "custom": None}
    # 格式取本 PI 这个前缀最新的版本（规则另出过新版时与 own_version 沿用的一致；不算上限自动突破另出的版本），
    # 没有时取号所在的版本（号在突破版本上时取它的原版本）
    v = db.one(
        "SELECT v.* FROM sn_rule_version v LEFT JOIN sn_rule_version p ON p.id = v.derived_from "
        "AND p.prefix = v.prefix WHERE v.prefix = %s AND v.claimed_pi = %s AND p.id IS NULL "
        "ORDER BY v.id DESC LIMIT 1",
        used["prefix"],
        pi,
    ) or base_of(used)
    return {"prefix": used["prefix"], "custom": _custom_dict(spec_of(v))}


def pi_prefix_locked(pi: str) -> dict[str, dict]:
    """本 PI 已占用的前缀 → 锁定的格式（同 pi_prefix 的 custom：本 PI 这个前缀最新的版本，不算上限自动突破另出的版本）。
    号全部删掉后前缀仍归属本 PI、进制和流水位数仍锁定（check_prefix）：生成页「选规则」前缀是其中之一时按这个格式，
    免得按默认位数保存时报 RULE_PREFIX_FORMAT_LOCKED。"""
    out: dict[str, dict] = {}
    for v in db.all(
        "SELECT v.* FROM sn_rule_version v LEFT JOIN sn_rule_version p ON p.id = v.derived_from "
        "AND p.prefix = v.prefix WHERE v.claimed_pi = %s AND p.id IS NULL ORDER BY v.id",
        pi,
    ):
        out[v["prefix"]] = _custom_dict(spec_of(v))
    return out


def _adopt(cu: CurrentUser, rule: dict, pi: str, orgs: set[str]) -> None:
    """物料行沿用本 PI 已有的规则版本（own_version）：生成时自动存入的本 PI 自定义规则被别的生产组织的物料行共用时
    改为全局规则（F-08 规则所属组织）；其他规则须能选用。"""
    if rule["factory_code"] is None or orgs <= {rule["factory_code"]}:
        return
    if rule["category_scope"] == PI and rule["category_value"] == pi:
        db.exec("UPDATE sn_rule SET factory_code = NULL WHERE id = %s", rule["id"])
    else:
        check_visible(cu, rule)


#: 示例号用的米数、单位（页面不填时）
SAMPLE_LENGTH = "100"
SAMPLE_UNIT = "M"


def preview(
    s: SpecIn, length: str | None = None, unit: str | None = None, seq_start: int | None = None, grow: bool = False
) -> dict:
    """编辑时的示例号：前 5 个和最大号（米数、单位用示例值）、最大流水（规则位数的）。seq_start：从这个流水起
    （自定义规则的起始号），不填从 1 起。grow（上限自动突破）：起始号可超过规则位数（按突破后的位数），
    最大号之后另带突破后的第一个号（最大流水 + 1）。"""
    spec = normalize(s)
    sample_len = codec.clean_length(length) or SAMPLE_LENGTH
    sample_unit = util.trim(unit) or SAMPLE_UNIT
    mx = codec.max_seq(spec)
    first = check_seq_start(seq_start, spec, grow) or 1
    seqs = list(range(first, min(first + 4, mx) + 1)) + ([mx] if mx > first + 4 else [])
    if grow:
        seqs = list(range(first, first + 5)) if first > mx else seqs + [mx + 1]
    seqs = [i for i in seqs if i <= (codec.max_seq_grow(spec) if grow else mx)]

    def text(i: int) -> codec.Spec:
        return codec.widen(spec, codec.seq_width(i, spec)) if grow else spec

    samples = [
        {
            "seq_dec": i,
            "seq_text": codec.encode_seq(i, text(i)),
            "sn": codec.format_sn(i, text(i), sample_len, sample_unit),
        }
        for i in seqs
    ]
    return {
        "max_seq": mx,
        "format": format_text(spec),
        "sample_length": sample_len,
        "sample_unit": sample_unit,
        "samples": samples,
    }


def describe(s: codec.Spec) -> str:
    return (
        f"prefix={s.prefix} suffix={s.suffix} base={s.base} len={s.seq_len} with_length={int(s.with_length)} "
        f"length_pad={s.length_pad} sep_before={s.sep_before} sep_after={s.sep_after} "
        f"with_unit={int(s.with_unit)} charset={s.charset}"
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
    """规则库列表与详情：每条规则的版本、用过的 PI、PI 绑定（一次取齐，不逐条查）。"""
    ids = [r["id"] for r in rs]
    versions = _versions_by_rule(ids)
    pi_bind = _column_by_rule("SELECT rule_id, pi_no v FROM sn_pi_rule WHERE rule_id IN ({}) ORDER BY pi_no", ids)
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
                "bindings": {"pi": pi_bind.get(r["id"], [])},
            }
        )
    return out


def view(r: dict) -> dict:
    return _views([r])[0]


def _visible_sql(facs: tuple[str, ...] | None) -> tuple[str, list]:
    """授权范围内能看到、能选用的规则：全局规则 + 范围内组织的规则（None = 全部）。"""
    if facs is None:
        return "", []
    fsql, fargs = factories.scope_sql(facs)
    return f" AND (factory_code IS NULL OR (1=1{fsql}))", fargs


def check_visible(cu: CurrentUser, r: dict) -> None:
    """选用规则：全局规则或授权组织的规则。"""
    if r["factory_code"] is not None and not cu.can_read(r["factory_code"]):
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.scope_text())


def check_editable(cu: CurrentUser, r: dict) -> None:
    """修改、删除规则：系统管理员不限；普通操作员只能改授权组织的规则，全局规则只能选用。"""
    if cu.is_admin:
        return
    if r["factory_code"] is None:
        raise biz(ErrorCode.RULE_GLOBAL_READONLY, rule_code=r["rule_code"])
    cu.check_write(r["factory_code"])


def _owner(cu: CurrentUser, factory_code: str | None) -> str | None:
    """新建规则的所属组织：系统管理员可不填（全局规则）；普通操作员须是授权组织，只授权一个时可不填。"""
    f = util.trim_or_none(factory_code)
    if f is not None:
        cu.check_write(f)
        return factories.must_get(f)["factory_code"]
    if cu.is_admin:
        return None
    if len(cu.factories) == 1:
        return cu.factories[0]
    raise biz(ErrorCode.RULE_FACTORY_REQUIRED)


def list_rules(
    q: str | None,
    category: str | None = None,
    category_value: str | None = None,
    facs: tuple[str, ...] | None = None,
) -> list[dict]:
    """规则库；facs = 授权范围（None = 全部）：只列全局规则和范围内组织的规则。"""
    sql = "SELECT * FROM sn_rule WHERE 1=1"
    vsql, args = _visible_sql(facs)
    sql += vsql
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
            "(SELECT rule_id FROM sn_rule_version WHERE claimed_pi LIKE %s))"
        )
        args += [like] * 4
    sql += " ORDER BY category_scope = %s DESC, category_value ASC, rule_code ASC"
    args.append(PI)
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
    """规则只绑定 PI：分类不填按 PI，填了只能是 PI；PI 必填，不超过 64 位。"""
    cs = util.trim(category) or PI
    if cs != PI:
        raise biz(ErrorCode.RULE_CATEGORY_INVALID)
    cv = util.trim(value)
    if not cv or len(cv) > 64:
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
        "INSERT INTO sn_rule_version(rule_id, version, prefix, suffix, base, seq_len, charset, with_length, "
        "length_pad, length_sep_before, length_sep_after, with_unit, used, claimed_pi, derived_from, created_by, "
        "created_at, updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        rule_id,
        version,
        spec.prefix,
        spec.suffix,
        spec.base,
        spec.seq_len,
        spec.charset,
        1 if spec.with_length else 0,
        spec.length_pad,
        spec.sep_before,
        spec.sep_after,
        1 if spec.with_unit else 0,
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
    factory: str | None = None,
) -> int:
    now = util.now()
    rid = db.insert(
        "INSERT INTO sn_rule(rule_code, rule_name, category_scope, category_value, factory_code, current_version, "
        "created_by, created_at, updated_by, updated_at) VALUES (%s,%s,%s,%s,%s,1,%s,%s,%s,%s)",
        code,
        name,
        cat[0],
        cat[1],
        factory,
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
        .factory(factory)
        .info(f"{code} v1 {describe(spec)}{note}"),
    )
    return rid


def create(
    cu: CurrentUser,
    code: str | None,
    name: str | None,
    s: SpecIn,
    category=None,
    category_value=None,
    factory_code: str | None = None,
) -> dict:
    """新建规则：须填 PI（category_value）；factory_code = 所属生产组织（系统管理员可不填 = 全局规则，
    普通操作员须是授权组织）。"""
    with db.tx():
        owner = _owner(cu, factory_code)
        c = util.trim(code)
        if not _CODE.match(c):
            raise biz(ErrorCode.RULE_CODE_INVALID)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        cat = _check_category(category, category_value)
        spec = normalize(s)
        check_prefix(spec.prefix, spec=spec)
        if db.exists("SELECT EXISTS(SELECT 1 FROM sn_rule WHERE rule_code = %s)", c):
            raise biz(ErrorCode.RULE_CODE_EXISTS, rule_code=c)
        rid = _insert_rule(cu, c, n, spec, cat, factory=owner)
        return view(get_rule(rid))


def _next_version(rule_id: int) -> int:
    return int(db.scalar("SELECT COALESCE(MAX(version), 0) FROM sn_rule_version WHERE rule_id = %s", rule_id)) + 1


def update(cu: CurrentUser, rule_id: int, name: str | None, s: SpecIn, category=None, category_value=None) -> dict:
    """结果：UPDATED 就地修改（当前版本从没生成过号）/ NEW_VERSION 另出一版（须换新前缀）/ RENAMED /
    RECATEGORIZED（改 PI）/ UNCHANGED。分类与 PI 都不传则不变（早先未分类 / 客户分类的规则原样保留）。"""
    with db.tx():
        r = get_rule(rule_id, lock=True)
        check_editable(cu, r)
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.RULE_NAME_REQUIRED)
        cur = db.one(
            "SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = %s FOR UPDATE",
            r["id"],
            r["current_version"],
        )
        spec = normalize(s)
        cs, cv = (
            (r["category_scope"], r["category_value"])
            if category is None and category_value is None
            else _check_category(category, category_value)
        )
        before = spec_of(cur)
        action = "UNCHANGED"
        version = r["current_version"]
        now = util.now()
        if spec != before:
            if cur["used"]:
                # 生成过号：另出一版。沿用同一前缀（只改米数、单位、后缀）时仍归属原 PI，流水部分须相同；
                # 换新前缀时新版本尚未归属，首次生成时归属
                same_prefix = spec.prefix == before.prefix
                owner = cur["claimed_pi"] if same_prefix else None
                check_prefix(spec.prefix, spec=spec, pi=owner)
                version = _next_version(r["id"])
                _insert_version(cu, r["id"], version, spec, now, claimed_pi=owner)
                action = "NEW_VERSION"
                audit.record(
                    cu,
                    audit.entry(audit.RULE_VERSION).info(
                        f"{r['rule_code']} v{version} {describe(spec)} (was {describe(before)})"
                    ),
                )
            else:
                if spec.prefix != before.prefix or not same_seq_format(spec, before):
                    check_prefix(spec.prefix, cur["id"], spec, cur["claimed_pi"])
                db.exec(
                    "UPDATE sn_rule_version SET prefix = %s, suffix = %s, base = %s, seq_len = %s, charset = %s, "
                    "with_length = %s, length_pad = %s, length_sep_before = %s, length_sep_after = %s, "
                    "with_unit = %s, updated_at = %s WHERE id = %s",
                    spec.prefix,
                    spec.suffix,
                    spec.base,
                    spec.seq_len,
                    spec.charset,
                    1 if spec.with_length else 0,
                    spec.length_pad,
                    spec.sep_before,
                    spec.sep_after,
                    1 if spec.with_unit else 0,
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
        check_editable(cu, r)
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
    #: plan = PREFIX：当前版本已归属的 PI
    other_pi: str | None = None
    #: 起始号（十进制流水）：从 PI 最大号之后编号时第一个号不小于它；不参与 key。自定义规则（plan = NEW）取规则参数；
    #: 自定义时沿用本 PI 已用前缀的版本（own_version）取物料行另记的起始号（见 _start_json），生成后清掉
    seq_start: int | None = None
    #: 流水上限自动突破（自定义规则的开关，记在物料行上，见 _start_json）；不参与 key（同格式共用同一版）
    seq_grow: bool = False

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
    没有则须换前缀。"""
    return _resolve_in(rule, _versions_of(rule["id"]), pi, source)


def _resolve_in(rule: dict, vs: list[dict], pi: str, source: str) -> Resolved:
    """同 resolve，版本已取出（vs 为该规则全部版本，新版本在前）。"""
    t = _current_of(rule, vs)
    if t is None:
        raise biz(ErrorCode.RULE_VERSION_NOT_FOUND)
    # 为本 PI 换前缀另出的版本（前缀不同；同前缀的是上限自动突破另出的版本，不算）
    own = next(
        (v for v in vs if v["derived_from"] == t["id"] and v["claimed_pi"] == pi and v["prefix"] != t["prefix"]), None
    )
    if t["claimed_pi"] == pi:
        return Resolved(source, MODE_LIBRARY, PLAN_USE, rule, t)
    if own is not None:
        return Resolved(source, MODE_LIBRARY, PLAN_USE, rule, own)
    if t["claimed_pi"] is None:
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
            c.get("with_length", False),
            c.get("length_pad") or 0,
            c.get("sep_before") or "",
            c.get("sep_after") or "",
            c.get("with_unit", False),
        )
    )


def check_seq_start(seq_start: int | None, spec: codec.Spec, grow: bool = False) -> int | None:
    """自定义规则的起始号：不填为 None；须为 1..规则最大流水（上限自动突破时为加到 12 位的最大流水）。"""
    if seq_start is None:
        return None
    mx = codec.max_seq_grow(spec) if grow else codec.max_seq(spec)
    if not 1 <= seq_start <= mx:
        raise biz(ErrorCode.RULE_SEQ_START_INVALID, max=mx)
    return int(seq_start)


def _custom_resolved(source: str, c: dict) -> Resolved:
    spec = _custom_spec(c)
    grow = bool(c.get("seq_grow"))
    start = check_seq_start(c.get("seq_start"), spec, grow)
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
        "length_pad": spec.length_pad,
        "length_sep_before": spec.sep_before,
        "length_sep_after": spec.sep_after,
        "with_unit": 1 if spec.with_unit else 0,
        "used": 0,
        "claimed_pi": None,
        "derived_from": None,
    }
    return Resolved(
        source, MODE_CUSTOM, PLAN_NEW, None, draft, custom=_custom_dict(spec), seq_start=start, seq_grow=grow
    )


def _custom_json(res: Resolved) -> str:
    """物料行选定的自定义规则存入 sn_line_rule.custom_json：格式参数，有起始号时另带 seq_start，
    开启上限自动突破时另带 seq_grow（关闭时不写，与原来相同）。"""
    c = dict(res.custom)
    if res.seq_start is not None:
        c["seq_start"] = res.seq_start
    if res.seq_grow:
        c["seq_grow"] = True
    return json.dumps(c, ensure_ascii=False, sort_keys=True)


def _start_json(seq_start: int | None, grow: bool = False) -> str | None:
    """物料行记 rule_id（沿用本 PI 已用前缀的版本，或自定义规则生成后改记的规则）时另记的：起始号 seq_start、
    上限自动突破 seq_grow（开启时），存入同一行的 custom_json；都没有为 None。
    起始号生成过一次就清掉（同自定义规则：起始号只管下一次生成，之后流水接续），
    上限自动突破一直保留（见 keep_grow）。"""
    c: dict = {}
    if seq_start is not None:
        c["seq_start"] = int(seq_start)
    if grow:
        c["seq_grow"] = True
    return json.dumps(c, sort_keys=True) if c else None


def _start_of(sel: dict) -> int | None:
    """物料行记了 rule_id 时 custom_json 里另记的起始号（见 _start_json）；没有为 None。"""
    if sel.get("rule_id") is None or not sel.get("custom_json"):
        return None
    v = json.loads(sel["custom_json"]).get("seq_start")
    return int(v) if isinstance(v, int) and v > 0 else None


def _grow_of(custom_json: str | None) -> bool:
    """物料行的 custom_json 里是否开启了上限自动突破。"""
    return bool(custom_json) and json.loads(custom_json).get("seq_grow") is True


def keep_grow(no: str, seq: int, rule_id: int | None = None) -> None:
    """物料行生成过号后（记 rule_id）：custom_json 只保留上限自动突破（起始号只管这一次生成）；没开启时清空。
    rule_id 给出时同时改记为这条规则（自定义规则生成后存入规则库）。"""
    cur = db.scalar("SELECT custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = %s", no, seq)
    cj = _start_json(None, _grow_of(cur))
    if rule_id is None:
        db.exec(
            "UPDATE sn_line_rule SET custom_json = %s WHERE bill_no = %s AND seq = %s AND rule_id IS NOT NULL",
            cj,
            no,
            seq,
        )
    else:
        db.exec(
            "UPDATE sn_line_rule SET rule_id = %s, custom_json = %s WHERE bill_no = %s AND seq = %s",
            rule_id,
            cj,
            no,
            seq,
        )


def _same_custom(custom_json: str, custom: dict) -> bool:
    """物料行存的自定义规则与 custom 格式相同（不看起始号、上限自动突破）。"""
    c = json.loads(custom_json)
    c.pop("seq_start", None)
    c.pop("seq_grow", None)
    return c == custom


def _custom_dict(spec: codec.Spec) -> dict:
    return {
        "prefix": spec.prefix,
        "base": spec.base,
        "seq_len": spec.seq_len,
        "with_length": spec.with_length,
        "length_pad": spec.length_pad,
        "sep_before": spec.sep_before,
        "sep_after": spec.sep_after,
        "with_unit": spec.with_unit,
        "suffix": spec.suffix,
    }


def pi_default(pi: str) -> Resolved | None:
    """PI 绑定的规则（各物料行的默认）；没绑时 None。"""
    b = db.one("SELECT rule_id FROM sn_pi_rule WHERE pi_no = %s", pi)
    if b is not None:
        r = db.one("SELECT * FROM sn_rule WHERE id = %s", b["rule_id"])
        if r is not None:
            return resolve(r, pi, SRC_PI)
    return None


def effective(
    pi: str,
    sel: dict | None,
    default: Resolved | None = None,
    use_default: bool = True,
    memo: dict[int, Resolved | None] | None = None,
) -> Resolved | None:
    """物料行生效的规则：物料行已选 ＞ PI 绑定；都没有返回 None（须先选规则）。
    memo：同一 PI 的多个物料行选了同一条规则时只解析一次（规则 id → 解析结果）。"""
    if sel is not None:
        if sel.get("custom_json") and sel.get("rule_id") is None:
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
                start = _start_of(sel)
                grow = _grow_of(sel.get("custom_json"))
                # 起始号、上限自动突破只对本 PI 正用的版本有效（memo 共用的解析结果不改，另复制一份）
                if res.plan != PLAN_USE or (start is None and not grow):
                    return res
                return replace(res, seq_start=start, seq_grow=grow)
    if not use_default:
        return None
    return default if default is not None else pi_default(pi)


def selections(keys: list[tuple[str, int]]) -> dict[tuple[str, int], dict]:
    if not keys:
        return {}
    bills = sorted({k[0] for k in keys})
    return {
        (r["bill_no"], r["seq"]): r
        for r in db.all(f"SELECT * FROM sn_line_rule WHERE bill_no IN ({marks(len(bills))})", *bills)
    }


def info(res: Resolved | None, length: str | None = None, start_seq: int = 1, unit: str | None = None) -> dict | None:
    """规则说明：来源、选法、规则、本 PI 用的版本、格式、第一个号（按本行米数、单位）。"""
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
        "seq_start": res.seq_start,
        "seq_grow": res.seq_grow,
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
        out["length_pad"] = s.length_pad
        out["sep_before"] = s.sep_before
        out["sep_after"] = s.sep_after
        out["with_unit"] = s.with_unit
        out["suffix"] = s.suffix
        # 上限自动突破时为加到 12 位的最大流水
        out["max_seq"] = codec.max_seq_grow(s) if res.seq_grow else codec.max_seq(s)
        if res.seq_start is not None:
            # 自定义规则的起始号：从最大号之后编号时第一个号不小于它
            start_seq = max(start_seq, res.seq_start)
        if (length is not None or not s.with_length) and 0 < start_seq <= out["max_seq"]:
            w = codec.widen(s, codec.seq_width(start_seq, s)) if res.seq_grow else s
            out["first_sn"] = codec.format_sn(start_seq, w, length, unit)
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
    default = pi_default(pi)
    memo: dict[int, Resolved | None] = {}
    return {k: info(effective(pi, sels.get(k), default, memo=memo)) for k in keys}


# ================================================================== 选取 / 绑定


def _derive(cu: CurrentUser, rule: dict, pi: str, new_prefix: str | None) -> dict:
    """为本 PI 换前缀：按当前版本的格式另出属于本 PI 的版本。"""
    t = current_version(rule)
    p = util.trim(new_prefix)
    spec = replace(spec_of(t), prefix=p)
    err = codec.validate(spec)
    if err is not None:
        raise biz(err)
    check_prefix(p, spec=spec, pi=pi)
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
                other_pi=res.other_pi,
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
        cu.check_write(*{line["org_code"] for _, _, line in keys})
        own = None
        if custom is not None:
            res = _custom_resolved(SRC_LINE, custom)
            own = own_version(pi, res.spec())
        if own is not None:
            # 前缀已被本 PI 同格式的版本占用（本 PI 别的单据先生成过）：物料行改记为那条规则，沿用这一版；
            # 填了起始号时另记在物料行（custom_json 只有 seq_start），下次生成从 max(PI 下一个号, 起始号) 起
            _adopt(cu, own.rule, pi, {line["org_code"] for _, _, line in keys})
            start, grow = res.seq_start, res.seq_grow
            res = replace(own, seq_start=start, seq_grow=grow)
            rid, cj = own.rule["id"], _start_json(start, grow)
            label = (
                f"{own.rule['rule_code']} v{own.version['version']}"
                + ("" if start is None else f" start={seq_label(start, res.spec(), grow)}")
                + (" grow" if grow else "")
            )
        elif custom is not None:
            check_prefix(res.version["prefix"], spec=res.spec(), pi=pi)
            rid, cj = None, _custom_json(res)
            label = (
                f"custom {format_text(res.spec())}"
                + ("" if res.seq_start is None else f" start={seq_label(res.seq_start, res.spec(), res.seq_grow)}")
                + (" grow" if res.seq_grow else "")
            )
        else:
            rule = get_rule(rule_id)
            check_visible(cu, rule)
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
        cu.check_write(*{line["org_code"] for _, _, line in keys})
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


def _check_pi_scope(cu: CurrentUser, pi: str) -> None:
    """PI 级的默认规则影响这张 PI 的全部单据：普通操作员须对这张 PI 的全部单据的生产组织都有授权。"""
    if cu.is_admin:
        return
    orgs = set(db.column("SELECT DISTINCT org_code FROM sn_prd_mo WHERE pi = %s", pi))
    cu.check_write(*(orgs or {"-"}))


def bind_pi(cu: CurrentUser, pi_in: str | None, rule_id: int | None, new_prefix: str | None = None) -> dict:
    """给 PI 绑定规则作各物料行的默认（单行另选优先）；版本已归属别的 PI 时须填新前缀。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    _check_pi_scope(cu, pi)
    with db.tx():
        rule = get_rule(rule_id)
        check_visible(cu, rule)
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


def unbind_pi(cu: CurrentUser, pi_in: str | None) -> dict:
    """解除 PI 绑定：规则转为通用模板，已选过规则的物料行不受影响。"""
    pi = util.trim(pi_in)
    _check_pi_scope(cu, pi)
    with db.tx():
        b = db.one("SELECT * FROM sn_pi_rule WHERE pi_no = %s FOR UPDATE", pi)
        if b is not None:
            db.exec("DELETE FROM sn_pi_rule WHERE pi_no = %s", pi)
            r = db.one("SELECT rule_code FROM sn_rule WHERE id = %s", b["rule_id"])
            audit.record(cu, audit.entry(audit.RULE_PI_UNBIND).pi(pi).info(r["rule_code"] if r else str(b["rule_id"])))
    return {"pi_no": pi}


def options(pi: str | None, customer: str | None, facs: tuple[str, ...] | None = None) -> list[dict]:
    """生成页规则库选项：分类与本 PI 或本 PI 客户相同的标「推荐」排前面（分类只用于筛选和推荐，不是绑定）；
    给出本 PI 用这条规则时的版本与是否须换前缀。
    facs = 授权范围（None = 全部）：只列全局规则和范围内组织的规则。"""
    out = []
    versions = _versions_by_rule()
    vsql, vargs = _visible_sql(facs)
    for r in db.all("SELECT * FROM sn_rule WHERE 1=1" + vsql + " ORDER BY rule_code", *vargs):
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
            "factory_code": r["factory_code"],
            "recommended": bool(recommended),
            "format": None if t is None else format_text(spec_of(t)),
            # 本 PI 用这条规则时的版本号（须换前缀时为另出前的当前版本）
            "version": (res.version["version"] if res is not None and res.version is not None else None)
            or (None if t is None else t["version"]),
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
        err = codec.validate(spec)
        if err is not None:
            raise biz(err)
        check_prefix(spec.prefix, spec=spec, pi=pi, gen=True)
        code, name = _custom_names(pi)
        from app.services import orders

        # 物料行改记为这条规则：本次生成的行，以及本 PI 选了同一自定义规则、这次没生成的行（可能在别的组织；
        # 之后接着用这一版，不再按自定义新建——前缀已被这一版占用）
        # 格式相同即同一条规则，起始号不同也算（起始号只管首次生成，之后流水接续）
        same = list(
            dict.fromkeys(
                [*lines]
                + [
                    (r["bill_no"], r["seq"])
                    for r in db.all(
                        "SELECT bill_no, seq, custom_json FROM sn_line_rule WHERE pi_no = %s "
                        "AND custom_json IS NOT NULL",
                        pi,
                    )
                    if _same_custom(r["custom_json"], res.custom)
                ]
            )
        )
        # 自定义规则归属这些物料行所在的生产组织（都在同一个组织时；否则为全局规则，各组织都能看到）
        orgs = orders.bill_orgs(no for no, _ in same) - {"-"}
        owner = next(iter(orgs)) if len(orgs) == 1 else None
        rid = _insert_rule(
            cu, code, name, spec, (PI, pi), " (自定义规则，生成成功后存入规则库)", claimed_pi=pi, factory=owner
        )
        for no, seq in same:
            # 改记为这条规则；上限自动突破记在各物料行上，照旧保留
            keep_grow(no, seq, rid)
        return db.one("SELECT * FROM sn_rule_version WHERE rule_id = %s AND version = 1", rid)
    if res.version is None:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    if res.version["claimed_pi"] is None:
        # 首次归属本 PI：前缀不能与别的 PI 的前缀相同或一个是另一个的开头
        check_prefix(res.version["prefix"], res.version["id"], spec_of(res.version), pi, gen=True)
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
    # 0 = SN 导入的号（没有规则）：不查库
    if not version_id:
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
