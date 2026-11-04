"""删号（删除重建）：在一张 PI 下按单据、物料行，或单枚 / 一段 SN 删除号，之后可改参数、改数量重新生成。

- 范围：单据（可多张）、物料行（可多行），或 SN 起止号（只填起号 = 单枚；起止号须在同一物料行上，按流水取其间的号）。
  只删现在挂在这张 PI 下的号；号的状态不限（待分配、已分配、待推送、已推送都可删），普通操作员只能删授权组织的号。
- 号从号池移除（完整 SN 与流水腾出，之后可以重新生成同样的号，不考虑旧号），删除前的号与挂靠存入 sn_item_deleted 备查。
- PI 最大流水号不退回：删掉的号在流水里留下空号段，重新生成时可选用（见 generate 的流水来源 RANGE）。
- 计数：额度还给号现在挂的物料行（现住址）——该行已生成数、已分配数减去删掉的枚数，本单生成数（own_generated）
  减去删掉的枚数（不小于 0），可生成数量随之增加（不超过订单数量）。号的生成行不变。
  流水归属 PI 的生成枚数（sn_pi_counter.generated_qty）一并减去。
- 交付批次：号在未确认或失败的交付批次中时，删除后该批次作废，批次里其余的号按作废的规则回号池或当场重建批次
  （与人工作废同一逻辑）；已确认的批次不动。
- 与生成、分配、转移共用 PI 操作锁（号现在的 PI 与流水归属 PI 都锁）；PI 正在生成时不能删。
- 只删本系统生成的号：SN 导入的号只能分配，不在删号范围内（按单据、物料行删时略过，按 SN 删时视为不存在）。
"""

from __future__ import annotations

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, delivery, generate, locks, orders

#: 删号原因选填，不填记这个默认原因
DEFAULT_REASON = "生成页删除 SN"
CHUNK = 2000

_ARCHIVE_COLS = (
    "id, sn, factory_code, pi_no, customer_code, bill_no, bill_seq, material_code, rev, gen_org_code, gen_bill_no, "
    "gen_bill_seq, seq_pi_no, seq_dec, length_text, attach_no, rule_version_id, gen_batch, status, delivery_mode, "
    "delivery_batch_no, mes_holder, created_at, allocated_at, delivered_at"
)


