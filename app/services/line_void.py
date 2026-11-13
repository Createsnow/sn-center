"""物料行失效（SN 生成页详情的「失效」条件，全系统一套）。

条件 = 开关 + 业务状态（金蝶明细 FStatus）+ 领料状态（FPickMtrlStatus），存在 sn_line_void_rule（只有 id = 1 一行；
没有时按默认：开启，完工 / 结案 / 结算 + 未领料）。开启时，业务状态、领料状态都在所选范围内的物料行整行失效：

- 不参与排号：生成时额度按 0 算（generate.Line.void），第一次生成排号段预留时不给它留号段（reserve.build）；
- 不参与计数：生成页 PI 列表、详情、分配页的订单数与可生成都不算它（已生成、已分配按现有的号照算）；
- 不能选规则、改回默认规则、撤销配置、填写附件项次 / 米数 / 产品单位，不能作转移目标、SN 导入的目标（LINE_VOID）；
- 已有的号照常分配、推送、拉取、删号、转移（同一行内转组织不算换行），已有的号段预留不动。

业务状态、领料状态还没取到（空）的物料行不会失效。关闭开关即全部生效。系统管理员、普通操作员都能改，改动留痕。
状态码只能是下面两张表里的值（写进 SQL 前已校验，不拼接外来文本）。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit

#: 业务状态（金蝶 PRD_MO 明细 Status）
BIZ = {"1": "计划", "2": "计划确认", "3": "下达", "4": "开工", "5": "完工", "6": "结案", "7": "结算"}
#: 领料状态（金蝶 PRD_MO 明细 PickMtrlStatus）
PICK = {"1": "未领料", "2": "部分领料", "3": "全部领料", "4": "超额领料"}
DEFAULT_BIZ = ("5", "6", "7")
DEFAULT_PICK = ("1",)


@dataclass(frozen=True)
class Rule:
    enabled: bool
    biz: tuple[str, ...]
    pick: tuple[str, ...]
    updated_by: str = ""
    updated_at: object = None

    def matches(self, biz_status: str | None, pick_status: str | None) -> bool:
        b, p = (biz_status or "").strip(), (pick_status or "").strip()
        return self.enabled and bool(b) and bool(p) and b in self.biz and p in self.pick

    def is_void(self, row: dict) -> bool:
        """快照的一行（sn_prd_mo）是否失效。"""
        return self.matches(row.get("biz_status"), row.get("pick_status"))

    def sql(self, alias: str = "m") -> str:
        """失效条件的 SQL 表达式（alias = sn_prd_mo 的别名）；关闭时为 0。状态码已校验，只含数字。"""
        if not self.enabled:
            return "0"
        b = ",".join(f"'{x}'" for x in self.biz)
        p = ",".join(f"'{x}'" for x in self.pick)
        return f"({alias}.biz_status IN ({b}) AND {alias}.pick_status IN ({p}))"


DEFAULT = Rule(True, DEFAULT_BIZ, DEFAULT_PICK)


def _codes(values, allowed: dict[str, str]) -> tuple[str, ...] | None:
    """去重、按状态码排序；有不认识的码或为空时返回 None。"""
    out = {util.trim(str(v)) for v in values or [] if not util.blank(str(v))}
    if not out or any(v not in allowed for v in out):
        return None
    return tuple(sorted(out, key=int))


def current() -> Rule:
    r = db.one("SELECT * FROM sn_line_void_rule WHERE id = 1")
    if r is None:
        return DEFAULT
    biz_ = _codes(r["biz_status"].split(","), BIZ) or DEFAULT_BIZ
    pick = _codes(r["pick_status"].split(","), PICK) or DEFAULT_PICK
    return Rule(bool(r["enabled"]), biz_, pick, r["updated_by"], r["updated_at"])


def view(rule: Rule | None = None) -> dict:
    r = rule or current()
    return {
        "enabled": r.enabled,
        "biz_status": list(r.biz),
        "pick_status": list(r.pick),
        "updated_by": r.updated_by or None,
        "updated_at": util.fmt(r.updated_at) if r.updated_at is not None else None,
    }


def save(cu: CurrentUser, enabled: bool | None, biz_in: list[str] | None, pick_in: list[str] | None) -> dict:
    """改失效条件（全系统生效）：业务状态、领料状态各至少选一项。"""
    biz_ = _codes(biz_in, BIZ)
    pick = _codes(pick_in, PICK)
    if biz_ is None or pick is None or enabled is None:
        raise biz(ErrorCode.LINE_VOID_RULE_INVALID)
    old = current()
    new = Rule(bool(enabled), biz_, pick)
    if (old.enabled, old.biz, old.pick) == (new.enabled, new.biz, new.pick):
        return view(old)
    now = util.now()
    with db.tx():
        db.exec(
            "INSERT INTO sn_line_void_rule(id, enabled, biz_status, pick_status, updated_by, updated_at) "
            "VALUES (1,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), "
            "biz_status = VALUES(biz_status), pick_status = VALUES(pick_status), updated_by = VALUES(updated_by), "
            "updated_at = VALUES(updated_at)",
            1 if new.enabled else 0,
            ",".join(new.biz),
            ",".join(new.pick),
            cu.emp_no,
            now,
        )
        audit.record(cu, audit.entry(audit.LINE_VOID_RULE).info(f"{_text(old)} -> {_text(new)}"))
    return view(Rule(new.enabled, new.biz, new.pick, cu.emp_no, now))


def _text(r: Rule) -> str:
    return f"{'on' if r.enabled else 'off'} biz={','.join(r.biz)} pick={','.join(r.pick)}"


def void_keys(keys: list[tuple[str, int]], rule: Rule | None = None) -> set[tuple[str, int]]:
    """这些物料行中失效的 (单据, 序号)。"""
    r = rule or current()
    if not keys or not r.enabled:
        return set()
    bills = sorted({k[0] for k in keys})
    want = set(keys)
    out = set()
    for i in range(0, len(bills), 500):
        part = bills[i : i + 500]
        for row in db.all(
            f"SELECT bill_no, seq, biz_status, pick_status FROM sn_prd_mo WHERE bill_no IN ({marks(len(part))})",
            *part,
        ):
            k = (row["bill_no"], row["seq"])
            if k in want and r.is_void(row):
                out.add(k)
    return out


def check_not_void(keys: list[tuple[str, int]], rule: Rule | None = None) -> None:
    """有失效的物料行时拒绝（选规则、撤销配置、填写等），报第一条。"""
    bad = void_keys(keys, rule)
    if bad:
        no, seq = sorted(bad)[0]
        raise biz(ErrorCode.LINE_VOID, bill_no=no, seq=seq)
