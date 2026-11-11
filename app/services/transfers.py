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

转单、转组织的来源与目标规则相同：来源按 PI → 单据筛选，范围二选一——整单转移（单据下全部号），或按物料编码转移
（选一个或多个物料编码；物料编码与附件项次同级，「物料编码 + 附件项次」确定一组号；每组可转全部、单枚或连续号段、
或指定数量——按流水从小到大取可转的号）；转组织另限来源生产组织（转单指定了来源生产组织时同样只取该组织的号）。
目标 PI、单据、生产组织都必填；只有同物料编码、同附件项次才能转移：每组号改挂到目标单据中物料编码、附件项次都相同的
物料行（客户取目标单据的；这样的行有多行时按米数相符的选，仍有多行须指定序号；同一单据内转组织时留在原行），
没有这样的行则拒绝（整单转移时单据下每个物料编码 + 附件项次都要有）。
转单的目标生产组织就是目标单据的生产组织、目标单据不能是来源单据；
转组织的目标生产组织可任选启用的（可与来源相同）。
号换了物料行时已生成数、已分配数随号移动，额度不变。

SN 导入的号（origin = IMPORT）与生成的号同一套转移规则（同物料编码、同附件项次、米数相符），区别只在挂靠的记法：
号池里导入的号的单据序号始终为 0（导入功能自成一套，不进生成页的物料行，已生成数、已分配数都不计），
所在物料行记在导入记录 sn_import_item.cur_bill_seq（见 _line_of），转移时随号改到目标行；没有导入记录的不能转。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, bill_stats, changes, delivery, factories, files, locks, orders, rules

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
    "length_text, attach_no, rule_version_id, status, delivery_batch_no, mes_holder, push_type, delivery_mode, origin"
)

#: 同 ITEM_COLS，带号池表别名 i.（与导入记录联查时用）
_I_COLS = ", ".join("i." + c.strip() for c in ITEM_COLS.split(","))

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
    """不能转的号及原因：IMPORTED（SN 导入的号没有导入记录、不知道在哪个物料行）/ PENDING_ALLOC（只有分配成功的号
    才能转）/ BATCH_PENDING / TRANSFER_OUT_PENDING。整张单据转走时这些号留在原单据，其余照转。
    items 须经 _with_import_lines 带上导入的号的物料行。"""
    out: dict[int, str] = {}
    for r in items:
        if r["origin"] == util.ORIGIN_IMPORT and r.get("import_seq") is None:
            out[r["id"]] = "IMPORTED"
        elif r["status"] == util.PENDING_ALLOC:
            out[r["id"]] = "PENDING_ALLOC"
        elif r["status"] == util.TO_PUSH and r["delivery_batch_no"] is not None:
            out[r["id"]] = "BATCH_PENDING"
    for i in delivery.frozen_by_transfer_out([r["id"] for r in items if r["id"] not in out]):
        out[i] = "TRANSFER_OUT_PENDING"
    return out


