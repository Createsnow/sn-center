"""金蝶生产订单快照（F-05、F-07）：已审核单据的每条物料行一行，物料行 = 单据编号 + 序号（金蝶明细行号）。

同步方式：
1. 比对同步（日常，默认每 10 分钟）：只拉已审核单据的编号与修改时间，与快照比对：新出现、修改时间不同的单据
   整单替换，快照有、金蝶没有的移出；每张变动的单据随即做订单变更比对（changes）；同一周期对反审核中的
   已出号单据按单号复查。任一页拉取失败（重试后）本周期不改快照。
2. 按单据编号同步：不带单据状态条件查询，只看单据状态。
3. 进页同步：进入订单页时在后台触发一次比对同步；正有同步在运行时跳过。
4. 全量重建：完整拉取后写入新表、原子换表（RENAME），失败旧快照完整保留；完成后对全部已出号单据比对。
各种同步共用一把同步锁：按单据同步排队，比对同步和进页同步直接跳过。
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from decimal import Decimal

import structlog

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, factories, k3, locks

log = structlog.get_logger()

PRD_MO_COLS = (
    "bill_no, seq, doc_status, customer_code, pi, po, org_code, org_name, attach_no, material_code, material_name, "
    "length_raw, qty, modify_time, synced_at"
)
_INSERT = f"INSERT INTO {{table}}({PRD_MO_COLS}) VALUES ({marks(15)})"


# ================================================================== 写快照


def _args(rows: list[k3.Row], ts) -> list[tuple]:
    out = []
    seen: set[tuple[str, int]] = set()
    for r in rows:
        key = (r.bill_no, r.seq)
        if key in seen:
            continue
        seen.add(key)
        out.append(
            (
                r.bill_no,
                r.seq,
                r.doc_status or k3.APPROVED,
                util.cut(r.customer_code, 64),
                util.cut(r.pi, 64),
                util.cut(r.po, 128),
                util.cut(r.org_code, 64),
                util.cut(r.org_name, 128),
                util.cut(r.attach_no, 64),
                util.cut(r.material_code, 64),
                util.cut(r.material_name, 255),
                util.cut(r.length_raw, 64),
                r.qty,
                r.modify_time,
                ts,
            )
        )
    return out


def _insert(table: str, args: list[tuple]) -> None:
    sql = _INSERT.format(table=table)
    for i in range(0, len(args), 1000):
        db.exec_many(sql, args[i : i + 1000])


def _replace_bills(bills: dict[str, list[k3.Row]], ts) -> None:
    """同一事务内先删该单全部行再插入（金蝶删掉的物料行随之消失）。"""
    for no, rows in bills.items():
        db.exec("DELETE FROM sn_prd_mo WHERE bill_no = %s", no)
        _insert("sn_prd_mo", _args(rows, ts))


def _remove_bills(nos: list[str]) -> None:
    for i in range(0, len(nos), 500):
        part = nos[i : i + 500]
        db.exec(f"DELETE FROM sn_prd_mo WHERE bill_no IN ({marks(len(part))})", *part)


def _local_heads() -> dict[str, object]:
    return {r["bill_no"]: r["t"] for r in db.all("SELECT bill_no, MAX(modify_time) t FROM sn_prd_mo GROUP BY bill_no")}


def _group(rows: list[k3.Row]) -> dict[str, list[k3.Row]]:
    out: dict[str, list[k3.Row]] = {}
    for r in rows:
        out.setdefault(r.bill_no, []).append(r)
    return out


# ================================================================== 比对同步


def compare_sync(cu: CurrentUser, trigger: str = "SCHEDULED") -> dict | None:
    """比对同步；拿不到同步锁（已有同步在运行）返回 None。trigger = SCHEDULED / PAGE / MANUAL。"""
    held = locks.sync_lock(wait=False)
    if held is None:
        return None
    try:
        return _compare(cu, trigger)
    finally:
        held.release()


def _compare(cu: CurrentUser, trigger: str) -> dict:
    ts = util.now()
    try:
        heads = k3.fetch_approved_heads()
        remote = {h.bill_no: h.modify_time for h in heads}
        local = _local_heads()
        changed = [no for no, t in remote.items() if no not in local or t is None or local[no] != t]
        removed = [no for no in local if no not in remote]
        fetched = _group(k3.fetch_bills(changed)) if changed else {}
    except BizError as e:
        _finish(ts, False, e.message, None, None)
        audit.record_isolated(cu, audit.entry(audit.ORDER_SYNC).fail(e.message).info(f"mode=compare trigger={trigger}"))
        raise
    # 有修改时间、却按单号拉不到明细（期间被反审核）：当作移出
    removed += [no for no in changed if no not in fetched and no in local]
    rows = sum(len(v) for v in fetched.values())
    with db.tx():
        _replace_bills(fetched, ts)
        _remove_bills(removed)
    notes = changes.after_sync(cu, list(fetched), removed)
    # 反审核中的已出号单据按单号复查（发现重新审核或删除）
    notes += changes.recheck_reversed(cu, set(remote))
    n_bills = len(fetched) + len(removed)
    _finish(ts, True, "", n_bills, rows)
    if n_bills:
        audit.record_isolated(
            cu,
            audit.entry(audit.ORDER_SYNC)
            .count(n_bills)
            .info(f"mode=compare trigger={trigger} replaced={len(fetched)} removed={len(removed)} rows={rows}"),
        )
    else:
        changes.count_daily("ORDER_SYNC_NOCHANGE")
    return {
        "mode": "compare",
        "bills": n_bills,
        "replaced": len(fetched),
        "removed": len(removed),
        "rows": rows,
        "synced_at": util.fmt(ts),
        "attach_k3_filled": notes.count("ATTACH"),
    }


def _finish(ts, ok: bool, message: str, bills: int | None, rows: int | None) -> None:
    if ok:
        db.exec(
            "UPDATE sn_sync_state SET last_run_at = %s, last_ok = 1, last_message = '', last_bills = %s, "
            "last_rows = %s WHERE id = 1",
            ts,
            bills,
            rows,
        )
    else:
        db.exec(
            "UPDATE sn_sync_state SET last_run_at = %s, last_ok = 0, last_message = %s WHERE id = 1",
            ts,
            util.cut(message, 500),
        )


def run_scheduled() -> None:
    """后台定时：每个周期一次（多实例靠同步锁只跑一份）。"""
    try:
        compare_sync(CurrentUser.system(), "SCHEDULED")
    except BizError as e:
        log.warning("compare_sync_failed", err=e.message)


_page_thread_lock = threading.Lock()


def page_entry_sync(cu: CurrentUser) -> bool:
    """进页同步：后台触发一次比对同步，立即返回；正有同步在运行时跳过（返回 False）。"""
    if not _page_thread_lock.acquire(blocking=False):
        return False

    def run() -> None:
        try:
            compare_sync(cu, "PAGE")
        except Exception as e:  # noqa: BLE001
            log.warning("page_sync_failed", err=str(e))
        finally:
            _page_thread_lock.release()

    threading.Thread(target=run, name="sn-page-sync", daemon=True).start()
    return True


# ================================================================== 按单据同步


def sync_bill(cu: CurrentUser, bill_no: str | None) -> dict:
    """不带单据状态条件按单号查询：已审核替换该单；不是已审核移出；查无此单移出。"""
    no = util.trim(bill_no)
    if not no:
        raise biz(ErrorCode.ORDER_BILL_REQUIRED)
    held = locks.sync_lock(wait=True)
    if held is None:
        raise biz(ErrorCode.ORDER_SYNC_BUSY)
    try:
        v = k3.view_bill(no)
        ts = util.now()
        had = db.exists("SELECT EXISTS(SELECT 1 FROM sn_prd_mo WHERE bill_no = %s)", no)
        if v.found and v.doc_status == k3.APPROVED:
            with db.tx():
                _replace_bills({no: v.rows}, ts)
                audit.record(cu, audit.entry(audit.ORDER_SYNC).bill(no).count(1).info(f"mode=bill rows={len(v.rows)}"))
            changes.after_sync(cu, [no], [])
            outcome = "REPLACED"
        else:
            with db.tx():
                _remove_bills([no])
                audit.record(
                    cu,
                    audit.entry(audit.ORDER_SYNC)
                    .bill(no)
                    .count(1 if had else 0)
                    .info(f"mode=bill found={v.found} status={v.doc_status or '-'}"),
                )
            changes.after_removed(cu, no, v)
            outcome = ("NOT_APPROVED" if had else "NOT_APPROVED_NEW") if v.found else "NOT_FOUND"
    finally:
        held.release()
    return {
        "mode": "bill",
        "bill_no": no,
        "outcome": outcome,
        "doc_status": v.doc_status,
        "rows": len(v.rows) if outcome == "REPLACED" else 0,
        "synced_at": util.fmt(ts),
    }


# ================================================================== 全量重建


def rebuild(cu: CurrentUser) -> dict:
    """全量重建：完整拉取 → 写入新表 → 原子换表；失败整体回滚，旧快照完整保留。"""
    held = locks.sync_lock(wait=True)
    if held is None:
        raise biz(ErrorCode.ORDER_SYNC_BUSY)
    try:
        rows = k3.fetch_all()
        try:
            factories.sync_from_k3(cu)
        except Exception as e:  # noqa: BLE001
            # 生产组织同步失败不影响订单快照
            log.warning("factory_sync_during_rebuild_failed", err=str(e))
        ts = util.now()
        with db.connection() as conn, conn.cursor() as c:
            c.execute("DROP TABLE IF EXISTS sn_prd_mo_new")
            c.execute("DROP TABLE IF EXISTS sn_prd_mo_old")
            c.execute("CREATE TABLE sn_prd_mo_new LIKE sn_prd_mo")
        try:
            with db.tx():
                _insert("sn_prd_mo_new", _args(rows, ts))
            with db.connection() as conn, conn.cursor() as c:
                c.execute("RENAME TABLE sn_prd_mo TO sn_prd_mo_old, sn_prd_mo_new TO sn_prd_mo")
                c.execute("DROP TABLE sn_prd_mo_old")
        except Exception:
            with db.connection() as conn, conn.cursor() as c:
                c.execute("DROP TABLE IF EXISTS sn_prd_mo_new")
            raise
        bills = len({r.bill_no for r in rows})
        db.exec("UPDATE sn_sync_state SET last_full_at = %s WHERE id = 1", ts)
        audit.record_isolated(cu, audit.entry(audit.ORDER_SYNC).count(bills).info(f"mode=rebuild rows={len(rows)}"))
        changes.after_rebuild(cu)
    finally:
        held.release()
    return {"mode": "rebuild", "bills": bills, "rows": len(rows), "synced_at": util.fmt(ts)}


# ================================================================== 附件项次、长度（金蝶缺失时在生成页填写）


def edit_lock(count: dict | None) -> str | None:
    """附件项次、长度能否在页面修改：物料行还没有号时可改；已生成、未分配（GENERATED）不能改，撤销生成后可改；
    已分配（ALLOCATED）不能再改。不影响转单、拆单、转组织。"""
    if count is None:
        return None
    if int(count["alloc_count"]) > 0:
        return "ALLOCATED"
    if int(count["gen_count"]) > 0 or int(count["own_generated"]) > 0:
        return "GENERATED"
    return None


def _check_editable(no: str, seq: int) -> None:
    lock = edit_lock(db.one("SELECT * FROM sn_line_count WHERE bill_no = %s AND seq = %s", no, seq))
    if lock == "ALLOCATED":
        raise biz(ErrorCode.LINE_EDIT_ALLOCATED, bill_no=no, seq=seq)
    if lock == "GENERATED":
        raise biz(ErrorCode.LINE_EDIT_GENERATED, bill_no=no, seq=seq)


def _must_line(no: str, seq: int | None) -> dict:
    line = db.one("SELECT * FROM sn_prd_mo WHERE bill_no = %s AND seq = %s", no, seq)
    if line is None:
        raise biz(ErrorCode.ORDER_LINE_NOT_FOUND, bill_no=no, seq=seq)
    return line


def effective_attach(line: dict, fill: dict | None) -> tuple[str, str]:
    """附件项次的生效值与来源：K3 金蝶值 / MANUAL 页面填写 / EMPTY 待填写（必填，不能生成）。"""
    k3v = util.trim(line.get("attach_no"))
    if k3v:
        return k3v, "K3"
    if fill is not None and fill["material_code"] == (line.get("material_code") or ""):
        return fill["manual_value"], "MANUAL"
    return "", "EMPTY"


def fill_attach(cu: CurrentUser, bill_no: str | None, seq: int | None, value: str | None) -> dict:
    """填写附件项次（金蝶为空时，必填）；物料行有号后不能改。值不写回金蝶，也不进推送报文和领取文件。"""
    no = util.trim(bill_no)
    v = util.trim(value)
    if not v or len(v) > 64:
        raise biz(ErrorCode.ATTACH_INVALID)
    line = _must_line(no, seq)
    with locks.with_pi_lock(line["pi"]), db.tx():
        line = _must_line(no, seq)
        if util.trim(line["attach_no"]):
            raise biz(ErrorCode.ATTACH_FILL_K3)
        _check_editable(no, line["seq"])
        prev = db.one("SELECT * FROM sn_line_attach WHERE bill_no = %s AND seq = %s FOR UPDATE", no, seq)
        now = util.now()
        db.exec(
            "INSERT INTO sn_line_attach(bill_no, seq, manual_value, material_code, k3_value, filled_by, filled_at, "
            "updated_at) VALUES (%s,%s,%s,%s,'',%s,%s,%s) ON DUPLICATE KEY UPDATE manual_value = VALUES(manual_value), "
            "material_code = VALUES(material_code), k3_value = '', filled_by = VALUES(filled_by), "
            "filled_at = VALUES(filled_at), updated_at = VALUES(updated_at)",
            no,
            seq,
            v,
            line["material_code"],
            cu.emp_no,
            now,
            now,
        )
        audit.record(
            cu,
            audit.entry(audit.ORDER_ATTACH_FILL)
            .bill(no)
            .pi(line["pi"])
            .factory(line["org_code"])
            .material(line["material_code"])
            .info(f"seq={seq} {'' if prev is None else prev['manual_value']} -> {v}"),
        )
    counts, fills, lens = line_extras([(no, seq)])
    return line_view(line, counts.get((no, seq)), fills.get((no, seq)), lfill=lens.get((no, seq)))


def effective_length(line: dict, fill: dict | None) -> tuple[str, str]:
    """长度（字符串，原样使用）的生效值与来源：K3 金蝶值 / MANUAL 页面填写（金蝶为空时）/
    DEFAULT 金蝶为空且未填写时取默认长度 000。"""
    k3v = codec.clean_length(line.get("length_raw"))
    if k3v is not None:
        return k3v, "K3"
    if fill is not None and fill["material_code"] == (line.get("material_code") or ""):
        return fill["manual_value"], "MANUAL"
    return codec.DEFAULT_LENGTH, "DEFAULT"


def length_of(line: dict) -> str:
    """单条物料行的生效长度（含页面填写）。"""
    fill = db.one("SELECT * FROM sn_line_length WHERE bill_no = %s AND seq = %s", line["bill_no"], line["seq"])
    return effective_length(line, fill)[0]


def _length_value(value: str | None) -> str | None:
    """填写的长度：字符串原样保存（去首尾空格），不超过 64 字；留空返回 None（改回默认长度 000）。"""
    v = codec.clean_length(value)
    if v is not None and len(v) > 64:
        raise biz(ErrorCode.LENGTH_FILL_INVALID)
    return v


_LENGTH_UPSERT = (
    "INSERT INTO sn_line_length(bill_no, seq, manual_value, material_code, filled_by, filled_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE manual_value = VALUES(manual_value), "
    "material_code = VALUES(material_code), filled_by = VALUES(filled_by), filled_at = VALUES(filled_at), "
    "updated_at = VALUES(updated_at)"
)


def fill_length(cu: CurrentUser, bill_no: str | None, seq: int | None, value: str | None) -> dict:
    """填写长度（金蝶为空时，选填）：留空即用默认长度 000；物料行有号后不能改。"""
    no = util.trim(bill_no)
    v = _length_value(value)
    line = _must_line(no, seq)
    with locks.with_pi_lock(line["pi"]), db.tx():
        line = _must_line(no, seq)
        if codec.clean_length(line["length_raw"]) is not None:
            raise biz(ErrorCode.LENGTH_FILL_K3)
        _check_editable(no, line["seq"])
        prev = db.one("SELECT * FROM sn_line_length WHERE bill_no = %s AND seq = %s FOR UPDATE", no, seq)
        now = util.now()
        if v is None:
            db.exec("DELETE FROM sn_line_length WHERE bill_no = %s AND seq = %s", no, seq)
        else:
            db.exec(_LENGTH_UPSERT, no, seq, v, line["material_code"], cu.emp_no, now, now)
        if (None if prev is None else prev["manual_value"]) != v:
            audit.record(
                cu,
                audit.entry(audit.ORDER_LENGTH_FILL)
                .bill(no)
                .pi(line["pi"])
                .factory(line["org_code"])
                .material(line["material_code"])
                .info(f"seq={seq} {'' if prev is None else prev['manual_value']} -> {v or codec.DEFAULT_LENGTH}"),
            )
    counts, fills, lens = line_extras([(no, seq)])
    return line_view(line, counts.get((no, seq)), fills.get((no, seq)), lfill=lens.get((no, seq)))


def fill_lengths(
    cu: CurrentUser, pi_in: str | None, bills_in: list[str] | None, lines_in: list[dict] | None, value: str | None
) -> dict:
    """统一设置长度：范围（点选的物料行 / 勾选的单据 / 都不填 = 整张 PI）内长度还是默认 000（金蝶为空、未填写）
    且还没有号的物料行一次设为同一个值；已填写的、有号的不改。返回设置的行数。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    v = _length_value(value)
    if v is None:
        raise biz(ErrorCode.LENGTH_FILL_INVALID)
    keys = {(util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines_in or []}
    bills = {util.trim(b) for b in bills_in or [] if util.trim(b)}
    with locks.with_pi_lock(pi), db.tx():
        rows = [
            r
            for r in db.all("SELECT * FROM sn_prd_mo WHERE pi = %s ORDER BY bill_no, seq", pi)
            if ((r["bill_no"], r["seq"]) in keys if keys else r["bill_no"] in bills if bills else True)
        ]
        counts, _, lens = line_extras([(r["bill_no"], r["seq"]) for r in rows])
        todo = [
            r
            for r in rows
            if effective_length(r, lens.get((r["bill_no"], r["seq"])))[1] == "DEFAULT"
            and edit_lock(counts.get((r["bill_no"], r["seq"]))) is None
        ]
        if todo:
            now = util.now()
            db.exec_many(
                _LENGTH_UPSERT, [(r["bill_no"], r["seq"], v, r["material_code"], cu.emp_no, now, now) for r in todo]
            )
            audit.record(
                cu,
                audit.entry(audit.ORDER_LENGTH_FILL)
                .pi(pi)
                .count(len(todo))
                .info(f"batch value={v} " + ",".join(f"{r['bill_no']}/{r['seq']}" for r in todo)),
            )
    return {"pi_no": pi, "value": v, "filled": len(todo)}


# ================================================================== 查询


def _scope(cu: CurrentUser, factory: str | None, col: str = "m.org_code") -> tuple[str, list]:
    facs = cu.read_factories(factory)
    return factories.scope_sql(facs, col)


def _bill_filter(cu: CurrentUser, q: str | None, customer: str | None, pi: str | None, factory: str | None):
    """订单页左表筛选（m = sn_prd_mo）：客户编码为空的单据照常显示；绑定的账户只看本组织。"""
    where = " WHERE 1=1"
    args: list = []
    text = util.trim(q)
    if text:
        where += (
            " AND m.bill_no IN (SELECT x.bill_no FROM sn_prd_mo x WHERE x.bill_no LIKE %s OR x.pi LIKE %s"
            " OR x.po LIKE %s OR x.material_code LIKE %s OR x.customer_code LIKE %s)"
        )
        like = util.like_any(text)
        args += [like] * 5
    if not util.blank(customer):
        where += " AND m.customer_code = %s"
        args.append(util.trim(customer))
    if not util.blank(pi):
        where += " AND m.pi = %s"
        args.append(util.trim(pi))
    fsql, fargs = _scope(cu, factory)
    return where + fsql, args + fargs


def list_bills(
    cu: CurrentUser, q: str | None, customer: str | None, pi: str | None, factory: str | None, page: util.Page
) -> dict:
    """左表：每张单据一行，单据编号大的在前；订单数量、已生成、可生成（额度）按物料行汇总。"""
    where, args = _bill_filter(cu, q, customer, pi, factory)
    total = db.count("SELECT COUNT(DISTINCT m.bill_no) FROM sn_prd_mo m" + where, *args)
    rows = db.all(
        "SELECT m.bill_no, MAX(m.customer_code) customer_code, MAX(m.po) po, MAX(m.pi) pi, MAX(m.org_code) org_code, "
        "MAX(m.org_name) org_name, SUM(FLOOR(m.qty)) total_qty, COUNT(*) line_count, "
        "SUM(COALESCE(c.gen_count, 0)) gen_sum, "
        "SUM(GREATEST(FLOOR(m.qty) - COALESCE(c.own_generated, 0), 0)) quota "
        "FROM sn_prd_mo m LEFT JOIN sn_line_count c ON c.bill_no = m.bill_no AND c.seq = m.seq"
        + where
        + " GROUP BY m.bill_no ORDER BY m.bill_no DESC LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    states = changes.bill_states([r["bill_no"] for r in rows])
    items = [
        {
            "bill_no": r["bill_no"],
            "customer_code": r["customer_code"],
            "po": r["po"],
            "pi": r["pi"],
            "org_code": r["org_code"],
            "org_name": r["org_name"],
            "total_qty": int(r["total_qty"] or 0),
            "line_count": int(r["line_count"]),
            "generated_qty": int(r["gen_sum"] or 0),
            "quota": int(r["quota"] or 0),
            **states.get(r["bill_no"], changes.EMPTY_STATE),
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def line_view(
    r: dict, count: dict | None = None, fill: dict | None = None, rule: dict | None = None, lfill: dict | None = None
) -> dict:
    qty = sn_qty(r)
    own = 0 if count is None else int(count["own_generated"])
    attach, attach_src = effective_attach(r, fill)
    length, length_src = effective_length(r, lfill)
    return {
        "bill_no": r["bill_no"],
        "seq": r["seq"],
        "customer_code": r["customer_code"],
        "pi": r["pi"],
        "po": r["po"],
        "org_code": r["org_code"],
        "org_name": r["org_name"],
        "attach_no": attach,
        "attach_source": attach_src,
        "attach_k3": r["attach_no"],
        "attach_manual": None if fill is None else fill["manual_value"],
        "attach_k3_conflict": fill is not None and bool(r["attach_no"]) and r["attach_no"] != fill["manual_value"],
        "material_code": r["material_code"],
        "material_name": r["material_name"],
        "length_raw": r["length_raw"],
        "length": length,
        "length_source": length_src,
        "length_manual": None if lfill is None else lfill["manual_value"],
        "qty": qty,
        "generated_qty": 0 if count is None else int(count["gen_count"]),
        "own_generated_qty": own,
        "allocated_qty": 0 if count is None else int(count["alloc_count"]),
        "edit_lock": edit_lock(count),
        "quota": max(0, qty - own),
        "rule": rule,
        "synced_at": util.fmt(r["synced_at"]),
    }


def sn_qty(line: dict) -> int:
    """物料行数量：有小数时按整数部分计。"""
    q = line.get("qty")
    return 0 if q is None else max(0, int(Decimal(q)))


def line_extras(keys: list[tuple[str, int]]) -> tuple[dict, dict, dict]:
    """物料行计数、页面填写的附件项次与长度：(bill, seq) → 行。"""
    if not keys:
        return {}, {}, {}
    bills = sorted({k[0] for k in keys})
    counts = {
        (c["bill_no"], c["seq"]): c
        for c in db.all(f"SELECT * FROM sn_line_count WHERE bill_no IN ({marks(len(bills))})", *bills)
    }
    fills = {
        (f["bill_no"], f["seq"]): f
        for f in db.all(f"SELECT * FROM sn_line_attach WHERE bill_no IN ({marks(len(bills))})", *bills)
    }
    lens = {
        (f["bill_no"], f["seq"]): f
        for f in db.all(f"SELECT * FROM sn_line_length WHERE bill_no IN ({marks(len(bills))})", *bills)
    }
    return counts, fills, lens


def lines(bill_no: str | None) -> list[dict]:
    return db.all("SELECT * FROM sn_prd_mo WHERE bill_no = %s ORDER BY seq ASC", util.trim(bill_no))


def lines_of(cu: CurrentUser, bill_no: str | None) -> dict:
    """右表：该单表头与各物料行（序号、附件项次、物料、长度、数量、已生成、可生成、SN 规则）。"""
    from app.services import rules

    ls = lines(bill_no)
    if not ls:
        raise biz(ErrorCode.ORDER_NOT_FOUND, bill_no=util.trim(bill_no))
    cu.check_read(ls[0]["org_code"])
    keys = [(r["bill_no"], r["seq"]) for r in ls]
    counts, fills, lens = line_extras(keys)
    rmap = rules.line_rules_brief(ls[0]["pi"], keys)
    h = ls[0]
    return {
        "bill_no": h["bill_no"],
        "pi": h["pi"],
        "po": h["po"],
        "customer_code": h["customer_code"],
        "org_code": h["org_code"],
        "org_name": h["org_name"],
        "state": changes.bill_states([h["bill_no"]]).get(h["bill_no"], changes.EMPTY_STATE),
        "lines": [
            line_view(r, counts.get(k), fills.get(k), rmap.get(k), lens.get(k)) for r, k in zip(ls, keys, strict=True)
        ],
    }


def meta(cu: CurrentUser) -> dict:
    """顶部：最近同步时间、快照单据数和物料行数、客户为空的单据数（照常显示、照常流转，只作提示）、附件项次待填写行数、
    比对同步状态、未处理提醒数。"""
    fsql, fargs = _scope(cu, None)
    m = db.one(
        "SELECT MAX(m.synced_at) ts, COUNT(DISTINCT m.bill_no) bills, "
        "COUNT(*) row_count, COUNT(DISTINCT CASE WHEN m.customer_code = '' THEN m.bill_no END) empty_customer "
        "FROM sn_prd_mo m WHERE 1=1" + fsql,
        *fargs,
    )
    pending_attach = db.count(
        "SELECT COUNT(*) FROM sn_prd_mo m LEFT JOIN sn_line_attach a ON a.bill_no = m.bill_no AND a.seq = m.seq "
        "WHERE m.attach_no = '' AND (a.bill_no IS NULL OR a.material_code <> m.material_code)" + fsql,
        *fargs,
    )
    st = db.one("SELECT * FROM sn_sync_state WHERE id = 1") or {}
    return {
        "synced_at": util.fmt(m["ts"]),
        "bills": int(m["bills"] or 0),
        "rows": int(m["row_count"] or 0),
        "empty_customer_bills": int(m["empty_customer"] or 0),
        "attach_pending": pending_attach,
        "compare": {
            "last_run_at": util.fmt(st.get("last_run_at")),
            "last_ok": None if st.get("last_ok") is None else bool(st["last_ok"]),
            "last_message": st.get("last_message") or "",
            "last_bills": st.get("last_bills"),
            "last_full_at": util.fmt(st.get("last_full_at")),
        },
        "open_alerts": changes.open_alert_count(cu),
    }


def customers(cu: CurrentUser, q: str | None) -> list[str]:
    """快照中出现过的客户编码；绑定的账户只看本组织订单的客户。"""
    sql = "SELECT DISTINCT m.customer_code FROM sn_prd_mo m WHERE m.customer_code <> ''"
    args: list = []
    text = util.trim(q)
    if text:
        sql += " AND m.customer_code LIKE %s"
        args.append(util.like_any(text))
    fsql, fargs = _scope(cu, None)
    return db.column(sql + fsql + " ORDER BY m.customer_code LIMIT 200", *args, *fargs)


# ================================================================== 其他服务用的订单视图


@dataclass(frozen=True)
class Bill:
    """快照中的一张订单：表头取自首行，物料行按序号。"""

    bill_no: str
    customer_code: str
    po: str
    pi: str
    org_code: str
    org_name: str
    lines: list[dict]


def bill(bill_no: str | None) -> Bill | None:
    ls = lines(bill_no)
    if not ls:
        return None
    h = ls[0]
    return Bill(h["bill_no"], h["customer_code"], h["po"], h["pi"], h["org_code"], h["org_name"], ls)


def must_bill(bill_no: str | None) -> Bill:
    b = bill(bill_no)
    if b is None:
        raise biz(ErrorCode.ORDER_NOT_FOUND, bill_no=util.trim(bill_no))
    return b


def snapshot_org(bill_no: str) -> tuple[str, str] | None:
    r = db.one("SELECT org_code, org_name FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", bill_no)
    return None if r is None else (r["org_code"], r["org_name"])


def customer_of_pi(pi: str) -> str | None:
    v = db.column(
        "SELECT customer_code FROM sn_prd_mo WHERE pi = %s AND customer_code <> '' ORDER BY bill_no LIMIT 1", pi
    )
    return v[0] if v else None


def targets(q: str | None, limit: int) -> list[dict]:
    """转单、转组织目标候选：快照中的物料行（生产组织 + 客户 + PI + 单据 + 序号 + 物料 + 生效长度）。"""
    text = util.trim(q)
    args: list = []
    where = " WHERE m.pi <> ''"
    if text:
        where += " AND (m.pi LIKE %s OR m.bill_no LIKE %s OR m.material_code LIKE %s OR m.customer_code LIKE %s)"
        like = util.like_any(text)
        args += [like] * 4
    rows = db.all(
        "SELECT m.* FROM sn_prd_mo m" + where + " ORDER BY m.pi, m.bill_no, m.seq LIMIT %s",
        *args,
        min(max(limit, 1), 200),
    )
    _, _, lens = line_extras([(r["bill_no"], r["seq"]) for r in rows])
    return [
        {
            "org_code": r["org_code"],
            "org_name": r["org_name"],
            "customer_code": r["customer_code"],
            "pi": r["pi"],
            "bill_no": r["bill_no"],
            "seq": r["seq"],
            "material_code": r["material_code"],
            "material_name": r["material_name"],
            "length": effective_length(r, lens.get((r["bill_no"], r["seq"])))[0],
        }
        for r in rows
    ]
