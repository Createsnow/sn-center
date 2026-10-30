"""转单（F-15）、转组织（F-16）与转移后的 MES 处理（5.2.8）。都不改 SN 字符串，都只在本系统改挂。

转移时每枚号：清掉原推送、拉取批次，rev + 1；
- 仍在同一生产组织：MES 持有组织为本组织的号改为待推送、推送类型改挂，随即自动推送；其余状态不变。
- 转到别的生产组织：转入方对接 MES 时改为待推送，MES 持有组织为来源组织的记改挂、其余记新增；
  转入方未对接时改为已分配。
- 号的 MES 持有组织为来源组织、且转入组织与来源不同时，在同一事务内向来源 MES 建立「转出」批次并推送。
- 转移前 MES 已持有、改挂后待推送的号不回号池：同一事务内给转入方当场建批（记转移单号，批次类型为
  SAME_ORG_TRANSFER / TRANSFER_IN，按单批枚数拆分，暂停交付的号除外）并推送；MES 还没收到的号回号池按流水认领
  （批次类型 NEW）。
只有分配成功（已分到生产组织）的号才能转：待分配的号不能转单、转组织。
不可转：待分配；所在批次未确认或失败；所在转出批次未确认或失败（先在原转组织单上继续推送转出）。

转单、转组织都直接执行（没有申请、审批），原因必填：系统管理员不限组织；普通操作员的来源须在授权范围内
（号现在所在的组织；指定了来源生产组织时即该组织），目标生产组织不限。

页面不区分转单、转组织（统一入口 preview_auto / transfer）：系统按 decide_kind 判断，目标单据与来源不同、
且目标生产组织就是目标单据的生产组织 → 转单；其余 → 转组织。

转单、转组织的来源与目标规则相同：来源按 PI → 单据筛选，范围三选一（整张单据 / 单据 + 物料编码下全部号 /
单据 + 物料编码下单枚或连续号段）；转组织另限来源生产组织（转单指定了来源生产组织时同样只取该组织的号）。
目标 PI、单据、生产组织、物料编码都必填，号改挂到目标单据的该物料行（客户取目标单据的）。
转单的目标生产组织就是目标单据的生产组织、目标单据不能是来源单据；
转组织的目标生产组织可任选启用的（可与来源相同）。
号换了物料行时已生成数、已分配数随号移动，额度不变。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, delivery, factories, files, locks, orders, rules

KIND_BILL = "BILL"
KIND_ORG = "ORG"
#: 来源候选不按类型区分（页面统一入口）：同转单，按读范围列出
KIND_AUTO = "AUTO"
BILL_TRANSFER = "BILL_TRANSFER"
ORG_TRANSFER = "ORG_TRANSFER"
MODE_VOID = "VOID"
MODE_SPLIT = "SPLIT"
MODE_DIRECT = "DIRECT"
#: 转移单生成即生效（转组织单的 status 只有 APPROVED）
APPROVED = "APPROVED"
SCOPE_BILL = "BILL"
SCOPE_MATERIAL = "MATERIAL"
SCOPE_RANGE = "RANGE"
SCOPES = frozenset((SCOPE_BILL, SCOPE_MATERIAL, SCOPE_RANGE))

ITEM_COLS = (
    "id, sn, factory_code, pi_no, customer_code, bill_no, bill_seq, material_code, rev, seq_pi_no, seq_dec, "
    "length_text, attach_no, rule_version_id, status, delivery_batch_no, mes_holder, push_type, delivery_mode"
)

ORG_FIELDS = (
    "id",
    "transfer_no",
    "mode",
    "status",
    "scope",
    "from_factory",
    "from_pi",
    "from_bill",
    "from_material",
    "from_sn",
    "from_end_sn",
    "qty",
    "to_factory",
    "to_pi",
    "to_customer",
    "to_bill",
    "to_seq",
    "to_material",
    "reason",
    "applied_by",
    "applied_at",
)
BILL_FIELDS = (
    "id",
    "transfer_no",
    "mode",
    "scope",
    "from_bill",
    "to_bill",
    "from_pi",
    "to_pi",
    "to_factory",
    "to_seq",
    "to_material",
    "scope_text",
    "qty",
    "reason",
    "created_by",
    "created_at",
)


def _reason(raw: str | None) -> str:
    r = util.trim(raw)
    if not r or len(r) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    return r


# ================================================================== 可转判断


def blocked(items: list[dict]) -> dict[int, str]:
    """不能转的号及原因：PENDING_ALLOC（只有分配成功的号才能转）/ BATCH_PENDING / TRANSFER_OUT_PENDING。"""
    out: dict[int, str] = {}
    for r in items:
        if r["status"] == util.PENDING_ALLOC:
            out[r["id"]] = "PENDING_ALLOC"
        elif r["status"] == util.TO_PUSH and r["delivery_batch_no"] is not None:
            out[r["id"]] = "BATCH_PENDING"
    for i in delivery.frozen_by_transfer_out([r["id"] for r in items if r["id"] not in out]):
        out[i] = "TRANSFER_OUT_PENDING"
    return out


_REASON_TEXT = {
    "PENDING_ALLOC": "待分配（只有分配成功的号才能转移）",
    "BATCH_PENDING": "所在批次未确认或失败",
    "TRANSFER_OUT_PENDING": "所在转出批次未确认或失败，先在原转组织单上继续推送转出",
}


def _summary(items: list[dict], bad: dict[int, str]) -> dict:
    by_status: dict[str, int] = {}
    by_reason: dict[str, int] = {}
    for r in items:
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1
        if r["id"] in bad:
            by_reason[bad[r["id"]]] = by_reason.get(bad[r["id"]], 0) + 1
    return {
        "total": len(items),
        "transferable": len(items) - len(bad),
        "by_status": by_status,
        "blocked": by_reason,
        "delivered": sum(1 for r in items if r["mes_holder"]),
    }


def _check_any(items: list[dict], ok: list[dict], bad: dict[int, str], src: Source) -> None:
    """范围内一枚可转的都没有：单枚时说明原因；有不可转的列出原因；否则没有号。"""
    if ok:
        return
    if src.scope == SCOPE_RANGE and len(items) == 1:
        raise biz(ErrorCode.TR_SN_NOT_TRANSFERABLE, sn=items[0]["sn"], reason=_REASON_TEXT.get(bad[items[0]["id"]], ""))
    _raise_if_blocked(bad)
    raise biz(ErrorCode.TR_NOTHING)


def _raise_if_blocked(bad: dict[int, str]) -> None:
    if not bad:
        return
    by: dict[str, int] = {}
    for v in bad.values():
        by[v] = by.get(v, 0) + 1
    detail = "；".join(f"{_REASON_TEXT.get(k, k)} {n} 枚" for k, n in by.items())
    raise biz(ErrorCode.TR_FROZEN, count=len(bad), detail=detail)


# ================================================================== 改挂（5.2.8）


@dataclass
class Target:
    """一枚号转移后的挂靠；None 字段 = 不变。"""

    factory: str | None
    pi: str
    customer: str
    bill: str
    seq: int
    material: str


def _move(
    cu: CurrentUser, kind: str, transfer_id: int, doc_no: str, items: list[dict], targets: dict[int, Target]
) -> dict:
    """改挂并按 5.2.8 处理状态与推送；返回 {to_push: {(org, pi)}, out_batches: [...], counts: {...}}。"""
    now = util.now()
    tkind = BILL_TRANSFER if kind == KIND_BILL else ORG_TRANSFER
    mes = factories.mes_codes()
    xo: dict[str, list[dict]] = {}
    to_push: set[tuple[str, str]] = set()
    rows = []
    #: 改挂后各列相同的号一起更新：(组织, PI, 客户, 单据, 序号, 物料, 状态, 推送类型, 交付方式, 原组织) → 号
    groups: dict[tuple, list[int]] = {}
    #: 物料行的已生成数、已分配数随号移动（转的都是已分配的号，两者变化相同）
    line_moves: dict[tuple[str, int], int] = {}
    for r in items:
        t = targets[r["id"]]
        old_org = r["factory_code"]
        new_org = t.factory
        rev = int(r["rev"]) + 1
        status = r["status"]
        push_type = r["push_type"]
        mode = r["delivery_mode"]
        # 只转分配成功的号（blocked 已排除待分配），号上都有生产组织
        if new_org == old_org:
            if r["mes_holder"] == old_org and old_org in mes:
                status, push_type, mode = util.TO_PUSH, util.PUSH_REASSIGN, None
        elif new_org in mes:
            status, mode = util.TO_PUSH, None
            push_type = util.PUSH_REASSIGN if r["mes_holder"] == old_org else util.PUSH_NEW
        else:
            status, push_type, mode = util.ALLOCATED, util.PUSH_NEW, None
        if status == util.TO_PUSH:
            to_push.add((new_org, t.pi))
        if r["mes_holder"] is not None and r["mes_holder"] == old_org and new_org != old_org:
            xo.setdefault(old_org, []).append(
                {
                    "item_id": r["id"],
                    "sn": r["sn"],
                    "rev": rev,
                    "pi_no": r["pi_no"],
                    "bill_no": r["bill_no"],
                    "bill_seq": r["bill_seq"],
                    "material_code": r["material_code"],
                    "customer_code": r["customer_code"],
                    "seq_pi_no": r["seq_pi_no"],
                    "seq_dec": r["seq_dec"],
                    "length_text": r["length_text"],
                    "attach_no": r["attach_no"],
                    "rule_version_id": r["rule_version_id"],
                    "to_factory": new_org,
                    "transfer_kind": tkind,
                    "transfer_doc_no": doc_no,
                }
            )
        # 批次类型只在原组织 MES 已持有时记为转移（transfer_from_org）；MES 还没收到的号保留原标记（没有即首次下发）
        from_org = old_org if r["mes_holder"] == old_org else None
        key = (new_org, t.pi, t.customer, t.bill, t.seq, t.material, status, push_type, mode, from_org)
        groups.setdefault(key, []).append(r["id"])
        if (t.bill, t.seq) != (r["bill_no"], r["bill_seq"]):
            line_moves[(r["bill_no"], r["bill_seq"])] = line_moves.get((r["bill_no"], r["bill_seq"]), 0) - 1
            line_moves[(t.bill, t.seq)] = line_moves.get((t.bill, t.seq), 0) + 1
        rows.append(
            (
                kind,
                transfer_id,
                r["id"],
                r["sn"],
                r["seq_pi_no"],
                r["seq_dec"],
                r["status"],
                old_org,
                r["pi_no"],
                r["customer_code"],
                r["bill_no"],
                r["bill_seq"],
                r["material_code"],
                r["delivery_batch_no"],
                r["rev"],
                status,
                new_org,
                t.pi,
                t.customer,
                t.bill,
                t.seq,
                t.material,
                rev,
            )
        )
    # 号已在本事务内 FOR UPDATE 锁住，rev + 1 与上面按读出值算的 rev 一致
    for (new_org, pi, customer, bill, seq, material, status, push_type, mode, from_org), ids in groups.items():
        for i in range(0, len(ids), 1000):
            part = ids[i : i + 1000]
            db.exec(
                "UPDATE sn_item SET factory_code = %s, pi_no = %s, customer_code = %s, bill_no = %s, bill_seq = %s, "
                "material_code = %s, rev = rev + 1, status = %s, push_type = %s, delivery_mode = %s, "
                "delivery_batch_no = NULL, "
                "transfer_kind = %s, transfer_doc_no = %s, transfer_from_org = COALESCE(%s, transfer_from_org), "
                "updated_at = %s "
                f"WHERE id IN ({marks(len(part))})",
                new_org,
                pi,
                customer,
                bill,
                seq,
                material,
                status,
                push_type,
                mode,
                tkind,
                doc_no,
                from_org,
                now,
                *part,
            )
    db.exec_many(
        "INSERT INTO sn_transfer_item(kind, transfer_id, item_id, sn, seq_pi_no, seq_dec, from_status, from_factory, "
        "from_pi, from_customer, from_bill, from_seq, from_material, from_delivery_batch, from_rev, "
        "to_status, to_factory, to_pi, to_customer, to_bill, to_seq, to_material, to_rev) VALUES "
        "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON DUPLICATE KEY UPDATE to_status = VALUES(to_status), to_factory = VALUES(to_factory), to_pi = "
        "VALUES(to_pi), "
        "to_customer = VALUES(to_customer), to_bill = VALUES(to_bill), to_seq = VALUES(to_seq), "
        "to_material = VALUES(to_material), to_rev = VALUES(to_rev), from_rev = VALUES(from_rev), "
        "from_delivery_batch = VALUES(from_delivery_batch)",
        rows,
    )
    for (no, seq), d in line_moves.items():
        if d:
            db.exec(
                "INSERT INTO sn_line_count(bill_no, seq, material_code, own_generated, gen_count, alloc_count, "
                "updated_at) "
                "VALUES (%s,%s,COALESCE((SELECT material_code FROM sn_prd_mo WHERE bill_no = %s AND seq = %s), "
                "''),0,0,0,%s) "
                "ON DUPLICATE KEY UPDATE bill_no = bill_no",
                no,
                seq,
                no,
                seq,
                now,
            )
            db.exec(
                "UPDATE sn_line_count SET gen_count = gen_count + %s, alloc_count = alloc_count + %s, updated_at = %s "
                "WHERE bill_no = %s AND seq = %s",
                d,
                d,
                now,
                no,
                seq,
            )
    out_batches = []
    for org, entries in xo.items():
        bs = delivery.build_transfer_out(cu, org, entries)
        out_batches += bs
        for b in bs:
            db.exec(
                "UPDATE sn_transfer_item t JOIN sn_delivery_item d ON d.batch_id = %s AND d.item_id = t.item_id "
                "SET t.out_batch_no = %s WHERE t.kind = %s AND t.transfer_id = %s",
                b["id"],
                b["batch_no"],
                kind,
                transfer_id,
            )
    # 先按目标单据重算暂停标记再建批：已入批的号不再暂停，目标单据暂停交付时号须留在号池
    changes.refresh_items({r["bill_no"] for r in items} | {t.bill for t in targets.values()})
    # 转移前 MES 已持有的号当场给转入方建交付批次；MES 还没收到的号回号池按流水认领（对 MES 仍是首次下发）
    held: dict[tuple[str, str], list[int]] = {}
    for (new_org, pi, *_, status, _push, _mode, from_org), ids in groups.items():
        if status == util.TO_PUSH and from_org is not None and new_org in mes:
            held.setdefault((new_org, pi), []).extend(ids)
    in_batches = []
    for (org, pi), ids in sorted(held.items()):
        in_batches += delivery.build_delivery_now(cu, org, pi, ids, (tkind, doc_no))
    return {"to_push": to_push, "out": out_batches, "in": in_batches, "xo_orgs": sorted(xo)}


def _after_move(cu: CurrentUser, moved: dict) -> None:
    for org, pi in sorted(moved["to_push"]):
        delivery.start_task(cu, org, pi)
    for org in moved["xo_orgs"]:
        delivery.start_task(cu, org, delivery.XO_KEY)


# ================================================================== 来源与目标（转单、转组织共用）


@dataclass(frozen=True)
class TransferRequest:
    """来源：PI → 单据 →（物料 →（单枚或连续号段））；目标：PI + 单据 + 生产组织 + 物料，都必填。"""

    #: 来源生产组织（转组织必填；转单可不填，填了只取该组织的号）
    factory_code: str | None = None
    pi_no: str | None = None
    bill_no: str | None = None
    #: BILL 整张单据 / MATERIAL 单据 + 物料 / RANGE 单据 + 物料 + 单枚或连续号段
    scope: str | None = None
    material_code: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    to_pi: str | None = None
    to_bill: str | None = None
    to_factory: str | None = None
    to_material: str | None = None
    #: 目标单据中同一物料有多行时指定序号
    to_seq: int | None = None
    reason: str | None = None


@dataclass(frozen=True)
class Source:
    pi: str
    bill: str
    scope: str
    material: str
    start_sn: str
    end_sn: str

    def text(self, factory: str | None = None) -> str:
        parts = [x for x in (factory, self.pi, self.bill) if x]
        if self.scope != SCOPE_BILL:
            parts.append(self.material or "(空物料)")
        if self.scope == SCOPE_RANGE:
            parts.append(self.start_sn if self.start_sn == self.end_sn else f"{self.start_sn} ~ {self.end_sn}")
        return " / ".join(parts)


@dataclass(frozen=True)
class Dest:
    """目标物料行（快照中）与转入生产组织。"""

    factory: str
    pi: str
    customer: str
    bill: str
    seq: int
    material: str
    length: str


def _source(r: TransferRequest) -> Source:
    pi = util.trim(r.pi_no)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    bill = util.trim(r.bill_no)
    if not bill:
        raise biz(ErrorCode.ORDER_BILL_REQUIRED)
    scope = util.trim(r.scope)
    if scope not in SCOPES:
        raise biz(ErrorCode.TR_SCOPE_INVALID)
    material = start = end = ""
    if scope != SCOPE_BILL:
        if r.material_code is None:
            raise biz(ErrorCode.TR_MATERIAL_REQUIRED)
        material = r.material_code.strip()
    if scope == SCOPE_RANGE:
        start = util.trim(r.start_sn)
        if not start:
            raise biz(ErrorCode.TR_SN_REQUIRED)
        end = util.trim(r.end_sn) or start
    return Source(pi, bill, scope, material, start, end)


def _source_items(src: Source, factory: str | None, lock: bool = False) -> list[dict]:
    cond, args = "pi_no = %s AND bill_no = %s", [src.pi, src.bill]
    if factory is not None:
        cond += " AND factory_code = %s"
        args.append(factory)
    if src.scope != SCOPE_BILL:
        cond += " AND material_code = %s"
        args.append(src.material)
    items = db.all(
        f"SELECT {ITEM_COLS} FROM sn_item WHERE {cond} ORDER BY seq_pi_no, seq_dec" + (" FOR UPDATE" if lock else ""),
        *args,
    )
    if src.scope == SCOPE_RANGE:
        items = _slice(items, src.start_sn, src.end_sn)
    return items


def _slice(items: list[dict], start: str, end: str) -> list[dict]:
    """单枚或连续号段：起止号须都在范围内、属于同一流水归属 PI。"""
    by_sn = {r["sn"]: r for r in items}
    s, e = by_sn.get(start), by_sn.get(end)
    if s is None or e is None or s["seq_pi_no"] != e["seq_pi_no"]:
        raise biz(ErrorCode.TR_RANGE_INVALID, start_sn=start, end_sn=end)
    lo, hi = min(s["seq_dec"], e["seq_dec"]), max(s["seq_dec"], e["seq_dec"])
    return [r for r in items if r["seq_pi_no"] == s["seq_pi_no"] and lo <= r["seq_dec"] <= hi]


def _dest_line(tb: orders.Bill, r: TransferRequest) -> dict:
    mat = None if r.to_material is None else r.to_material.strip()
    if r.to_seq is not None:
        line = next((x for x in tb.lines if x["seq"] == r.to_seq), None)
        if line is None or (mat is not None and line["material_code"] != mat):
            raise biz(ErrorCode.TR_TARGET_LINE_INVALID, seq=r.to_seq)
        return line
    if mat is None:
        raise biz(ErrorCode.TR_TO_MATERIAL_REQUIRED)
    hit = [x for x in tb.lines if x["material_code"] == mat]
    if not hit:
        raise biz(ErrorCode.TR_TARGET_MATERIAL_INVALID, bill_no=tb.bill_no, material=mat or "(空)")
    if len(hit) > 1:
        raise biz(ErrorCode.TR_TARGET_LINE_REQUIRED, bill_no=tb.bill_no, material=mat or "(空)")
    return hit[0]


def _dest(kind: str, r: TransferRequest, source_bill: str) -> Dest:
    """目标 PI、单据、生产组织、物料都必填；单据须在快照中且属于该 PI。
    转单的生产组织就是目标单据的生产组织；转组织可选任一启用的生产组织。"""
    to_pi = util.trim(r.to_pi)
    if not to_pi:
        raise biz(ErrorCode.TR_TO_PI_REQUIRED)
    to_bill = util.trim(r.to_bill)
    if not to_bill:
        raise biz(ErrorCode.TR_TO_BILL_REQUIRED)
    tb = orders.bill(to_bill)
    if tb is None or not tb.pi or (kind == KIND_BILL and tb.bill_no == source_bill):
        raise biz(ErrorCode.TR_TARGET_BILL_INVALID, bill_no=to_bill)
    if tb.pi != to_pi:
        raise biz(ErrorCode.TR_TARGET_PI_MISMATCH, bill_no=to_bill, pi=tb.pi)
    to_factory = util.trim(r.to_factory)
    if not to_factory:
        raise biz(ErrorCode.TR_TO_FACTORY_REQUIRED)
    if kind == KIND_BILL:
        if to_factory != tb.org_code:
            raise biz(ErrorCode.TR_TARGET_ORG_MISMATCH, bill_no=to_bill, factory=tb.org_code)
    else:
        to_factory = factories.must_be_enabled(to_factory)["factory_code"]
    line = _dest_line(tb, r)
    return Dest(
        to_factory, tb.pi, tb.customer_code, tb.bill_no, line["seq"], line["material_code"], orders.length_of(line)
    )


def _targets(items: list[dict], d: Dest) -> dict[int, Target]:
    """号改挂到目标物料行与转入生产组织；目标米数与号上米数不同则拒绝（SN 已含米数）。"""
    out: dict[int, Target] = {}
    for r in items:
        if r["length_text"] is not None and r["length_text"] != d.length:
            raise biz(ErrorCode.TR_LENGTH_MISMATCH, target=d.length or "(空)", current=r["length_text"])
        out[r["id"]] = Target(d.factory, d.pi, d.customer, d.bill, d.seq, d.material)
    return out


def _from_factory(cu: CurrentUser, code: str | None) -> str:
    """转组织的来源生产组织：系统管理员指定；普通操作员只能是授权组织（只授权一个时可不填）。"""
    if cu.is_admin:
        return factories.must_get(code)["factory_code"]
    return cu.write_factory(code)


@dataclass
class Plan:
    factory: str | None
    src: Source
    dest: Dest
    items: list[dict]
    targets: dict[int, Target]


def _plan(cu: CurrentUser, kind: str, r: TransferRequest, lock: bool = False) -> Plan:
    factory = _from_factory(cu, r.factory_code) if kind == KIND_ORG or util.trim(r.factory_code) else None
    src = _source(r)
    d = _dest(kind, r, src.bill)
    items = _source_items(src, factory, lock)
    if not items:
        raise biz(ErrorCode.TR_NOTHING)
    targets = _targets(items, d)
    if kind == KIND_BILL:
        # 转单：号现在所在的组织须在授权范围内；目标单据的组织不限（来源组织在 _from_factory 校验）
        cu.check_write(*{x["factory_code"] for x in items})
    if all(
        (r["factory_code"] or d.factory, r["pi_no"], r["bill_no"], r["bill_seq"]) == (d.factory, d.pi, d.bill, d.seq)
        for r in items
    ):
        raise biz(ErrorCode.TR_SAME_TARGET)
    return Plan(factory, src, d, items, targets)


def decide_kind(r: TransferRequest) -> str:
    """页面不区分转单、转组织，按目标判断：目标单据与来源单据不同、且目标生产组织就是目标单据的生产组织 → 转单；
    其余（换了生产组织，或同一单据内换组织）→ 转组织。目标单据不在快照时按转组织，由 _dest 报错。"""
    tb = orders.bill(util.trim(r.to_bill)) if util.trim(r.to_bill) else None
    if tb is not None and tb.bill_no != util.trim(r.bill_no) and util.trim(r.to_factory) == tb.org_code:
        return KIND_BILL
    return KIND_ORG


def sources(cu: CurrentUser, kind: str, factory: str | None, pi: str | None) -> dict:
    """来源候选：PI 下各单据、各物料分配成功的号数（待分配的号不能转；转组织限来源生产组织），供逐级筛选。
    rows 按单据 + 物料 + 生产组织细分，带号上的客户、附件项次、米数（页面据此带出来源生产组织与来源信息）。"""
    p = util.trim(pi)
    if not p:
        raise biz(ErrorCode.PI_REQUIRED)
    cond, args = f"pi_no = %s AND status <> '{util.PENDING_ALLOC}'", [p]
    if kind == KIND_ORG:
        cond += " AND factory_code = %s"
        args.append(_from_factory(cu, factory))
    else:
        fsql, fargs = factories.scope_sql(cu.read_factories(None))
        cond += fsql
        args += fargs
    bills: dict[str, dict] = {}
    mats: dict[tuple[str, str], dict] = {}
    rows: dict[tuple[str, str, str], dict] = {}
    for x in db.all(
        "SELECT bill_no, material_code, factory_code, customer_code, attach_no, length_text, COUNT(*) n "
        f"FROM sn_item WHERE {cond} "
        "GROUP BY bill_no, material_code, factory_code, customer_code, attach_no, length_text "
        "ORDER BY bill_no, material_code, factory_code",
        *args,
    ):
        n = int(x["n"])
        b = bills.setdefault(x["bill_no"], {"bill_no": x["bill_no"], "qty": 0, "materials": [], "rows": []})
        b["qty"] += n
        mm = mats.get((x["bill_no"], x["material_code"]))
        if mm is None:
            mm = mats[(x["bill_no"], x["material_code"])] = {"material_code": x["material_code"], "qty": 0}
            b["materials"].append(mm)
        mm["qty"] += n
        rk = (x["bill_no"], x["material_code"], x["factory_code"])
        row = rows.get(rk)
        if row is None:
            row = rows[rk] = {
                "material_code": x["material_code"],
                "factory_code": x["factory_code"],
                "qty": 0,
                "customers": [],
                "attach_nos": [],
                "lengths": [],
            }
            b["rows"].append(row)
        row["qty"] += n
        for k, v in (("customers", x["customer_code"]), ("attach_nos", x["attach_no"]), ("lengths", x["length_text"])):
            if v is not None and v not in row[k]:
                row[k].append(v)
    return {"bills": list(bills.values())}


def locate(cu: CurrentUser, q: str | None) -> dict:
    """快速定位：输入完整 SN、单据编号或 PI，带出来源（只在读范围内、分配成功的号中找）。
    SN → PI、单据、物料编码、生产组织、起号；单据编号 → PI、单据；PI → PI。"""
    text = util.trim(q)
    if not text:
        raise biz(ErrorCode.TR_LOCATE_NOT_FOUND, q="")
    fsql, fargs = factories.scope_sql(cu.read_factories(None))
    r = db.one(
        f"SELECT sn, pi_no, bill_no, material_code, factory_code, status FROM sn_item WHERE sn = %s{fsql}",
        text,
        *fargs,
    )
    if r is not None:
        if r["status"] == util.PENDING_ALLOC:
            raise biz(ErrorCode.TR_SN_NOT_TRANSFERABLE, sn=r["sn"], reason=_REASON_TEXT["PENDING_ALLOC"])
        return {
            "by": "SN",
            "pi": r["pi_no"],
            "bill_no": r["bill_no"],
            "material_code": r["material_code"],
            "factory_code": r["factory_code"],
            "sn": r["sn"],
        }
    alloc = f" AND status <> '{util.PENDING_ALLOC}'"
    r = db.one(f"SELECT pi_no FROM sn_item WHERE bill_no = %s{alloc}{fsql} LIMIT 1", text, *fargs)
    if r is not None:
        return {"by": "BILL", "pi": r["pi_no"], "bill_no": text}
    if db.one(f"SELECT 1 FROM sn_item WHERE pi_no = %s{alloc}{fsql} LIMIT 1", text, *fargs) is not None:
        return {"by": "PI", "pi": text}
    raise biz(ErrorCode.TR_LOCATE_NOT_FOUND, q=text)


def preview(cu: CurrentUser, kind: str, r: TransferRequest) -> dict:
    """确认框：枚数、各状态枚数、不可转枚数及原因、来源和目标已生成数的变化（未生成额度不变）、
    跨生产组织枚数、将向来源 MES 推送转出的枚数。"""
    p = _plan(cu, kind, r)
    bad = blocked(p.items)
    out = _summary(p.items, bad)
    moves: dict[tuple[str, int], int] = {}
    ok = [x for x in p.items if x["id"] not in bad]
    for x in ok:
        t = p.targets[x["id"]]
        if (t.bill, t.seq) != (x["bill_no"], x["bill_seq"]):
            moves[(x["bill_no"], x["bill_seq"])] = moves.get((x["bill_no"], x["bill_seq"]), 0) - 1
            moves[(t.bill, t.seq)] = moves.get((t.bill, t.seq), 0) + 1
    counts = orders.line_counts(list(moves))
    out["generated_changes"] = [
        {
            "bill_no": k[0],
            "seq": k[1],
            "before": 0 if counts.get(k) is None else int(counts[k]["gen_count"]),
            "after": (0 if counts.get(k) is None else int(counts[k]["gen_count"])) + v,
        }
        for k, v in sorted(moves.items())
    ]
    d = p.dest
    out.update(
        to_pi=d.pi,
        to_org=d.factory,
        to_bill=d.bill,
        to_seq=d.seq,
        to_material=d.material,
        to_customer=d.customer,
        scope=p.src.text(p.factory),
        other_org=sum(1 for x in p.items if x["factory_code"] is not None and x["factory_code"] != d.factory),
        xo_qty=sum(1 for x in ok if x["mes_holder"] is not None and x["mes_holder"] == x["factory_code"] != d.factory),
    )
    return out


def preview_auto(cu: CurrentUser, r: TransferRequest) -> dict:
    """统一入口的确认框数据：同 preview，另带系统判断的类型 kind（BILL 转单 / ORG 转组织）。"""
    kind = decide_kind(r)
    return {**preview(cu, kind, r), "kind": kind}


def transfer(cu: CurrentUser, r: TransferRequest) -> dict:
    """统一入口：按 decide_kind 执行转单或转组织；返回 {kind, doc}（doc 为转单单或转组织单）。"""
    kind = decide_kind(r)
    doc = transfer_bill(cu, r) if kind == KIND_BILL else direct(cu, r)
    return {"kind": kind, "doc": doc}


# ================================================================== 转单


def transfer_bill(cu: CurrentUser, req: TransferRequest) -> dict:
    """转单（直接执行，原因必填；普通操作员的来源须在授权范围内，目标不限）：生成转单单；需要时自动改挂推送、转出推送。"""
    reason = _reason(req.reason)
    p0 = _plan(cu, KIND_BILL, req)
    with locks.with_pi_lock(p0.src.pi, p0.dest.pi), db.tx():
        p = _plan(cu, KIND_BILL, req, lock=True)
        bad = blocked(p.items)
        ok = [x for x in p.items if x["id"] not in bad]
        _check_any(p.items, ok, bad, p.src)
        src, d = p.src, p.dest
        scope = src.text()
        no = util.next_id("TB")
        tid = db.insert(
            "INSERT INTO sn_bill_transfer(transfer_no, mode, scope, from_bill, to_bill, from_pi, to_pi, to_factory, "
            "to_seq, to_material, scope_text, qty, reason, created_by, created_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            no,
            MODE_VOID if src.scope == SCOPE_BILL else MODE_SPLIT,
            src.scope,
            src.bill,
            d.bill,
            src.pi,
            d.pi,
            d.factory,
            d.seq,
            d.material,
            util.cut(scope, 500),
            len(ok),
            reason,
            cu.emp_no,
            util.now(),
        )
        moved = _move(cu, KIND_BILL, tid, no, ok, p.targets)
        audit.record(
            cu,
            audit.entry(audit.TRANSFER_BILL)
            .factory(ok[0]["factory_code"])
            .pi(src.pi)
            .bill(src.bill)
            .count(len(ok))
            .range(ok[0]["sn"], ok[-1]["sn"])
            .transfer(no)
            .why(reason)
            .info(
                f"to={d.bill}/{d.seq}/{d.material}/{d.pi}/{d.factory} scope={scope} out_batches="
                + ",".join(b["batch_no"] for b in moved["out"])
                + " in_batches="
                + ",".join(b["batch_no"] for b in moved["in"])
            ),
        )
    _after_move(cu, moved)
    return bill_transfer_view(cu, tid)


# ================================================================== 转组织


def _dest_row(d: Dest) -> dict:
    return {
        "to_factory": d.factory,
        "to_pi": d.pi,
        "to_bill": d.bill,
        "to_seq": d.seq,
        "to_material": d.material,
        "to_customer": d.customer,
    }


def _insert_org(cu, p: Plan, qty: int, reason: str) -> tuple[int, str]:
    """转组织单：直接执行，生成即生效（DIRECT / APPROVED）。"""
    no = util.next_id("TO")
    src, d = p.src, p.dest
    tid = db.insert(
        "INSERT INTO sn_transfer(transfer_no, mode, status, scope, from_factory, from_pi, from_bill, from_material, "
        "from_sn, from_end_sn, qty, to_factory, to_pi, to_customer, to_bill, to_seq, to_material, reason, "
        "applied_by, applied_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        no,
        MODE_DIRECT,
        APPROVED,
        src.scope,
        p.factory,
        src.pi,
        src.bill,
        src.material,
        src.start_sn,
        src.end_sn,
        qty,
        d.factory,
        d.pi,
        d.customer,
        d.bill,
        d.seq,
        d.material,
        reason,
        cu.emp_no,
        util.now(),
    )
    return tid, no


def _org_entry(
    action: str, no: str, factory: str, pi: str, scope: str, t: dict, qty: int, reason: str | None, items: list[dict]
) -> audit.Entry:
    return (
        audit.entry(action)
        .factory(factory)
        .pi(pi)
        .count(qty)
        .transfer(no)
        .why(reason)
        .range(items[0]["sn"] if items else None, items[-1]["sn"] if items else None)
        .info(
            f"scope={scope} to={t['to_factory']}/{t['to_pi']}/{t.get('to_bill') or '-'}/"
            f"{t.get('to_seq') or '-'}/{t['to_material'] or '-'}"
        )
    )


def direct(cu: CurrentUser, r: TransferRequest) -> dict:
    """转组织（直接执行，原因必填）：普通操作员的来源生产组织须在授权范围内，目标生产组织不限（任一启用的）。"""
    reason = _reason(r.reason)
    p0 = _plan(cu, KIND_ORG, r)
    with locks.with_pi_lock(p0.src.pi, p0.dest.pi), db.tx():
        p = _plan(cu, KIND_ORG, r, lock=True)
        bad = blocked(p.items)
        ok = [x for x in p.items if x["id"] not in bad]
        _check_any(p.items, ok, bad, p.src)
        tid, no = _insert_org(cu, p, len(ok), reason)
        moved = _move(cu, KIND_ORG, tid, no, ok, p.targets)
        e = _org_entry(
            audit.TRANSFER_DIRECT, no, p.factory, p.src.pi, p.src.text(), _dest_row(p.dest), len(ok), reason, ok
        )
        out_nos, in_nos = (",".join(b["batch_no"] for b in moved[k]) for k in ("out", "in"))
        audit.record(cu, e.info(f"{e.detail} out_batches={out_nos} in_batches={in_nos}"))
    _after_move(cu, moved)
    return org_view(cu, tid)


# ================================================================== 物料确认对应（F-06，订单变更修正，不是转单）


def map_material(cu: CurrentUser, alert: dict, target_seq: int | None, note: str | None) -> None:
    """把失去对应行的已生成数、本单生成数并入指定新行，号改挂到新行；已交付给 MES 的号 rev + 1 以改挂重推
    （同一事务内当场建 REMAP 批次）。涉及的号在未确认或失败的批次中时须等批次确认后再做。"""
    bill = alert["bill_no"]
    old = alert["seq"]
    line = (
        None
        if target_seq is None
        else db.one("SELECT * FROM sn_prd_mo WHERE bill_no = %s AND seq = %s", bill, target_seq)
    )
    if line is None or target_seq == old:
        raise biz(ErrorCode.ALERT_MAP_TARGET_INVALID, seq=target_seq if target_seq is not None else "")
    with locks.with_pi_lock(line["pi"]), db.tx():
        a = db.one("SELECT * FROM sn_order_alert WHERE id = %s FOR UPDATE", alert["id"])
        if a is None or a["status"] != "OPEN":
            raise biz(ErrorCode.ALERT_NOT_FOUND)
        items = db.all(f"SELECT {ITEM_COLS} FROM sn_item WHERE bill_no = %s AND bill_seq = %s FOR UPDATE", bill, old)
        frozen = sum(1 for x in items if x["status"] == util.TO_PUSH and x["delivery_batch_no"] is not None)
        frozen += len(delivery.frozen_by_transfer_out([x["id"] for x in items]))
        if frozen:
            raise biz(ErrorCode.ALERT_MAP_FROZEN, count=frozen)
        mes = factories.mes_codes()
        now = util.now()
        pushes: set[tuple[str, str]] = set()
        # 已交付给 MES 的号 rev + 1，其中 MES 持有组织即本组织（且对接 MES）的改为待推送改挂
        # （批次类型 REMAP，清除转移标记）；其余只改挂
        extra = {
            "REASSIGN": ", rev = rev + 1, status = 'TO_PUSH', push_type = 'REASSIGN', delivery_batch_no = NULL, "
            "transfer_kind = NULL, transfer_doc_no = NULL, transfer_from_org = NULL",
            "DELIVERED": ", rev = rev + 1",
            "": "",
        }
        groups: dict[str, list[int]] = {k: [] for k in extra}
        for x in items:
            if x["mes_holder"] is None:
                groups[""].append(x["id"])
            elif x["mes_holder"] == x["factory_code"] and x["factory_code"] in mes:
                groups["REASSIGN"].append(x["id"])
                pushes.add((x["factory_code"], x["pi_no"]))
            else:
                groups["DELIVERED"].append(x["id"])
        reassign = set(groups["REASSIGN"])
        for k, ids in groups.items():
            for i in range(0, len(ids), 1000):
                part = ids[i : i + 1000]
                db.exec(
                    f"UPDATE sn_item SET bill_seq = %s, material_code = %s{extra[k]}, updated_at = %s "
                    f"WHERE id IN ({marks(len(part))})",
                    target_seq,
                    line["material_code"],
                    now,
                    *part,
                )
        oc = db.one("SELECT * FROM sn_line_count WHERE bill_no = %s AND seq = %s FOR UPDATE", bill, old)
        if oc is not None:
            db.exec(
                "INSERT INTO sn_line_count(bill_no, seq, material_code, own_generated, gen_count, alloc_count, "
                "updated_at) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE material_code = VALUES(material_code), "
                "own_generated = own_generated + VALUES(own_generated), gen_count = gen_count + VALUES(gen_count), "
                "alloc_count = alloc_count + VALUES(alloc_count), updated_at = VALUES(updated_at)",
                bill,
                target_seq,
                line["material_code"],
                oc["own_generated"],
                oc["gen_count"],
                oc["alloc_count"],
                now,
            )
            db.exec("DELETE FROM sn_line_count WHERE bill_no = %s AND seq = %s", bill, old)
        rule = db.one("SELECT * FROM sn_line_rule WHERE bill_no = %s AND seq = %s", bill, old)
        if rule is not None and not db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_line_rule WHERE bill_no = %s AND seq = %s)", bill, target_seq
        ):
            db.exec("UPDATE sn_line_rule SET seq = %s WHERE bill_no = %s AND seq = %s", target_seq, bill, old)
        audit.record(
            cu,
            audit.entry(audit.ORDER_ALERT_HANDLE)
            .bill(bill)
            .pi(line["pi"])
            .factory(line["org_code"])
            .count(len(items))
            .why(note)
            .info(
                f"alert={alert['id']} type=MATERIAL action=MAP seq {old} -> {target_seq} "
                f"material={line['material_code']}"
            ),
        )
        changes.close_material_alert(cu, alert["id"], note)
        changes.refresh_items([bill])
        # 关闭提醒、重算暂停后，MES 已持有的号当场建改挂（REMAP）批次；仍暂停交付的号留在号池
        held: dict[tuple[str, str], list[int]] = {}
        for x in items:
            if x["id"] in reassign:
                held.setdefault((x["factory_code"], x["pi_no"]), []).append(x["id"])
        for (org, pi), ids in sorted(held.items()):
            delivery.build_delivery_now(cu, org, pi, ids)
    changes.kick_resumed()
    for org, pi in sorted(pushes):
        delivery.start_task(cu, org, pi)


# ================================================================== 查询（F-17）


def _direction(cu: CurrentUser, from_f: str | None) -> str:
    if not cu.bound:
        return ""
    return "OUT" if from_f in cu.factories else "IN"


def _org_where(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None) -> tuple[str, list]:
    where = " WHERE 1=1"
    args: list = []
    if not util.blank(pi):
        where += " AND (from_pi = %s OR to_pi = %s)"
        args += [pi.strip(), pi.strip()]
    if not util.blank(bill):
        where += (
            " AND (from_bill = %s OR to_bill = %s OR id IN "
            "(SELECT transfer_id FROM sn_transfer_item WHERE kind = 'ORG' AND from_bill = %s))"
        )
        args += [bill.strip()] * 3
    facs = cu.read_factories(factory)
    if facs is not None:
        fr, fa = factories.scope_sql(facs, "from_factory")
        to, ta = factories.scope_sql(facs, "to_factory")
        where += f" AND ((1=1{fr}) OR (1=1{to}))"
        args += [*fa, *ta]
    return where, args


def _bill_where(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None) -> tuple[str, list]:
    where = " WHERE 1=1"
    args: list = []
    if not util.blank(pi):
        where += " AND (from_pi = %s OR to_pi = %s)"
        args += [pi.strip(), pi.strip()]
    if not util.blank(bill):
        where += " AND (from_bill = %s OR to_bill = %s)"
        args += [bill.strip(), bill.strip()]
    facs = cu.read_factories(factory)
    if facs is not None:
        # 号的来源或去向组织在范围内，或来源单据、目标单据的组织在范围内（含早期只转了待分配号、号上没有组织的单）
        fsql, fa = factories.scope_sql(facs, "x.from_factory")
        tsql, ta = factories.scope_sql(facs, "x.to_factory")
        bsql, ba = factories.scope_sql(facs, "to_factory")
        lsql, la = factories.scope_sql(facs, "l.org_code")
        where += (
            " AND (id IN (SELECT x.transfer_id FROM sn_transfer_item x WHERE x.kind = 'BILL' AND "
            f"((1=1{fsql}) OR (1=1{tsql}))) OR (1=1{bsql}) OR from_bill IN "
            f"(SELECT l.bill_no FROM sn_bill_ledger l WHERE 1=1{lsql}))"
        )
        args += [*fa, *ta, *ba, *la]
    return where, args


def list_org(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None, page: util.Page) -> dict:
    where, args = _org_where(cu, factory, pi, bill)
    total = db.count("SELECT COUNT(*) FROM sn_transfer" + where, *args)
    rows = db.all(f"SELECT * FROM sn_transfer{where} ORDER BY id DESC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result(
        [{"transfer": util.jrow(t, ORG_FIELDS), "direction": _direction(cu, t["from_factory"])} for t in rows],
        total,
        page,
    )


def list_bill(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None, page: util.Page) -> dict:
    where, args = _bill_where(cu, factory, pi, bill)
    total = db.count("SELECT COUNT(*) FROM sn_bill_transfer" + where, *args)
    rows = db.all(f"SELECT * FROM sn_bill_transfer{where} ORDER BY id DESC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result([util.jrow(t, BILL_FIELDS) for t in rows], total, page)


# 合并列表的统一列：转单不限来源生产组织、没有号段和目标客户；转移单生成即生效，转单状态记 APPROVED
_ALL_ORG = (
    "SELECT 'ORG' kind, id, transfer_no, mode, status, scope, '' scope_text, from_factory, from_pi, from_bill, "
    "from_material, from_sn, from_end_sn, to_factory, to_pi, to_bill, to_seq, to_material, to_customer, qty, reason, "
    "applied_by created_by, applied_at created_at FROM sn_transfer"
)
_ALL_BILL = (
    "SELECT 'BILL' kind, id, transfer_no, mode, 'APPROVED' status, scope, scope_text, NULL from_factory, from_pi, "
    "from_bill, '' from_material, '' from_sn, '' from_end_sn, to_factory, to_pi, to_bill, to_seq, to_material, "
    "'' to_customer, qty, reason, created_by, created_at FROM sn_bill_transfer"
)
ALL_FIELDS = (
    "kind",
    "id",
    "transfer_no",
    "mode",
    "status",
    "scope",
    "scope_text",
    "from_factory",
    "from_pi",
    "from_bill",
    "from_material",
    "from_sn",
    "from_end_sn",
    "to_factory",
    "to_pi",
    "to_bill",
    "to_seq",
    "to_material",
    "to_customer",
    "qty",
    "reason",
    "created_by",
    "created_at",
)


def list_all(
    cu: CurrentUser, kind: str | None, factory: str | None, pi: str | None, bill: str | None, page: util.Page
) -> dict:
    """转单与转组织合在一张表里（新的在前）；kind = BILL / ORG 只看一种。"""
    kind = util.trim(kind).upper()
    parts: list[str] = []
    args: list = []
    if kind in ("", KIND_ORG):
        ow, oa = _org_where(cu, factory, pi, bill)
        parts.append(_ALL_ORG + ow)
        args += oa
    if kind in ("", KIND_BILL):
        bw, ba = _bill_where(cu, factory, pi, bill)
        parts.append(_ALL_BILL + bw)
        args += ba
    if not parts:
        return util.page_result([], 0, page)
    union = " UNION ALL ".join(f"({x})" for x in parts)
    total = db.count(f"SELECT COUNT(*) FROM ({union}) u", *args)
    rows = db.all(
        f"SELECT * FROM ({union}) u ORDER BY created_at DESC, id DESC LIMIT %s,%s", *args, page.offset, page.size
    )
    items = []
    for t in rows:
        x = util.jrow(t, ALL_FIELDS)
        x["direction"] = _direction(cu, t["from_factory"]) if t["kind"] == KIND_ORG else ""
        items.append(x)
    return util.page_result(items, total, page)


def _push_info(kind: str, tid: int, doc_no: str) -> dict:
    """改挂、转出推送的批次与结果；未完成的可继续推送。"""
    out_batches = [
        delivery.batch_json(b)
        for b in db.all(
            "SELECT * FROM sn_delivery_batch WHERE transfer_doc_no = %s AND batch_type = 'TRANSFER_OUT' ORDER BY id",
            doc_no,
        )
    ]
    targets = db.all(
        "SELECT i.factory_code, i.pi_no, COUNT(*) n, SUM(i.status = 'TO_PUSH') pending, "
        "GROUP_CONCAT(DISTINCT i.delivery_batch_no) batches FROM sn_transfer_item t JOIN sn_item i ON i.id = t.item_id "
        "WHERE t.kind = %s AND t.transfer_id = %s AND i.transfer_doc_no = %s GROUP BY i.factory_code, i.pi_no",
        kind,
        tid,
        doc_no,
    )
    deliveries = []
    for r in targets:
        if r["factory_code"] is None:
            continue
        rem = delivery.remaining_pushes(r["factory_code"], r["pi_no"]) if factories.is_mes(r["factory_code"]) else None
        deliveries.append(
            {
                "factory_code": r["factory_code"],
                "pi_no": r["pi_no"],
                "qty": int(r["n"]),
                "to_push": int(r["pending"] or 0),
                "batches": [x for x in (r["batches"] or "").split(",") if x],
                "remaining": rem,
            }
        )
    return {"out_batches": out_batches, "deliveries": deliveries}


def org_view(cu: CurrentUser, tid: int) -> dict:
    t = db.one("SELECT * FROM sn_transfer WHERE id = %s", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    cu.check_read(t["from_factory"], t["to_factory"])
    out = util.jrow(t, ORG_FIELDS)
    out["direction"] = _direction(cu, t["from_factory"])
    if t["status"] == APPROVED:
        out.update(_push_info(KIND_ORG, tid, t["transfer_no"]))
    return out


def bill_transfer_view(cu: CurrentUser, tid: int) -> dict:
    t = db.one("SELECT * FROM sn_bill_transfer WHERE id = %s", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    if cu.bound:
        # 与列表同一口径：号的来源或去向生产组织、来源单据或目标单据的生产组织在可看范围内
        r = db.one(
            "SELECT GROUP_CONCAT(DISTINCT from_factory) f, GROUP_CONCAT(DISTINCT to_factory) t FROM sn_transfer_item "
            "WHERE kind = %s AND transfer_id = %s",
            KIND_BILL,
            tid,
        )
        orgs = {x for v in (r["f"], r["t"]) for x in (v or "").split(",") if x}
        cu.check_read(*orgs, t["to_factory"], *orders.bill_orgs([t["from_bill"]]))
    out = util.jrow(t, BILL_FIELDS)
    out.update(_push_info(KIND_BILL, tid, t["transfer_no"]))
    return out


def items(cu: CurrentUser, kind: str, tid: int, page: util.Page) -> dict:
    """转移明细：每枚号转移前后的挂靠和状态、原批次、转出批次；转出组织的号标「已转出」。"""
    if kind == KIND_ORG:
        org_view(cu, tid)
    else:
        bill_transfer_view(cu, tid)
    total = db.count("SELECT COUNT(*) FROM sn_transfer_item WHERE kind = %s AND transfer_id = %s", kind, tid)
    rows = db.all(
        "SELECT t.*, i.status cur_status, i.factory_code cur_factory, i.pi_no cur_pi, i.rule_version_id, "
        "i.delivery_batch_no cur_batch, i.attach_no, i.length_text length "
        "FROM sn_transfer_item t LEFT JOIN sn_item i ON i.id = t.item_id "
        "WHERE t.kind = %s AND t.transfer_id = %s ORDER BY t.seq_pi_no, t.seq_dec LIMIT %s, %s",
        kind,
        tid,
        page.offset,
        page.size,
    )
    out = []
    for r in rows:
        cur = r["cur_status"]
        if cu.bound and r["from_factory"] in cu.factories and not cu.can_read(r["cur_factory"]):
            cur = "TRANSFERRED_OUT"
        v = {k: util.jv(r[k]) for k in r if k not in ("rule_version_id",)}
        v["seq_text"] = rules.seq_text(r["rule_version_id"], r["seq_dec"])
        v["current_status"] = cur
        out.append(v)
    return util.page_result(out, total, page)


OUT_LIST_HEADERS = [
    "行号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "流水归属PI",
    "组织编号",
    "生产组织名称",
    "转出前PI",
    "转出前单据",
    "转出前序号",
    "转出前物料",
    "附件项次",
    "米数",
    "原交付批次",
    "转出批次",
    "转入生产组织",
    "转入生产组织名称",
]


def out_list(cu: CurrentUser, tid: int):
    """转出号清单：完整 SN、流水、来源组织（编号与中文名称）、转出前挂靠（物料编码带附件项次、米数）、原批次，
    来源现场据此作废已打出的旧标签。"""
    t = org_view(cu, tid)
    names = factories.names()

    def rows():
        last = 0
        no = 0
        while True:
            page = db.all(
                "SELECT t.*, i.rule_version_id, i.attach_no, i.length_text FROM sn_transfer_item t "
                "LEFT JOIN sn_item i ON i.id = t.item_id "
                "WHERE t.kind = 'ORG' AND t.transfer_id = %s AND t.item_id > %s AND t.to_factory IS NOT NULL "
                "AND t.to_factory <> t.from_factory ORDER BY t.item_id LIMIT 5000",
                tid,
                last,
            )
            if not page:
                return
            for r in page:
                no += 1
                last = r["item_id"]
                yield [
                    no,
                    r["sn"],
                    rules.seq_text(r["rule_version_id"], r["seq_dec"]),
                    str(r["seq_dec"]),
                    r["seq_pi_no"],
                    r["from_factory"] or "",
                    names.get(r["from_factory"], "") if r["from_factory"] else "",
                    r["from_pi"],
                    r["from_bill"],
                    r["from_seq"],
                    r["from_material"],
                    r["attach_no"] or "",
                    r["length_text"] or "",
                    r["from_delivery_batch"],
                    r["out_batch_no"],
                    r["to_factory"],
                    names.get(r["to_factory"], "") if r["to_factory"] else "",
                ]

    return t, files.xlsx_bytes(t["transfer_no"], OUT_LIST_HEADERS, rows())