_REASON_TEXT = {
    "IMPORTED": "SN 导入的号没有导入记录（不知道在哪个物料行），不能转移",
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
    if src.single_range and len(items) == 1:
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
    #: 物料行的已生成数、已分配数随号移动（转的都是已分配的号，两者变化相同）；导入的号不计
    line_moves: dict[tuple[str, int], int] = {}
    #: 导入的号转到的物料行 → 导入记录 id
    import_lines: dict[int, list[int]] = {}
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
                    # 导入的号记转出前所在的物料行（明细 billSeq），号池里是 0
                    "bill_seq": _line_of(r),
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
        # 批次类型只在转出组织的 MES 已持有时记为转移（transfer_from_org = 转出组织）。转出组织没有持有时：
        # 同组织转移保留原标记（如暂停期间还没下发的跨组织转入，仍与原组织的转出成对；没有即首次下发）；
        # 跨组织转移清除标记，转入方为首次下发 NEW（对接说明 1.3「原组织未持有：NEW」）——否则沿用更早一次转移的
        # 标记，转回该组织会被当成同组织转移、转到第三个组织会被当成跨组织转入（都没有与之成对的转出）
        from_org = old_org if r["mes_holder"] == old_org else None
        keep_from = from_org is None and new_org == old_org
        # 导入的号：号池里单据序号仍为 0，所在物料行改在导入记录（下面），也不计已生成数、已分配数
        seq = 0 if _is_import(r) else t.seq
        key = (new_org, t.pi, t.customer, t.bill, seq, t.material, status, push_type, mode, from_org, keep_from)
        groups.setdefault(key, []).append(r["id"])
        if _is_import(r):
            import_lines.setdefault(t.seq, []).append(int(r["seq_dec"]))
        elif (t.bill, t.seq) != (r["bill_no"], r["bill_seq"]):
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
                _line_of(r),
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
    # 单据汇总：转出的（PI，单据）与转入的都要重算
    bill_stats.touch_many([(r["pi_no"], r["bill_no"]) for r in items] + [(t.pi, t.bill) for t in targets.values()])
    # 号已在本事务内 FOR UPDATE 锁住，rev + 1 与上面按读出值算的 rev 一致
    for (
        new_org,
        pi,
        customer,
        bill,
        seq,
        material,
        status,
        push_type,
        mode,
        from_org,
        keep_from,
    ), ids in groups.items():
        for i in range(0, len(ids), 1000):
            part = ids[i : i + 1000]
            db.exec(
                "UPDATE sn_item SET factory_code = %s, pi_no = %s, customer_code = %s, bill_no = %s, bill_seq = %s, "
                "material_code = %s, rev = rev + 1, status = %s, push_type = %s, delivery_mode = %s, "
                "delivery_batch_no = NULL, "
                "transfer_kind = %s, transfer_doc_no = %s, "
                "transfer_from_org = IF(%s, transfer_from_org, %s), "
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
                1 if keep_from else 0,
                from_org,
                now,
                *part,
            )
    for seq, ids in sorted(import_lines.items()):
        for i in range(0, len(ids), 1000):
            part = ids[i : i + 1000]
            db.exec(f"UPDATE sn_import_item SET cur_bill_seq = %s WHERE id IN ({marks(len(part))})", seq, *part)
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
    # 目标单据此前没出过号（没生成、没导入）时建台账（同生成、导入）：否则号挂到这张单后，
    # 订单变更比对（PI / 组织 / 客户变更、失去对应、反审核）整张跳过，未交付的号不会暂停
    for no in sorted({targets[r["id"]].bill for r in items}):
        changes.ensure_ledger(no)
    # 先按目标单据重算暂停标记再建批：已入批的号不再暂停，目标单据暂停交付时号须留在号池
    changes.refresh_items({r["bill_no"] for r in items} | {t.bill for t in targets.values()})
    # 转移前 MES 已持有的号当场给转入方建交付批次；MES 还没收到的号回号池按流水认领（对 MES 仍是首次下发）
    held: dict[tuple[str, str], list[int]] = {}
    for (new_org, pi, *_, status, _push, _mode, from_org, _keep), ids in groups.items():
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
class LineSpec:
    """按物料编码转移时选中的一个物料编码 + 附件项次（两者同级，一起确定一组号）及其转移方式。"""

    material_code: str | None = None
    #: None = 该物料编码下全部附件项次（只传物料编码的旧调用）
    attach_no: str | None = None
    #: ALL 该物料编码 + 附件项次下全部号 / RANGE 单枚或连续号段 / QTY 指定数量（留空按其余字段推断）
    mode: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    qty: int | None = None
    #: 目标单据中同物料编码、同附件项次有多行时指定序号
    to_seq: int | None = None


@dataclass(frozen=True)
class TransferRequest:
    """来源：PI → 单据 →（整单 / 一个或多个物料编码 + 附件项次，各自全部、号段或指定数量）；
    目标：PI + 单据 + 生产组织都必填，物料行按同物料编码、同附件项次自动匹配。"""

    #: 来源生产组织（转组织必填；转单可不填，填了只取该组织的号）
    factory_code: str | None = None
    pi_no: str | None = None
    bill_no: str | None = None
    #: BILL 整单 / MATERIAL 按物料编码（单个或多个，见 lines）/ RANGE 单个物料编码下单枚或连续号段（旧调用）
    scope: str | None = None
    #: 只选一个物料编码时可不用 lines，直接填以下几项
    material_code: str | None = None
    attach_no: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    qty: int | None = None
    #: 按物料编码转移时选中的各物料编码；整单转移时只用来给各物料编码指定目标序号
    lines: tuple[LineSpec, ...] | None = None
    to_pi: str | None = None
    to_bill: str | None = None
    to_factory: str | None = None
    #: 选填；填了须与来源物料编码相同（只有同物料编码、同附件项次才能转移）
    to_material: str | None = None
    #: 目标单据中同物料编码、同附件项次有多行时指定序号（各物料编码未单独指定时都用它）
    to_seq: int | None = None
    reason: str | None = None


PICK_ALL = "ALL"
PICK_RANGE = "RANGE"
PICK_QTY = "QTY"
PICK_MODES = frozenset((PICK_ALL, PICK_RANGE, PICK_QTY))


def _attach_of(r: dict) -> str:
    return r["attach_no"] or ""


def _is_import(r: dict) -> bool:
    return r["origin"] == util.ORIGIN_IMPORT


def _line_of(r: dict) -> int:
    """号所在的物料行：生成的号为号池的单据序号；导入的号为导入记录的当前行号（号池里记 0）。"""
    return int(r["import_seq"]) if _is_import(r) else int(r["bill_seq"])


def _with_import_lines(items: list[dict]) -> list[dict]:
    """导入的号带上导入记录的当前行号 import_seq（没有导入记录为 None，不能转）；生成的号不变、不查库。"""
    imp = [r for r in items if _is_import(r)]
    if imp:
        from app.services import sn_import

        details = sn_import.details([r["seq_dec"] for r in imp])
        for r in imp:
            d = details.get(int(r["seq_dec"]))
            r["import_seq"] = None if d is None else int(d["cur_bill_seq"])
    return items


@dataclass(frozen=True)
class Pick:
    """来源中选中的一组号：物料编码 + 附件项次（同级）及转移方式。"""

    material: str
    #: None = 该物料编码下全部附件项次
    attach: str | None
    mode: str
    start_sn: str = ""
    end_sn: str = ""
    qty: int = 0
    to_seq: int | None = None

    def matches(self, r: dict) -> bool:
        return r["material_code"] == self.material and (self.attach is None or _attach_of(r) == self.attach)

    def overlaps(self, o: Pick) -> bool:
        return self.material == o.material and (self.attach is None or o.attach is None or self.attach == o.attach)

    def text(self) -> str:
        s = self.material or "(空物料编码)"
        if self.attach is not None:
            s += f"（附件项次 {self.attach or '空'}）"
        if self.mode == PICK_RANGE:
            s += " " + (self.start_sn if self.start_sn == self.end_sn else f"{self.start_sn} ~ {self.end_sn}")
        elif self.mode == PICK_QTY:
            s += f" {self.qty} 枚"
        return s


@dataclass(frozen=True)
class Source:
    pi: str
    bill: str
    scope: str
    #: 按物料编码转移时选中的各组；整单转移时只带各物料编码指定的目标序号（不限定范围）
    picks: tuple[Pick, ...]

    @property
    def single_range(self) -> Pick | None:
        """只选了一个物料编码、且按号段转移时的那一组（转组织单记起止号）。"""
        if self.scope != SCOPE_BILL and len(self.picks) == 1 and self.picks[0].mode == PICK_RANGE:
            return self.picks[0]
        return None

    @property
    def doc_scope(self) -> str:
        """转移单上记的范围：BILL 整单 / RANGE 单个物料编码下单枚或号段 / MATERIAL 其余按物料编码转移。"""
        if self.scope == SCOPE_BILL:
            return SCOPE_BILL
        return SCOPE_RANGE if self.single_range else SCOPE_MATERIAL

    @property
    def material(self) -> str:
        if self.scope == SCOPE_BILL:
            return ""
        return util.cut("、".join(dict.fromkeys(p.material for p in self.picks)), 64)

    @property
    def start_sn(self) -> str:
        p = self.single_range
        return p.start_sn if p else ""

    @property
    def end_sn(self) -> str:
        p = self.single_range
        return p.end_sn if p else ""

    def text(self, factory: str | None = None) -> str:
        parts = [x for x in (factory, self.pi, self.bill) if x]
        if self.scope != SCOPE_BILL:
            parts += [p.text() for p in self.picks]
        return " / ".join(parts)


@dataclass(frozen=True)
class Dest:
    """目标单据（快照中，物料行带生效的附件项次、米数）与转入生产组织。"""

    factory: str
    pi: str
    customer: str
    bill: str
    lines: list[dict]


def _pick_of(scope: str, s: LineSpec) -> Pick:
    if s.material_code is None:
        raise biz(ErrorCode.TR_MATERIAL_REQUIRED)
    material = s.material_code.strip()
    attach = None if s.attach_no is None else s.attach_no.strip()
    if scope == SCOPE_BILL:
        return Pick(material, attach, PICK_ALL, to_seq=s.to_seq)
    if scope == SCOPE_RANGE:
        mode = PICK_RANGE
    else:
        mode = util.trim(s.mode).upper() or (
            PICK_RANGE if util.trim(s.start_sn) else PICK_QTY if s.qty is not None else PICK_ALL
        )
    if mode not in PICK_MODES:
        raise biz(ErrorCode.TR_LINE_MODE_INVALID)
    start = end = ""
    qty = 0
    if mode == PICK_RANGE:
        start = util.trim(s.start_sn)
        if not start:
            raise biz(ErrorCode.TR_SN_REQUIRED)
        end = util.trim(s.end_sn) or start
    elif mode == PICK_QTY:
        if s.qty is None or isinstance(s.qty, bool) or int(s.qty) < 1:
            raise biz(ErrorCode.TR_QTY_INVALID)
        qty = int(s.qty)
    return Pick(material, attach, mode, start, end, qty, s.to_seq)


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
    specs = list(r.lines or ())
    if not specs and scope != SCOPE_BILL:
        specs = [LineSpec(r.material_code, r.attach_no, None, r.start_sn, r.end_sn, r.qty)]
    picks: list[Pick] = []
    for s in specs:
        p = _pick_of(scope, s)
        if any(p.overlaps(o) for o in picks):
            raise biz(ErrorCode.TR_LINE_DUPLICATE, material=p.material or "(空)", attach_no=p.attach or "-")
        picks.append(p)
    if scope == SCOPE_RANGE and len(picks) != 1:
        raise biz(ErrorCode.TR_SCOPE_INVALID)
    return Source(pi, bill, scope, tuple(picks))


def _source_items(src: Source, factory: str | None, lock: bool = False) -> list[dict]:
    """整单：来源单据（该组织）下全部号；按物料编码：各物料编码 + 附件项次下全部、号段或指定数量的号。
    指定数量按流水从小到大取可转的号（不可转的跳过，可转的不够时拒绝）。"""
    cond, args = "pi_no = %s AND bill_no = %s", [src.pi, src.bill]
    if factory is not None:
        cond += " AND factory_code = %s"
        args.append(factory)
    if src.scope != SCOPE_BILL:
        mats = sorted({p.material for p in src.picks})
        cond += f" AND material_code IN ({marks(len(mats))})"
        args += mats
    items = _with_import_lines(
        db.all(
            f"SELECT {ITEM_COLS} FROM sn_item WHERE {cond} ORDER BY seq_pi_no, seq_dec"
            + (" FOR UPDATE" if lock else ""),
            *args,
        )
    )
    if src.scope == SCOPE_BILL:
        return items
    out: list[dict] = []
    for p in src.picks:
        g = [r for r in items if p.matches(r)]
        if p.mode == PICK_RANGE:
            g = _slice(g, p.start_sn, p.end_sn)
        elif p.mode == PICK_QTY:
            bad = blocked(g)
            ok = [r for r in g if r["id"] not in bad]
            if len(ok) < p.qty:
                raise biz(
                    ErrorCode.TR_QTY_EXCEEDS,
                    material=p.material or "(空)",
                    attach_no=p.attach or "-",
                    qty=p.qty,
                    available=len(ok),
                )
            g = ok[: p.qty]
        if not g:
            raise biz(ErrorCode.TR_LINE_EMPTY, material=p.material or "(空)", attach_no=p.attach or "-")
        out += g
    out.sort(key=lambda r: (r["seq_pi_no"], r["seq_dec"]))
    return out


def _slice(items: list[dict], start: str, end: str) -> list[dict]:
    """单枚或连续号段：起止号须都在范围内、属于同一流水归属 PI。"""
    by_sn = {r["sn"]: r for r in items}
    s, e = by_sn.get(start), by_sn.get(end)
    if s is None or e is None or s["seq_pi_no"] != e["seq_pi_no"]:
        raise biz(ErrorCode.TR_RANGE_INVALID, start_sn=start, end_sn=end)
    lo, hi = min(s["seq_dec"], e["seq_dec"]), max(s["seq_dec"], e["seq_dec"])
    return [r for r in items if r["seq_pi_no"] == s["seq_pi_no"] and lo <= r["seq_dec"] <= hi]


def _dest(kind: str, r: TransferRequest, source_bill: str) -> Dest:
    """目标 PI、单据、生产组织都必填；单据须在快照中且属于该 PI。
    转单的生产组织就是目标单据的生产组织；转组织可选任一启用的生产组织。
    物料行在 _targets 中按物料编码 + 附件项次匹配。"""
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
    return Dest(to_factory, tb.pi, tb.customer_code, tb.bill_no, orders.effective_lines(tb.lines))


def _not_match(d: Dest, material: str, attach: str):
    return biz(ErrorCode.TR_TARGET_LINE_NOT_MATCH, bill_no=d.bill, material=material or "(空)", attach_no=attach or "-")


def _dest_line(d: Dest, src: Source, r: TransferRequest, key: tuple[str, str, int], rs: list[dict]) -> dict:
    """一组号（物料编码 + 附件项次 + 来源序号）的目标物料行：只有同物料编码、同附件项次的行才能转入。
    指定了序号时须是这样的行；没指定时：同一单据内转（转组织）留在原行，其余在米数相符的行里只有一行时选它。"""
    material, attach, from_seq = key
    to_mat = None if r.to_material is None else r.to_material.strip()
    if to_mat is not None and to_mat != material:
        raise _not_match(d, material, attach)
    pick = next((p for p in src.picks if p.matches(rs[0])), None)
    want = pick.to_seq if pick is not None and pick.to_seq is not None else r.to_seq
    cands = [x for x in d.lines if x["material_code"] == material and x["eff_attach"] == attach]
    if want is not None:
        line = next((x for x in d.lines if x["seq"] == want), None)
        if line is None:
            raise biz(ErrorCode.TR_TARGET_LINE_INVALID, seq=want)
        if line not in cands:
            raise _not_match(d, material, attach)
        return line
    if not cands:
        raise _not_match(d, material, attach)
    if d.bill == rs[0]["bill_no"]:
        stay = next((x for x in cands if x["seq"] == from_seq), None)
        if stay is not None:
            return stay
    lens = {x["length_text"] for x in rs if x["length_text"] is not None}
    fit = [x for x in cands if all(v == x["eff_length"] for v in lens)]
    if len(fit) == 1:
        return fit[0]
    if not fit and len(cands) == 1:
        return cands[0]
    if not fit:
        raise biz(
            ErrorCode.TR_LENGTH_MISMATCH,
            target="、".join(dict.fromkeys(x["eff_length"] or "(空)" for x in cands)),
            current="、".join(sorted(lens)),
        )
    raise biz(ErrorCode.TR_TARGET_LINE_REQUIRED, bill_no=d.bill, material=material or "(空)", attach_no=attach or "-")


def _targets(items: list[dict], d: Dest, src: Source, r: TransferRequest) -> tuple[dict[int, Target], list[dict]]:
    """号按物料编码 + 附件项次 + 来源序号分组，各组改挂到目标单据中同物料编码、同附件项次的物料行与转入生产组织；
    目标米数与号上米数不同则拒绝（SN 已含米数）。SN 导入的号按导入记录的当前行号作来源序号，与生成的号一样匹配；
    没有导入记录的不能转、留在原单据，不参与匹配。返回 (各号的挂靠, 各组的对应)。"""
    groups: dict[tuple[str, str, int], list[dict]] = {}
    for x in items:
        if _is_import(x) and x.get("import_seq") is None:
            continue
        groups.setdefault((x["material_code"], _attach_of(x), _line_of(x)), []).append(x)
    out: dict[int, Target] = {}
    mapping: list[dict] = []
    for key, rs in groups.items():
        line = _dest_line(d, src, r, key, rs)
        for x in rs:
            if x["length_text"] is not None and x["length_text"] != line["eff_length"]:
                raise biz(ErrorCode.TR_LENGTH_MISMATCH, target=line["eff_length"] or "(空)", current=x["length_text"])
            out[x["id"]] = Target(d.factory, d.pi, d.customer, d.bill, line["seq"], line["material_code"])
        mapping.append(
            {
                "material_code": key[0],
                "attach_no": key[1],
                "from_seq": key[2],
                "to_seq": line["seq"],
                "length": line["eff_length"],
                "ids": [x["id"] for x in rs],
            }
        )
    mapping.sort(key=lambda m: (m["material_code"], m["attach_no"], m["from_seq"]))
    return out, mapping


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
    #: 各组（物料编码 + 附件项次 + 来源序号）→ 目标序号
    mapping: list[dict]

    def to_seq(self) -> int | None:
        """转移单上记的目标序号：各组都转到同一行时记该行，否则为空（明细里逐枚记）。"""
        seqs = {m["to_seq"] for m in self.mapping}
        return seqs.pop() if len(seqs) == 1 else None

    def to_material(self) -> str:
        return util.cut("、".join(dict.fromkeys(m["material_code"] for m in self.mapping)), 64)

    def to_text(self) -> str:
        """目标说明（操作痕迹用）：单据 / 序号 / 物料编码 / PI / 生产组织，多组时序号逐组列出。"""
        seqs = ",".join(f"{m['material_code']}#{m['attach_no']}:{m['from_seq']}->{m['to_seq']}" for m in self.mapping)
        return f"{self.dest.bill}/{seqs}/{self.dest.pi}/{self.dest.factory}"


def _plan(cu: CurrentUser, kind: str, r: TransferRequest, lock: bool = False) -> Plan:
    factory = _from_factory(cu, r.factory_code) if kind == KIND_ORG or util.trim(r.factory_code) else None
    src = _source(r)
    d = _dest(kind, r, src.bill)
    items = _source_items(src, factory, lock)
    if not items:
        raise biz(ErrorCode.TR_NOTHING)
    targets, mapping = _targets(items, d, src, r)
    if kind == KIND_BILL:
        # 转单：号现在所在的组织须在授权范围内；目标单据的组织不限（来源组织在 _from_factory 校验）
        cu.check_write(*{x["factory_code"] for x in items})
    movable = [x for x in items if x["id"] in targets]
    if movable and all(
        (x["factory_code"] or d.factory, x["pi_no"], x["bill_no"], _line_of(x))
        == (d.factory, d.pi, d.bill, targets[x["id"]].seq)
        for x in movable
    ):
        raise biz(ErrorCode.TR_SAME_TARGET)
    return Plan(factory, src, d, items, targets, mapping)


def decide_kind(r: TransferRequest) -> str:
    """页面不区分转单、转组织，按目标判断：目标单据与来源单据不同、且目标生产组织就是目标单据的生产组织 → 转单；
    其余（换了生产组织，或同一单据内换组织）→ 转组织。目标单据不在快照时按转组织，由 _dest 报错。"""
    tb = orders.bill(util.trim(r.to_bill)) if util.trim(r.to_bill) else None
    if tb is not None and tb.bill_no != util.trim(r.bill_no) and util.trim(r.to_factory) == tb.org_code:
        return KIND_BILL
    return KIND_ORG


def sources(cu: CurrentUser, kind: str, factory: str | None, pi: str | None) -> dict:
    """来源候选：PI 下各单据、各物料编码 + 附件项次（同级）分配成功的号数（待分配的号不能转；
    SN 导入的号与生成的号一起算；转组织限来源生产组织），供逐级筛选。materials 按物料编码 + 附件项次列出；
    rows 再按生产组织细分，带号上的客户、米数与号所在的 ERP行号 seqs
    （导入的号取导入记录的当前行号；页面据此带出来源生产组织与来源信息）。"""
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
    mats: dict[tuple[str, str, str], dict] = {}
    rows: dict[tuple[str, str, str, str], dict] = {}
    for x in db.all(
        "SELECT i.bill_no, i.material_code, i.factory_code, i.customer_code, i.attach_no, i.length_text, "
        "COALESCE(m.cur_bill_seq, i.bill_seq) line_seq, COUNT(*) n "
        "FROM (SELECT bill_no, material_code, factory_code, customer_code, attach_no, length_text, bill_seq, origin, "
        f"seq_dec FROM sn_item WHERE {cond}) i "
        f"LEFT JOIN sn_import_item m ON i.origin = '{util.ORIGIN_IMPORT}' AND m.id = i.seq_dec "
        "GROUP BY i.bill_no, i.material_code, i.factory_code, i.customer_code, i.attach_no, i.length_text, line_seq "
        "ORDER BY i.bill_no, i.material_code, i.attach_no, i.factory_code",
        *args,
    ):
        n = int(x["n"])
        attach = x["attach_no"] or ""
        b = bills.setdefault(x["bill_no"], {"bill_no": x["bill_no"], "qty": 0, "materials": [], "rows": []})
        b["qty"] += n
        mk = (x["bill_no"], x["material_code"], attach)
        mm = mats.get(mk)
        if mm is None:
            mm = mats[mk] = {"material_code": x["material_code"], "attach_no": attach, "qty": 0}
            b["materials"].append(mm)
        mm["qty"] += n
        rk = (x["bill_no"], x["material_code"], attach, x["factory_code"])
        row = rows.get(rk)
        if row is None:
            row = rows[rk] = {
                "material_code": x["material_code"],
                "attach_no": attach,
                "factory_code": x["factory_code"],
                "qty": 0,
                "customers": [],
                "attach_nos": [],
                "lengths": [],
                "seqs": [],
            }
            b["rows"].append(row)
        row["qty"] += n
        for k, v in (("customers", x["customer_code"]), ("attach_nos", x["attach_no"]), ("lengths", x["length_text"])):
            if v is not None and v not in row[k]:
                row[k].append(v)
        if int(x["line_seq"]) not in row["seqs"]:
            row["seqs"].append(int(x["line_seq"]))
    for b in bills.values():
        for row in b["rows"]:
            row["seqs"].sort()
    return {"bills": list(bills.values())}


def locate(cu: CurrentUser, q: str | None) -> dict:
    """快速定位：输入完整 SN、单据编号或 PI，带出来源（只在读范围内、分配成功的号中找）。
    SN → PI、单据、物料编码、生产组织、起号；单据编号 → PI、单据；PI → PI。SN 导入的号与生成的号一样定位。"""
    text = util.trim(q)
    if not text:
        raise biz(ErrorCode.TR_LOCATE_NOT_FOUND, q="")
    fsql, fargs = factories.scope_sql(cu.read_factories(None))
    r = db.one(
        f"SELECT sn, pi_no, bill_no, material_code, attach_no, factory_code, status, origin FROM sn_item "
        f"WHERE sn = %s{fsql}",
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
            "attach_no": r["attach_no"] or "",
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
        # 导入的号不计已生成数
        if not _is_import(x) and (t.bill, t.seq) != (x["bill_no"], x["bill_seq"]):
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
        to_seq=p.to_seq(),
        to_material=p.to_material(),
        to_customer=d.customer,
        lines=[
            {
                "material_code": mp["material_code"],
                "attach_no": mp["attach_no"],
                "from_seq": mp["from_seq"],
                "to_seq": mp["to_seq"],
                "length": mp["length"],
                "total": len(mp["ids"]),
                "transferable": sum(1 for i in mp["ids"] if i not in bad),
            }
            for mp in p.mapping
        ],
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
            src.doc_scope,
            src.bill,
            d.bill,
            src.pi,
            d.pi,
            d.factory,
            p.to_seq(),
            p.to_material(),
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
                f"to={p.to_text()} scope={scope} out_batches="
                + ",".join(b["batch_no"] for b in moved["out"])
                + " in_batches="
                + ",".join(b["batch_no"] for b in moved["in"])
            ),
        )
    _after_move(cu, moved)
    return bill_transfer_view(cu, tid)