def _target(
    pi: str,
    bills: list[str] | None,
    lines: list[dict] | None,
    start_sn: str | None,
    end_sn: str | None,
) -> tuple[str, list, str]:
    """范围 → （sn_item 的条件模板，参数，范围说明）。条件模板里列名前写 {t}（无别名为空、有别名为 i.）。"""
    a = util.trim(start_sn)
    if a:
        s = db.one("SELECT * FROM sn_item WHERE sn = %s AND pi_no = %s AND origin = 'GEN'", a, pi)
        if s is None:
            raise biz(ErrorCode.DELETE_SN_NOT_FOUND, pi=pi, sn=a)
        b = util.trim(end_sn) or a
        e = s if b == a else db.one("SELECT * FROM sn_item WHERE sn = %s AND pi_no = %s AND origin = 'GEN'", b, pi)
        if e is None:
            raise biz(ErrorCode.DELETE_SN_NOT_FOUND, pi=pi, sn=b)
        if (
            e["seq_pi_no"] != s["seq_pi_no"]
            or (e["bill_no"], e["bill_seq"]) != (s["bill_no"], s["bill_seq"])
            or e["seq_dec"] < s["seq_dec"]
        ):
            raise biz(ErrorCode.DELETE_RANGE_INVALID)
        return (
            "{t}pi_no = %s AND {t}origin = 'GEN' AND {t}bill_no = %s AND {t}bill_seq = %s AND {t}seq_pi_no = %s "
            "AND {t}seq_dec BETWEEN %s AND %s",
            [pi, s["bill_no"], s["bill_seq"], s["seq_pi_no"], s["seq_dec"], e["seq_dec"]],
            f"{s['bill_no']}/{s['bill_seq']} {a}~{b}",
        )
    nos = list(dict.fromkeys(util.trim(x) for x in bills or [] if util.trim(x)))
    keys = list(
        dict.fromkeys(
            (util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines or [] if util.trim(x.get("bill_no"))
        )
    )
    if not nos and not keys:
        raise biz(ErrorCode.DELETE_TARGET_INVALID)
    ors, args = [], [pi]
    if nos:
        ors.append(f"{{t}}bill_no IN ({marks(len(nos))})")
        args += nos
    if keys:
        ors.append("({t}bill_no, {t}bill_seq) IN (" + ",".join(["(%s,%s)"] * len(keys)) + ")")
        for k in keys:
            args += list(k)
    desc = " ".join(nos + [f"{k[0]}/{k[1]}" for k in keys])
    return "{t}pi_no = %s AND {t}origin = 'GEN' AND (" + " OR ".join(ors) + ")", args, desc


def _summary(tpl: str, args: list) -> dict:
    """范围内的号：枚数、各状态枚数、涉及的生产组织、物料行、要作废的批次、转入号（流水属别的 PI）枚数。"""
    where = tpl.format(t="")
    by_status = {
        r["status"]: int(r["n"])
        for r in db.all(f"SELECT status, COUNT(*) n FROM sn_item WHERE {where} GROUP BY status", *args)
    }
    lines = [
        {
            "bill_no": r["bill_no"],
            "seq": r["bill_seq"],
            "material_code": r["material_code"],
            "qty": int(r["n"]),
            "start_sn": r["a"],
            "end_sn": r["b"],
        }
        for r in db.all(
            f"SELECT bill_no, bill_seq, MIN(material_code) material_code, COUNT(*) n, MIN(sn) a, MAX(sn) b "
            f"FROM sn_item WHERE {where} GROUP BY bill_no, bill_seq ORDER BY bill_no, bill_seq",
            *args,
        )
    ]
    batches = db.column(
        "SELECT DISTINCT i.delivery_batch_no FROM sn_item i "
        "JOIN sn_delivery_batch b ON b.batch_no = i.delivery_batch_no "
        f"WHERE {tpl.format(t='i.')} AND i.status = 'TO_PUSH' AND b.result IN ('UNCONFIRMED','FAILED') "
        "ORDER BY i.delivery_batch_no",
        *args,
    )
    orgs = sorted(
        {r for r in db.column(f"SELECT DISTINCT factory_code FROM sn_item WHERE {where}", *args) if r}
        | orders.bill_orgs(
            db.column(f"SELECT DISTINCT bill_no FROM sn_item WHERE {where} AND factory_code IS NULL", *args)
        )
    )
    seq_pis = db.column(f"SELECT DISTINCT seq_pi_no FROM sn_item WHERE {where}", *args)
    return {
        "qty": sum(by_status.values()),
        "by_status": by_status,
        "orgs": [o for o in orgs if o != "-"],
        "lines": lines,
        "batches": batches,
        "seq_pis": sorted(seq_pis),
        "transferred_in": db.count(f"SELECT COUNT(*) FROM sn_item WHERE {where} AND seq_pi_no <> pi_no", *args),
    }


def preview(
    cu: CurrentUser,
    pi_in: str | None,
    bills: list[str] | None = None,
    lines: list[dict] | None = None,
    start_sn: str | None = None,
    end_sn: str | None = None,
) -> dict:
    """删号确认框：要删的枚数、各状态枚数、涉及的生产组织与物料行、要作废重建的交付批次；本 PI 当前的空号段。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    tpl, args, desc = _target(pi, bills, lines, start_sn, end_sn)
    s = _summary(tpl, args)
    if s["orgs"]:
        cu.check_write(*s["orgs"])
    return {"pi_no": pi, "scope_text": desc, **s, "gaps": generate.gaps_of(pi)}


def delete(
    cu: CurrentUser,
    pi_in: str | None,
    bills: list[str] | None = None,
    lines: list[dict] | None = None,
    start_sn: str | None = None,
    end_sn: str | None = None,
    reason_in: str | None = None,
) -> dict:
    """删号：见模块说明。在一个事务里完成；返回删除单号、枚数、各物料行枚数、作废与重建的批次、删后的空号段。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    reason = util.trim(reason_in) or DEFAULT_REASON
    if len(reason) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    tpl, args, desc = _target(pi, bills, lines, start_sn, end_sn)
    where = tpl.format(t="")
    seq_pis = set(db.column(f"SELECT DISTINCT seq_pi_no FROM sn_item WHERE {where}", *args))
    voided: list[tuple[dict, set[str]]] = []
    rebuilt: list[str] = []
    with locks.with_pi_lock(pi, *seq_pis), db.tx():
        held = {pi, *seq_pis}
        if db.exists(
            f"SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE running_pi IN ({marks(len(held))}))", *sorted(held)
        ):
            raise biz(ErrorCode.GEN_BUSY, pi="、".join(sorted(held)))
        s = _summary(tpl, args)
        if not s["qty"]:
            raise biz(ErrorCode.DELETE_NOTHING)
        if not set(s["seq_pis"]) <= held:
            raise biz(ErrorCode.CONFLICT_RETRY)
        if s["orgs"]:
            cu.check_write(*s["orgs"])
        # 先锁批次再锁号（与回执确认的加锁顺序一致）。加锁后再看一次结果：上面读出之后、加锁之前这一批可能刚收到
        # MES 回执确认（号已是已推送、MES 已持有），已确认的批次不作废
        batches = [
            b
            for b in (delivery.must_batch(no, lock=True) for no in s["batches"])
            if b["result"] in ("UNCONFIRMED", "FAILED")
        ]
        ids = db.column(f"SELECT id FROM sn_item WHERE {where} ORDER BY seq_pi_no, seq_dec FOR UPDATE", *args)
        now = util.now()
        del_no = util.next_id("D")
        per_line = db.all(
            f"SELECT bill_no, bill_seq, COUNT(*) n, SUM(status <> 'PENDING_ALLOC') a, MIN(sn) first_sn, "
            f"MAX(sn) last_sn, MIN(factory_code) f, COUNT(DISTINCT COALESCE(factory_code, '')) nf, "
            f"GROUP_CONCAT(DISTINCT status) st FROM sn_item WHERE {where} GROUP BY bill_no, bill_seq",
            *args,
        )
        per_pi = db.all(f"SELECT seq_pi_no, COUNT(*) n FROM sn_item WHERE {where} GROUP BY seq_pi_no", *args)
        for i in range(0, len(ids), CHUNK):
            part = ids[i : i + CHUNK]
            db.exec(
                f"INSERT INTO sn_item_deleted({_ARCHIVE_COLS}, del_no, deleted_by, deleted_at, reason) "
                f"SELECT {_ARCHIVE_COLS}, %s, %s, %s, %s FROM sn_item WHERE id IN ({marks(len(part))})",
                del_no,
                cu.emp_no,
                now,
                reason,
                *part,
            )
            db.exec(f"DELETE FROM sn_item WHERE id IN ({marks(len(part))})", *part)
        for r in per_line:
            n, a = int(r["n"]), int(r["a"] or 0)
            # 额度还给现住址：号现在挂的物料行
            db.exec(
                "UPDATE sn_line_count SET gen_count = GREATEST(gen_count - %s, 0), "
                "alloc_count = GREATEST(alloc_count - %s, 0), own_generated = GREATEST(own_generated - %s, 0), "
                "updated_at = %s WHERE bill_no = %s AND seq = %s",
                n,
                a,
                n,
                now,
                r["bill_no"],
                r["bill_seq"],
            )
        for r in per_pi:
            db.exec(
                "UPDATE sn_pi_counter SET generated_qty = GREATEST(generated_qty - %s, 0), updated_at = %s "
                "WHERE pi_no = %s",
                int(r["n"]),
                now,
                r["seq_pi_no"],
            )
        bills_hit = {r["bill_no"] for r in per_line}
        changes.refresh_items(bills_hit)
        for b in batches:
            nb, bill_set = delivery.void_in_tx(cu, b, util.cut(f"删除 SN（{del_no}）：{reason}", 500), del_no)
            voided.append((b, bill_set))
            rebuilt += [x["batch_no"] for x in nb]
        for r in sorted(per_line, key=lambda x: (x["bill_no"], x["bill_seq"])):
            st = r["st"] or ""
            audit.record(
                cu,
                audit.entry(audit.SN_DELETE)
                .factory(r["f"] if int(r["nf"]) == 1 else None)
                .pi(pi)
                .bill(r["bill_no"])
                .range(r["first_sn"], r["last_sn"])
                .count(int(r["n"]))
                .status(st if "," not in st else None, None)
                .batch(del_no)
                .why(reason)
                .info(f"seq={r['bill_seq']} statuses={st} scope={util.cut(desc, 200)}"),
            )
    for b, bill_set in voided:
        delivery.after_void(cu, b, bill_set)
    return {
        "del_no": del_no,
        "pi_no": pi,
        "qty": len(ids),
        "lines": [
            {"bill_no": r["bill_no"], "seq": r["bill_seq"], "qty": int(r["n"])}
            for r in sorted(per_line, key=lambda x: (x["bill_no"], x["bill_seq"]))
        ],
        "voided": [b["batch_no"] for b, _ in voided],
        "rebuilt": rebuilt,
        "gaps": generate.gaps_of(pi),
    }


def deleted(pi_in: str | None, limit: int = 200) -> list[dict]:
    """删号记录（新的在前）：每次删除一行，删除单号、枚数、起止号、原因、操作人与时间。"""
    pi = util.trim(pi_in)
    rows = db.all(
        "SELECT del_no, MIN(sn) start_sn, MAX(sn) end_sn, COUNT(*) qty, MIN(deleted_by) deleted_by, "
        "MIN(deleted_at) deleted_at, MIN(reason) reason, GROUP_CONCAT(DISTINCT bill_no ORDER BY bill_no) bills "
        "FROM sn_item_deleted WHERE pi_no = %s GROUP BY del_no ORDER BY MIN(deleted_at) DESC, del_no DESC LIMIT %s",
        pi,
        min(max(limit, 1), 500),
    )
    return [
        {
            "del_no": r["del_no"],
            "start_sn": r["start_sn"],
            "end_sn": r["end_sn"],
            "qty": int(r["qty"]),
            "bills": (r["bills"] or "").split(","),
            "deleted_by": r["deleted_by"],
            "deleted_at": util.fmt(r["deleted_at"]),
            "reason": r["reason"],
        }
        for r in rows
    ]
