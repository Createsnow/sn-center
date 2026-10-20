"""转单（F-15）、转组织（F-16）与转移后的 MES 处理（5.2.8）。都不改 SN 字符串，都只在本系统改挂。

转移时每枚号：清掉原推送、拉取、领取批次，rev + 1；
- 仍在同一生产组织：MES 持有组织为本组织的号改为待推送（未入批）、推送类型改挂，随即自动推送；其余状态不变。
- 转到别的生产组织：转入方对接 MES 时改为待推送（未入批），MES 持有组织为来源组织的记改挂、其余记新增；
  转入方未对接时改为待领取。
- 号的 MES 持有组织为来源组织、且转入组织与来源不同时，在同一事务内向来源 MES 建立「转出」批次并推送。
不可转：申请中；所在批次未确认或失败；所在转出批次未确认或失败（先在原转组织单上继续推送转出）；领取未完成。
转单另可转待分配的号（仍为待分配，之后按目标单据的生产组织分配）；转组织不能转待分配的号。
转单移动已生成数、已分配数，额度不变；转组织不改来源单据和序号，计数和额度都不变。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, delivery, factories, files, locks, orders, rules

KIND_BILL = "BILL"
KIND_ORG = "ORG"
BILL_TRANSFER = "BILL_TRANSFER"
ORG_TRANSFER = "ORG_TRANSFER"
MODE_VOID = "VOID"
MODE_SPLIT = "SPLIT"
MODE_APPLY = "APPLY"
MODE_DIRECT = "DIRECT"
PENDING = "PENDING"
APPROVED = "APPROVED"
REJECTED = "REJECTED"
WITHDRAWN = "WITHDRAWN"
SCOPE_PI = "PI"
SCOPE_MATERIAL = "MATERIAL"
SCOPE_SINGLE = "SINGLE"
SCOPES = frozenset((SCOPE_PI, SCOPE_MATERIAL, SCOPE_SINGLE))

ITEM_COLS = (
    "id, sn, factory_code, pi_no, customer_code, bill_no, bill_seq, material_code, rev, seq_pi_no, seq_dec, "
    "length_text, rule_version_id, status, delivery_batch_no, acquire_batch_no, mes_holder, push_type, delivery_mode"
)

ORG_FIELDS = (
    "id",
    "transfer_no",
    "mode",
    "status",
    "scope",
    "from_factory",
    "from_pi",
    "from_material",
    "from_sn",
    "qty",
    "to_factory",
    "to_pi",
    "to_customer",
    "to_material",
    "manual_fields",
    "reason",
    "applied_by",
    "applied_at",
    "decided_by",
    "decided_at",
    "decide_note",
)
BILL_FIELDS = (
    "id",
    "transfer_no",
    "mode",
    "from_bill",
    "to_bill",
    "from_pi",
    "to_pi",
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


def blocked(items: list[dict], allow_pending_alloc: bool) -> dict[int, str]:
    """不能转的号及原因：APPLYING / BATCH_PENDING / TRANSFER_OUT_PENDING / ACQUIRE_INCOMPLETE / PENDING_ALLOC。"""
    out: dict[int, str] = {}
    for r in items:
        if r["status"] == util.APPLYING:
            out[r["id"]] = "APPLYING"
        elif r["status"] == util.PENDING_ALLOC and not allow_pending_alloc:
            out[r["id"]] = "PENDING_ALLOC"
        elif r["status"] == util.TO_PUSH and r["delivery_batch_no"] is not None:
            out[r["id"]] = "BATCH_PENDING"
        elif r["status"] in (util.TO_PUSH, util.TO_ACQUIRE) and r["acquire_batch_no"] is not None:
            out[r["id"]] = "ACQUIRE_INCOMPLETE"
    for i in delivery.frozen_by_transfer_out([r["id"] for r in items if r["id"] not in out]):
        out[i] = "TRANSFER_OUT_PENDING"
    return out


_REASON_TEXT = {
    "APPLYING": "申请中",
    "PENDING_ALLOC": "待分配（转组织不能转）",
    "BATCH_PENDING": "所在批次未确认或失败",
    "TRANSFER_OUT_PENDING": "所在转出批次未确认或失败，先在原转组织单上继续推送转出",
    "ACQUIRE_INCOMPLETE": "领取批次未完成",
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
        "delivered": sum(1 for r in items if r["mes_holder"] or r["status"] == util.ACQUIRED),
    }


def _check_any(items: list[dict], ok: list[dict], bad: dict[int, str], scope: str | None = None) -> None:
    """范围内一枚可转的都没有：单枚时说明原因；有不可转的列出原因；否则没有号。"""
    if ok:
        return
    if scope == SCOPE_SINGLE and items:
        raise biz(ErrorCode.TR_SN_NOT_TRANSFERABLE, sn=items[0]["sn"], reason=_REASON_TEXT.get(bad[items[0]["id"]], ""))
    _raise_if_blocked(bad)
    raise biz(ErrorCode.TR_NOTHING)


def _check_any(items: list[dict], ok: list[dict], bad: dict[int, str], scope: str | None = None) -> None:
    """范围内一枚可转的都没有：单枚时说明原因；有不可转的列出原因；否则没有号。"""
    if ok:
        return
    if scope == SCOPE_SINGLE and items:
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
    line_moves: dict[tuple[str, int], int] = {}
    alloc_moves: dict[tuple[str, int], int] = {}
    for r in items:
        t = targets[r["id"]]
        old_org = r["factory_code"]
        new_org = t.factory
        rev = int(r["rev"]) + 1
        status = r["status"]
        push_type = r["push_type"]
        mode = r["delivery_mode"]
        if status != util.PENDING_ALLOC:
            if new_org == old_org:
                if r["mes_holder"] == old_org and old_org in mes:
                    status, push_type, mode = util.TO_PUSH, util.PUSH_REASSIGN, None
            elif new_org in mes:
                status, mode = util.TO_PUSH, None
                push_type = util.PUSH_REASSIGN if r["mes_holder"] == old_org and old_org else util.PUSH_NEW
            else:
                status, push_type, mode = util.TO_ACQUIRE, util.PUSH_NEW, None
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
                    "rule_version_id": r["rule_version_id"],
                    "to_factory": new_org,
                    "transfer_kind": tkind,
                    "transfer_doc_no": doc_no,
                }
            )
        db.exec(
            "UPDATE sn_item SET factory_code = %s, pi_no = %s, customer_code = %s, bill_no = %s, bill_seq = %s, "
            "material_code = %s, rev = %s, status = %s, push_type = %s, delivery_mode = %s, delivery_batch_no = NULL, "
            "acquire_batch_no = NULL, transfer_id = NULL, applying_from = NULL, transfer_kind = %s, transfer_doc_no "
            "= %s, "
            "transfer_from_org = %s, updated_at = %s WHERE id = %s",
            new_org,
            t.pi,
            t.customer,
            t.bill,
            t.seq,
            t.material,
            rev,
            status,
            push_type,
            mode,
            tkind,
            doc_no,
            old_org,
            now,
            r["id"],
        )
        if (t.bill, t.seq) != (r["bill_no"], r["bill_seq"]):
            line_moves[(r["bill_no"], r["bill_seq"])] = line_moves.get((r["bill_no"], r["bill_seq"]), 0) - 1
            line_moves[(t.bill, t.seq)] = line_moves.get((t.bill, t.seq), 0) + 1
            if old_org is not None:
                alloc_moves[(r["bill_no"], r["bill_seq"])] = alloc_moves.get((r["bill_no"], r["bill_seq"]), 0) - 1
                alloc_moves[(t.bill, t.seq)] = alloc_moves.get((t.bill, t.seq), 0) + 1
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
                r["acquire_batch_no"],
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
    db.exec_many(
        "INSERT INTO sn_transfer_item(kind, transfer_id, item_id, sn, seq_pi_no, seq_dec, from_status, from_factory, "
        "from_pi, from_customer, from_bill, from_seq, from_material, from_delivery_batch, from_acquire_batch, "
        "from_rev, "
        "to_status, to_factory, to_pi, to_customer, to_bill, to_seq, to_material, to_rev) VALUES "
        "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON DUPLICATE KEY UPDATE to_status = VALUES(to_status), to_factory = VALUES(to_factory), to_pi = "
        "VALUES(to_pi), "
        "to_customer = VALUES(to_customer), to_bill = VALUES(to_bill), to_seq = VALUES(to_seq), "
        "to_material = VALUES(to_material), to_rev = VALUES(to_rev), from_rev = VALUES(from_rev), "
        "from_delivery_batch = VALUES(from_delivery_batch), from_acquire_batch = VALUES(from_acquire_batch)",
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
                alloc_moves.get((no, seq), 0),
                now,
                no,
                seq,
            )
    out_batches = []
    for org, entries in xo.items():
        bs = delivery.build_transfer_out(cu, org, entries)
        out_batches += bs
        for b in bs:
            ids = [e["item_id"] for e in entries if e["sn"] in _sns_of(b)]
            for i in range(0, len(ids), 1000):
                part = ids[i : i + 1000]
                db.exec(
                    f"UPDATE sn_transfer_item SET out_batch_no = %s WHERE kind = %s AND transfer_id = %s "
                    f"AND item_id IN ({marks(len(part))})",
                    b["batch_no"],
                    kind,
                    transfer_id,
                    *part,
                )
    changes.refresh_items({r["bill_no"] for r in items} | {t.bill for t in targets.values()})
    return {"to_push": to_push, "out": out_batches, "xo_orgs": sorted(xo)}


def _sns_of(b: dict) -> set[str]:
    return set(db.column("SELECT sn FROM sn_delivery_item WHERE batch_id = %s", b["id"]))


def _after_move(cu: CurrentUser, moved: dict) -> None:
    for org, pi in sorted(moved["to_push"]):
        delivery.start_task(cu, org, pi)
    for org in moved["xo_orgs"]:
        delivery.start_task(cu, org, delivery.XO_KEY)


# ================================================================== 转单


@dataclass(frozen=True)
class BillRequest:
    from_bill: str | None = None
    to_bill: str | None = None
    mode: str | None = None
    #: 整单作废：来源序号 → 目标序号（自动对应不了的行须给）
    mapping: dict | None = None
    #: 拆单：生产组织 + PI + 来源物料行，可填起止号只转一段
    factory_code: str | None = None
    pi_no: str | None = None
    from_seq: int | None = None
    start_sn: str | None = None
    end_sn: str | None = None
    to_seq: int | None = None
    reason: str | None = None


def _target_bill(no: str | None, source: str) -> orders.Bill:
    b = orders.bill(no)
    if b is None or not b.pi or b.bill_no == source:
        raise biz(ErrorCode.TR_TARGET_BILL_INVALID, bill_no=util.trim(no))
    return b


def _auto_line(tb: orders.Bill, material: str) -> dict | None:
    hit = [x for x in tb.lines if x["material_code"] == material]
    return hit[0] if len(hit) == 1 else None


def _bill_plan(req: BillRequest) -> tuple[list[dict], dict[int, Target], orders.Bill, str]:
    src = util.trim(req.from_bill)
    if not src:
        raise biz(ErrorCode.ORDER_BILL_REQUIRED)
    mode = util.trim(req.mode) or MODE_SPLIT
    tb = _target_bill(req.to_bill, src)
    lines = {x["seq"]: x for x in tb.lines}
    if mode == MODE_VOID:
        items = db.all(f"SELECT {ITEM_COLS} FROM sn_item WHERE bill_no = %s ORDER BY seq_pi_no, seq_dec", src)
        scope = "整单"
        mapping = {int(k): int(v) for k, v in (req.mapping or {}).items()}
        line_of: dict[int, dict] = {}
        for seq in sorted({r["bill_seq"] for r in items}):
            mat = next(r["material_code"] for r in items if r["bill_seq"] == seq)
            if seq in mapping:
                if mapping[seq] not in lines:
                    raise biz(ErrorCode.TR_TARGET_LINE_INVALID, seq=mapping[seq])
                line_of[seq] = lines[mapping[seq]]
            else:
                hit = _auto_line(tb, mat)
                if hit is None:
                    raise biz(ErrorCode.TR_TARGET_LINE_REQUIRED, seq=seq, material=mat or "(空)")
                line_of[seq] = hit
    elif mode == MODE_SPLIT:
        org = util.trim(req.factory_code) or None
        pi = util.trim(req.pi_no)
        if req.from_seq is None:
            raise biz(ErrorCode.RULE_LINE_REQUIRED)
        cond = " AND pi_no = %s" if pi else ""
        args: list = [pi] if pi else []
        if org:
            cond += " AND factory_code = %s"
            args.append(org)
        items = db.all(
            f"SELECT {ITEM_COLS} FROM sn_item WHERE bill_no = %s AND bill_seq = %s{cond} ORDER BY seq_pi_no, seq_dec",
            src,
            req.from_seq,
            *args,
        )
        if items and (req.start_sn or req.end_sn):
            items = _slice(items, util.trim(req.start_sn), util.trim(req.end_sn))
        if not items:
            raise biz(ErrorCode.TR_NOTHING)
        mat = items[0]["material_code"]
        if req.to_seq is not None:
            if req.to_seq not in lines:
                raise biz(ErrorCode.TR_TARGET_LINE_INVALID, seq=req.to_seq)
            target = lines[req.to_seq]
        else:
            target = _auto_line(tb, mat)
            if target is None:
                raise biz(ErrorCode.TR_TARGET_LINE_REQUIRED, seq=req.from_seq, material=mat or "(空)")
        line_of = {req.from_seq: target}
        scope = f"{org or '-'} / {pi or '-'} / 序号 {req.from_seq}" + (
            f" / {req.start_sn or '…'} ~ {req.end_sn or '…'}" if req.start_sn or req.end_sn else ""
        )
    else:
        raise biz(ErrorCode.VALIDATION, detail="mode")
    if not items:
        raise biz(ErrorCode.TR_NOTHING)
    targets: dict[int, Target] = {}
    for r in items:
        line = line_of[r["bill_seq"]]
        tlen = codec.normalize_length(line["length_raw"])
        if r["length_text"] is not None and tlen != r["length_text"]:
            raise biz(ErrorCode.TR_LENGTH_MISMATCH, target=tlen or "(空)", current=r["length_text"])
        # 已分配的号按目标单据的生产组织（相同时同组织改挂，不同时等同转组织）；待分配的仍待分配
        targets[r["id"]] = Target(
            None if r["factory_code"] is None else tb.org_code,
            tb.pi,
            tb.customer_code,
            tb.bill_no,
            line["seq"],
            line["material_code"],
        )
    return items, targets, tb, scope


def _slice(items: list[dict], start: str, end: str) -> list[dict]:
    by_sn = {r["sn"]: r for r in items}
    s = by_sn.get(start) if start else items[0]
    e = by_sn.get(end) if end else items[-1]
    if s is None or e is None or s["seq_pi_no"] != e["seq_pi_no"]:
        raise biz(ErrorCode.TR_RANGE_INVALID, start_sn=start or "", end_sn=end or "")
    lo, hi = min(s["seq_dec"], e["seq_dec"]), max(s["seq_dec"], e["seq_dec"])
    return [r for r in items if r["seq_pi_no"] == s["seq_pi_no"] and lo <= r["seq_dec"] <= hi]


def bill_preview(req: BillRequest) -> dict:
    """转单确认框：枚数、各状态枚数、不可转枚数及原因、来源和目标已生成数的变化（未生成额度不变）。"""
    items, targets, tb, scope = _bill_plan(req)
    bad = blocked(items, True)
    out = _summary(items, bad)
    moves: dict[tuple[str, int], int] = {}
    for r in items:
        if r["id"] in bad:
            continue
        t = targets[r["id"]]
        moves[(r["bill_no"], r["bill_seq"])] = moves.get((r["bill_no"], r["bill_seq"]), 0) - 1
        moves[(t.bill, t.seq)] = moves.get((t.bill, t.seq), 0) + 1
    counts, _ = orders.line_extras(list(moves))
    out["generated_changes"] = [
        {
            "bill_no": k[0],
            "seq": k[1],
            "before": 0 if counts.get(k) is None else int(counts[k]["gen_count"]),
            "after": (0 if counts.get(k) is None else int(counts[k]["gen_count"])) + d,
        }
        for k, d in sorted(moves.items())
    ]
    out["to_pi"] = tb.pi
    out["to_org"] = tb.org_code
    out["scope"] = scope
    out["other_org"] = sum(1 for r in items if r["factory_code"] is not None and r["factory_code"] != tb.org_code)
    return out


def transfer_bill(cu: CurrentUser, req: BillRequest) -> dict:
    """转单（总部直接执行）：生成转单单；需要时自动改挂推送、转出推送。"""
    reason = _reason(req.reason)
    items, _t, tb, _s = _bill_plan(req)
    pis = {r["pi_no"] for r in items} | {tb.pi}
    with locks.with_pi_lock(*pis), db.tx():
        items, targets, tb, scope = _bill_plan(req)
        ids = [r["id"] for r in items]
        items = db.all(
            f"SELECT {ITEM_COLS} FROM sn_item WHERE id IN ({marks(len(ids))}) ORDER BY seq_pi_no, seq_dec FOR UPDATE",
            *ids,
        )
        bad = blocked(items, True)
        ok = [x for x in items if x["id"] not in bad]
        _check_any(items, ok, bad)
        items = ok
        src = util.trim(req.from_bill)
        no = util.next_id("TB")
        tid = db.insert(
            "INSERT INTO sn_bill_transfer(transfer_no, mode, from_bill, to_bill, from_pi, to_pi, scope_text, qty, "
            "reason, created_by, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            no,
            util.trim(req.mode) or MODE_SPLIT,
            src,
            tb.bill_no,
            items[0]["pi_no"],
            tb.pi,
            util.cut(scope, 500),
            len(items),
            reason,
            cu.emp_no,
            util.now(),
        )
        moved = _move(cu, KIND_BILL, tid, no, items, targets)
        audit.record(
            cu,
            audit.entry(audit.TRANSFER_BILL)
            .factory(items[0]["factory_code"])
            .pi(items[0]["pi_no"])
            .bill(src)
            .count(len(items))
            .range(items[0]["sn"], items[-1]["sn"])
            .transfer(no)
            .why(reason)
            .info(
                f"to={tb.bill_no}/{tb.pi}/{tb.org_code} scope={scope} out_batches="
                + ",".join(b["batch_no"] for b in moved["out"])
            ),
        )
    _after_move(cu, moved)
    return bill_transfer_view(cu, tid)


# ================================================================== 转组织


@dataclass(frozen=True)
class OrgRequest:
    factory_code: str | None = None
    pi_no: str | None = None
    scope: str | None = None
    material_code: str | None = None
    sn: str | None = None
    to_factory: str | None = None
    to_pi: str | None = None
    to_customer: str | None = None
    to_material: str | None = None
    #: 手工填写或改过的目标字段（pi / customer / material），不与金蝶核对
    manual_fields: list[str] | None = None
    reason: str | None = None


def _org_items(factory: str, r: OrgRequest, lock: bool = False) -> tuple[list[dict], str, str]:
    pi = util.trim(r.pi_no)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    scope = util.trim(r.scope)
    if scope not in SCOPES:
        raise biz(ErrorCode.TR_SCOPE_INVALID)
    cond, args = "", []
    if scope == SCOPE_MATERIAL:
        if r.material_code is None:
            raise biz(ErrorCode.TR_MATERIAL_REQUIRED)
        cond, args = " AND material_code = %s", [r.material_code.strip()]
    elif scope == SCOPE_SINGLE:
        if util.blank(r.sn):
            raise biz(ErrorCode.TR_SN_REQUIRED)
        cond, args = " AND sn = %s", [r.sn.strip()]
    items = db.all(
        f"SELECT {ITEM_COLS} FROM sn_item WHERE factory_code = %s AND pi_no = %s{cond} ORDER BY seq_pi_no, seq_dec"
        + (" FOR UPDATE" if lock else ""),
        factory,
        pi,
        *args,
    )
    return items, pi, scope


def _org_targets(t: dict, items: list[dict]) -> dict[int, Target]:
    return {
        r["id"]: Target(
            t["to_factory"],
            t["to_pi"],
            t["to_customer"] or r["customer_code"],
            r["bill_no"],
            r["bill_seq"],
            t["to_material"] or r["material_code"],
        )
        for r in items
    }


def _check_org_target(r: OrgRequest, items: list[dict], from_factory: str, from_pi: str) -> dict:
    to_pi = util.trim(r.to_pi)
    if not to_pi:
        raise biz(ErrorCode.TR_TO_PI_REQUIRED)
    to_factory = factories.must_be_enabled(r.to_factory)["factory_code"]
    to_customer = util.trim(r.to_customer)
    to_material = util.trim(r.to_material)
    manual = sorted({x for x in (r.manual_fields or []) if x in ("pi", "customer", "material")})
    t = {"to_factory": to_factory, "to_pi": to_pi, "to_customer": to_customer, "to_material": to_material}
    # 目标与来源完全相同时拒绝
    if (
        to_factory == from_factory
        and to_pi == from_pi
        and all(
            (not to_customer or x["customer_code"] == to_customer)
            and (not to_material or x["material_code"] == to_material)
            for x in items
        )
    ):
        raise biz(ErrorCode.TR_SAME_TARGET)
    # 改了物料且能从快照取到目标长度时，与号上长度不同则拒绝；手工填写的物料不校验
    if to_material and "material" not in manual:
        lens = {
            codec.normalize_length(x)
            for x in db.column(
                "SELECT length_raw FROM sn_prd_mo WHERE pi = %s AND material_code = %s", to_pi, to_material
            )
        }
        if len(lens) == 1:
            tl = lens.pop()
            for x in items:
                if x["length_text"] is not None and x["length_text"] != tl:
                    raise biz(ErrorCode.TR_LENGTH_MISMATCH, target=tl or "(空)", current=x["length_text"])
    t["manual_fields"] = ",".join(manual)
    return t


def org_candidates(cu: CurrentUser, r: OrgRequest) -> dict:
    """转组织确认框：所选范围各状态枚数、可转枚数、不可转原因；物料清单。"""
    if cu.is_admin:
        factory = factories.must_get(r.factory_code)["factory_code"]
    elif cu.is_factory:
        factory = cu.write_factory(r.factory_code)
    else:
        facs = cu.read_factories(r.factory_code)
        if not facs or len(facs) != 1:
            raise biz(ErrorCode.FACTORY_NOT_FOUND, factory="")
        factory = facs[0]
    items, pi, _scope = _org_items(factory, r)
    bad = blocked(items, False)
    out = _summary(items, bad)
    out["materials"] = db.column(
        "SELECT DISTINCT material_code FROM sn_item WHERE factory_code = %s AND pi_no = %s ORDER BY material_code",
        factory,
        pi,
    )
    out["xo_qty"] = sum(
        1 for x in items if x["id"] not in bad and x["mes_holder"] == factory and util.trim(r.to_factory) != factory
    )
    return out


def apply(cu: CurrentUser, r: OrgRequest) -> dict:
    """生产组织操作员提交转组织申请：号变为申请中（不能领取、推送、拉取）；申请期间不通知 MES。"""
    if not cu.is_factory:
        raise biz(ErrorCode.FORBIDDEN)
    factory = cu.write_factory(r.factory_code)
    reason = _reason(r.reason)
    with db.tx():
        items, pi, scope = _org_items(factory, r, lock=True)
        t = _check_org_target(r, items, factory, pi)
        bad = blocked(items, False)
        ok = [x for x in items if x["id"] not in bad]
        _check_any(items, ok, bad, scope)
        tid, no = _insert_org(cu, MODE_APPLY, PENDING, factory, pi, scope, r, t, len(ok), reason)
        now = util.now()
        rows = []
        for x in ok:
            rows.append(
                (
                    KIND_ORG,
                    tid,
                    x["id"],
                    x["sn"],
                    x["seq_pi_no"],
                    x["seq_dec"],
                    x["status"],
                    factory,
                    x["pi_no"],
                    x["customer_code"],
                    x["bill_no"],
                    x["bill_seq"],
                    x["material_code"],
                    x["delivery_batch_no"],
                    x["acquire_batch_no"],
                    x["rev"],
                )
            )
        db.exec_many(
            "INSERT INTO sn_transfer_item(kind, transfer_id, item_id, sn, seq_pi_no, seq_dec, from_status, "
            "from_factory, "
            "from_pi, from_customer, from_bill, from_seq, from_material, from_delivery_batch, from_acquire_batch, "
            "from_rev) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            rows,
        )
        ids = [x["id"] for x in ok]
        for i in range(0, len(ids), 1000):
            part = ids[i : i + 1000]
            db.exec(
                f"UPDATE sn_item SET applying_from = status, status = 'APPLYING', transfer_id = %s, updated_at = %s "
                f"WHERE id IN ({marks(len(part))})",
                tid,
                now,
                *part,
            )
        changes.refresh_items({x["bill_no"] for x in ok})
        audit.record(cu, _org_entry(audit.TRANSFER_APPLY, no, factory, pi, scope, t, len(ok), reason, ok))
    return org_view(cu, tid)


def _insert_org(cu, mode, status, factory, pi, scope, r: OrgRequest, t: dict, qty: int, reason: str) -> tuple[int, str]:
    no = util.next_id("TO")
    tid = db.insert(
        "INSERT INTO sn_transfer(transfer_no, mode, status, scope, from_factory, from_pi, from_material, from_sn, qty, "
        "to_factory, to_pi, to_customer, to_material, manual_fields, reason, applied_by, applied_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        no,
        mode,
        status,
        scope,
        factory,
        pi,
        util.trim(r.material_code) if scope == SCOPE_MATERIAL else "",
        util.trim(r.sn) if scope == SCOPE_SINGLE else "",
        qty,
        t["to_factory"],
        t["to_pi"],
        t["to_customer"],
        t["to_material"],
        t["manual_fields"],
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
            f"scope={scope} to={t['to_factory']}/{t['to_pi']}/{t['to_customer'] or '(原客户)'}/"
            f"{t['to_material'] or '(原物料)'} manual={t.get('manual_fields') or '-'}"
        )
    )


def direct(cu: CurrentUser, r: OrgRequest) -> dict:
    """总部不经申请直接转移（须填原因）。"""
    if not cu.is_admin:
        raise biz(ErrorCode.FORBIDDEN)
    factory = factories.must_get(r.factory_code)["factory_code"]
    reason = _reason(r.reason)
    to_pi = util.trim(r.to_pi)
    with locks.with_pi_lock(util.trim(r.pi_no), to_pi), db.tx():
        items, pi, scope = _org_items(factory, r, lock=True)
        t = _check_org_target(r, items, factory, pi)
        bad = blocked(items, False)
        ok = [x for x in items if x["id"] not in bad]
        _check_any(items, ok, bad, scope)
        tid, no = _insert_org(cu, MODE_DIRECT, APPROVED, factory, pi, scope, r, t, len(ok), reason)
        db.exec("UPDATE sn_transfer SET decided_by = %s, decided_at = %s WHERE id = %s", cu.emp_no, util.now(), tid)
        moved = _move(cu, KIND_ORG, tid, no, ok, _org_targets(t, ok))
        e = _org_entry(audit.TRANSFER_DIRECT, no, factory, pi, scope, t, len(ok), reason, ok)
        audit.record(cu, e.info(f"{e.detail} out_batches={','.join(b['batch_no'] for b in moved['out'])}"))
    _after_move(cu, moved)
    return org_view(cu, tid)


def _lock_pending(tid: int) -> dict:
    t = db.one("SELECT * FROM sn_transfer WHERE id = %s FOR UPDATE", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    if t["status"] != PENDING:
        raise biz(ErrorCode.TR_NOT_PENDING, transfer_no=t["transfer_no"])
    return t


def approve(cu: CurrentUser, tid: int, note: str | None) -> dict:
    t0 = db.one("SELECT * FROM sn_transfer WHERE id = %s", tid)
    if t0 is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    with locks.with_pi_lock(t0["from_pi"], t0["to_pi"]), db.tx():
        t = _lock_pending(tid)
        f = factories.must_get(t["to_factory"])
        if not f["enabled"]:
            raise biz(ErrorCode.TR_TARGET_DISABLED, factory=f"{f['factory_name']}（{f['factory_code']}）")
        ids = db.column("SELECT item_id FROM sn_transfer_item WHERE kind = %s AND transfer_id = %s", KIND_ORG, tid)
        items = db.all(
            f"SELECT {ITEM_COLS}, applying_from FROM sn_item WHERE id IN ({marks(len(ids))}) AND status = 'APPLYING' "
            "AND transfer_id = %s ORDER BY seq_pi_no, seq_dec FOR UPDATE",
            *ids,
            tid,
        )
        if len(items) != len(ids):
            raise biz(ErrorCode.TR_CONCURRENT)
        # 申请前状态参与 5.2.8 判断
        for x in items:
            x["status"] = x["applying_from"]
        moved = _move(cu, KIND_ORG, tid, t["transfer_no"], items, _org_targets(t, items))
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s, decide_note = %s WHERE id = %s",
            APPROVED,
            cu.emp_no,
            util.now(),
            util.cut(util.trim_or_none(note), 500),
            tid,
        )
        audit.record(
            cu,
            _org_entry(
                audit.TRANSFER_APPROVE,
                t["transfer_no"],
                t["from_factory"],
                t["from_pi"],
                t["scope"],
                t,
                len(items),
                util.trim_or_none(note),
                items,
            ),
        )
    _after_move(cu, moved)
    return org_view(cu, tid)


def _restore(t: dict) -> None:
    """驳回 / 撤回：恢复为申请前状态。"""
    bills = set(
        db.column("SELECT DISTINCT bill_no FROM sn_item WHERE transfer_id = %s AND status = 'APPLYING'", t["id"])
    )
    db.exec(
        "UPDATE sn_item SET status = applying_from, applying_from = NULL, transfer_id = NULL, updated_at = %s "
        "WHERE transfer_id = %s AND status = 'APPLYING'",
        util.now(),
        t["id"],
    )
    changes.refresh_items(bills)


def reject(cu: CurrentUser, tid: int, note: str | None) -> dict:
    with db.tx():
        t = _lock_pending(tid)
        _restore(t)
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s, decide_note = %s WHERE id = %s",
            REJECTED,
            cu.emp_no,
            util.now(),
            util.cut(util.trim_or_none(note), 500),
            tid,
        )
        audit.record(
            cu,
            audit.entry(audit.TRANSFER_REJECT)
            .factory(t["from_factory"])
            .pi(t["from_pi"])
            .count(t["qty"])
            .transfer(t["transfer_no"])
            .why(util.trim_or_none(note))
            .status(util.APPLYING, "PREVIOUS"),
        )
    for no in _bills_of(tid):
        delivery.kick_bill(no)
    return org_view(cu, tid)


def withdraw(cu: CurrentUser, tid: int) -> dict:
    with db.tx():
        t = _lock_pending(tid)
        if not cu.is_factory or cu.factory_code != t["from_factory"]:
            raise biz(ErrorCode.TR_WITHDRAW_FORBIDDEN)
        _restore(t)
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s WHERE id = %s",
            WITHDRAWN,
            cu.emp_no,
            util.now(),
            tid,
        )
        audit.record(
            cu,
            audit.entry(audit.TRANSFER_WITHDRAW)
            .factory(t["from_factory"])
            .pi(t["from_pi"])
            .count(t["qty"])
            .transfer(t["transfer_no"])
            .status(util.APPLYING, "PREVIOUS"),
        )
    for no in _bills_of(tid):
        delivery.kick_bill(no)
    return org_view(cu, tid)


def _bills_of(tid: int) -> list[str]:
    return db.column(
        "SELECT DISTINCT from_bill FROM sn_transfer_item WHERE kind = %s AND transfer_id = %s", KIND_ORG, tid
    )


# ================================================================== 物料确认对应（F-06，订单变更修正，不是转单）


def map_material(cu: CurrentUser, alert: dict, target_seq: int | None, note: str | None) -> None:
    """把失去对应行的已生成数、本单生成数并入指定新行，号改挂到新行；已交付给 MES 的号 rev + 1 以改挂重推。
    涉及的号在未确认或失败的批次中时须等批次确认后再做。"""
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
        for x in items:
            delivered = x["mes_holder"] is not None
            status, push_type, batch = x["status"], x["push_type"], x["delivery_batch_no"]
            rev = int(x["rev"]) + (1 if delivered else 0)
            if delivered and x["mes_holder"] == x["factory_code"] and x["factory_code"] in mes:
                status, push_type, batch = util.TO_PUSH, util.PUSH_REASSIGN, None
                pushes.add((x["factory_code"], x["pi_no"]))
            db.exec(
                "UPDATE sn_item SET bill_seq = %s, material_code = %s, rev = %s, status = %s, push_type = %s, "
                "delivery_batch_no = %s, updated_at = %s WHERE id = %s",
                target_seq,
                line["material_code"],
                rev,
                status,
                push_type,
                batch,
                now,
                x["id"],
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
    changes.kick_resumed()
    for org, pi in sorted(pushes):
        delivery.start_task(cu, org, pi)


# ================================================================== 查询（F-17）


def _direction(cu: CurrentUser, from_f: str | None, to_f: str | None) -> str:
    if not cu.bound:
        return ""
    return "OUT" if from_f in cu.factories else "IN"


def list_org(
    cu: CurrentUser, status: str | None, factory: str | None, pi: str | None, bill: str | None, page: util.Page
) -> dict:
    where = " WHERE 1=1"
    args: list = []
    if not util.blank(pi):
        where += " AND (from_pi = %s OR to_pi = %s)"
        args += [pi.strip(), pi.strip()]
    if not util.blank(bill):
        where += " AND id IN (SELECT transfer_id FROM sn_transfer_item WHERE kind = 'ORG' AND from_bill = %s)"
        args.append(bill.strip())
    facs = cu.read_factories(factory)
    if facs is not None:
        fr, fa = factories.scope_sql(facs, "from_factory")
        to, ta = factories.scope_sql(facs, "to_factory")
        where += f" AND ((1=1{fr}) OR (1=1{to}))"
        args += [*fa, *ta]
    pending = db.count("SELECT COUNT(*) FROM sn_transfer" + where + " AND status = 'PENDING'", *args)
    if not util.blank(status):
        where += " AND status = %s"
        args.append(util.trim(status))
    total = db.count("SELECT COUNT(*) FROM sn_transfer" + where, *args)
    rows = db.all(f"SELECT * FROM sn_transfer{where} ORDER BY id DESC LIMIT %s,%s", *args, page.offset, page.size)
    disabled = factories.disabled_codes()
    out = util.page_result(
        [
            {
                "transfer": util.jrow(t, ORG_FIELDS),
                "direction": _direction(cu, t["from_factory"], t["to_factory"]),
                "to_factory_disabled": t["status"] == PENDING and t["to_factory"] in disabled,
            }
            for t in rows
        ],
        total,
        page,
    )
    out["pending"] = pending
    return out


def list_bill(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None, page: util.Page) -> dict:
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
        fsql, fa = factories.scope_sql(facs, "x.from_factory")
        tsql, ta = factories.scope_sql(facs, "x.to_factory")
        where += (
            " AND id IN (SELECT x.transfer_id FROM sn_transfer_item x WHERE x.kind = 'BILL' AND "
            f"((1=1{fsql}) OR (1=1{tsql})))"
        )
        args += [*fa, *ta]
    total = db.count("SELECT COUNT(*) FROM sn_bill_transfer" + where, *args)
    rows = db.all(f"SELECT * FROM sn_bill_transfer{where} ORDER BY id DESC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result([util.jrow(t, BILL_FIELDS) for t in rows], total, page)


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
    out["direction"] = _direction(cu, t["from_factory"], t["to_factory"])
    if t["status"] == APPROVED:
        out.update(_push_info(KIND_ORG, tid, t["transfer_no"]))
    return out


def bill_transfer_view(cu: CurrentUser, tid: int) -> dict:
    t = db.one("SELECT * FROM sn_bill_transfer WHERE id = %s", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
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
        "i.delivery_batch_no cur_batch FROM sn_transfer_item t LEFT JOIN sn_item i ON i.id = t.item_id "
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
    "转出前PI",
    "转出前单据",
    "转出前序号",
    "转出前物料",
    "原交付批次",
    "原领取批次",
    "转出批次",
    "转入生产组织",
]


def out_list(cu: CurrentUser, tid: int, fmt: str):
    """转出号清单：完整 SN、流水、原批次，来源现场据此作废已打出的旧标签（页面领取的号也列入）。"""
    t = org_view(cu, tid)

    def rows():
        last = 0
        no = 0
        while True:
            page = db.all(
                "SELECT t.*, i.rule_version_id FROM sn_transfer_item t LEFT JOIN sn_item i ON i.id = t.item_id "
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
                    r["from_pi"],
                    r["from_bill"],
                    r["from_seq"],
                    r["from_material"],
                    r["from_delivery_batch"],
                    r["from_acquire_batch"],
                    r["out_batch_no"],
                    r["to_factory"],
                ]

    return t, files.stream(fmt, t["transfer_no"], OUT_LIST_HEADERS, rows())