# ================================================================== 转组织


def _dest_row(p: Plan) -> dict:
    d = p.dest
    return {
        "to_factory": d.factory,
        "to_pi": d.pi,
        "to_bill": d.bill,
        "to_seq": p.to_seq(),
        "to_material": p.to_material(),
        "to_customer": d.customer,
        "to_text": p.to_text(),
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
        src.doc_scope,
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
        p.to_seq(),
        p.to_material(),
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
        .info(f"scope={scope} to={t['to_text']}")
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
        e = _org_entry(audit.TRANSFER_DIRECT, no, p.factory, p.src.pi, p.src.text(), _dest_row(p), len(ok), reason, ok)
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
        # SN 导入的号：号池单据序号为 0，所在行记在导入记录（见 _line_of），一并改到新行
        imported = db.all(
            f"SELECT {_I_COLS} FROM sn_item i JOIN sn_import_item s ON s.id = i.seq_dec "
            f"WHERE i.bill_no = %s AND i.origin = '{util.ORIGIN_IMPORT}' AND s.cur_bill_seq = %s FOR UPDATE",
            bill,
            old,
        )
        items += imported
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
        bill_stats.touch_many((x["pi_no"], x["bill_no"]) for x in items)
        for k, ids in groups.items():
            for i in range(0, len(ids), 1000):
                part = ids[i : i + 1000]
                db.exec(
                    f"UPDATE sn_item SET bill_seq = IF(origin = '{util.ORIGIN_IMPORT}', bill_seq, %s), "
                    f"material_code = %s{extra[k]}, updated_at = %s WHERE id IN ({marks(len(part))})",
                    target_seq,
                    line["material_code"],
                    now,
                    *part,
                )
        imp_ids = [int(x["seq_dec"]) for x in imported]
        for i in range(0, len(imp_ids), 1000):
            part = imp_ids[i : i + 1000]
            db.exec(f"UPDATE sn_import_item SET cur_bill_seq = %s WHERE id IN ({marks(len(part))})", target_seq, *part)
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
        # 号段预留随计数归新行（见 reserve）：之后在新行删号再生成回到原号段，原行不留没人能用的预留；
        # 新行已有预留时两段合在一起，生成时用不完的照常退回成空号段
        db.exec("UPDATE sn_pi_reserve SET seq = %s WHERE bill_no = %s AND seq = %s", target_seq, bill, old)
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
    imports = _import_serials(rows)
    for r in rows:
        cur = r["cur_status"]
        if cu.bound and r["from_factory"] in cu.factories and not cu.can_read(r["cur_factory"]):
            cur = "TRANSFERRED_OUT"
        v = {k: util.jv(r[k]) for k in r if k not in ("rule_version_id",)}
        if r["seq_pi_no"]:
            v["seq_text"] = rules.seq_text(r["rule_version_id"], r["seq_dec"])
        else:
            text, dec, seq_pi = _serial_of(r, imports)
            v.update(seq_text=text, seq_dec=dec, seq_pi_no=seq_pi)
        v["current_status"] = cur
        out.append(v)
    return util.page_result(out, total, page)


def _import_serials(rows: list[dict]) -> dict[int, dict]:
    """rows（带 seq_pi_no、seq_dec）里 SN 导入的号的导入记录；没有导入的号时不查库。"""
    ids = [r["seq_dec"] for r in rows if not r["seq_pi_no"]]
    if not ids:
        return {}
    from app.services import sn_import

    return sn_import.details(ids)


def _serial_of(r: dict, imports: dict[int, dict]) -> tuple[str, str, str]:
    """（进制流水, 十进制流水, 流水归属 PI）：生成的号按规则换算；导入的号取导入记录的流水号、十进制流水、PI。"""
    if r["seq_pi_no"]:
        return rules.seq_text(r["rule_version_id"], r["seq_dec"]), str(r["seq_dec"]), r["seq_pi_no"]
    d = imports.get(int(r["seq_dec"]))
    return ("", "", "") if d is None else (d["serial_text"], str(d["seq_dec"]), d["pi_no"])


OUT_LIST_HEADERS = [
    "序号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "流水归属PI",
    "组织编号",
    "生产组织名称",
    "转出前PI",
    "转出前单据",
    "附件项次",
    "转出前物料",
    "转出前ERP行号",
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
            imports = _import_serials(page)
            for r in page:
                no += 1
                last = r["item_id"]
                text, dec, seq_pi = _serial_of(r, imports)
                yield [
                    no,
                    r["sn"],
                    text,
                    dec,
                    seq_pi,
                    r["from_factory"] or "",
                    names.get(r["from_factory"], "") if r["from_factory"] else "",
                    r["from_pi"],
                    r["from_bill"],
                    r["attach_no"] or "",
                    r["from_material"],
                    r["from_seq"],
                    r["length_text"] or "",
                    r["from_delivery_batch"],
                    r["out_batch_no"],
                    r["to_factory"],
                    names.get(r["to_factory"], "") if r["to_factory"] else "",
                ]

    return t, files.xlsx_bytes(t["transfer_no"], OUT_LIST_HEADERS, rows())
