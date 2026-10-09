"""领取与打印。页面与对外接口共用：状态记在每一枚 SN 上；领取只按「工厂 + PI」整批，
该厂该 PI 下全部待领取的号 → 待打印（申请中的号不参与）；打印 / 回调 → 已打印。
领取与页面打印都以「工厂 + 请求号」幂等。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

import pymysql

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, is_duplicate, marks
from app.services import audit, factories, files, orders, sn_items

CHUNK = 1000
BATCH_FIELDS = ("batch_no", "factory_code", "pi_no", "request_no", "source", "qty", "created_by", "created_at")
PRINT_FIELDS = ("print_no", "factory_code", "pi_no", "request_no", "qty", "created_by", "created_at")


# ================================================================== 页面视图


@dataclass(frozen=True)
class Scope:
    """可读范围：all_factories_of_pi = 跨厂只读（同一 PI 已分到各厂的号）。"""

    factory: str | None
    all_factories_of_pi: bool


def read_scope(cu: CurrentUser, factory: str | None, pi: str | None, pi_all_factories: bool) -> Scope:
    if pi_all_factories and cu.is_factory:
        if util.blank(pi):
            raise biz(ErrorCode.QUERY_PI_REQUIRED)
        owns = db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_item WHERE factory_code = %s AND pi_no = %s)", cu.factory_code, pi.strip()
        )
        if not owns:
            raise biz(ErrorCode.QUERY_PI_NOT_OWNED, pi=pi.strip())
        return Scope(None, True)
    return Scope(cu.read_factory(factory), False)


def pis(cu: CurrentUser, factory: str | None, pi: str | None) -> list[dict]:
    """领取页每张 PI 的待领取 / 待打印 / 申请中枚数。"""
    sc = read_scope(cu, factory, pi, False)
    sql = (
        "SELECT factory_code, pi_no, MAX(customer_code) customer_code, SUM(status = 'TO_ACQUIRE') to_acquire, "
        "SUM(status = 'TO_PRINT') to_print, SUM(status = 'APPLYING') applying FROM sn_item "
        "WHERE status IN ('TO_ACQUIRE','TO_PRINT','APPLYING') AND factory_code IS NOT NULL"
    )
    args: list = []
    if sc.factory is not None:
        sql += " AND factory_code = %s"
        args.append(sc.factory)
    if not util.blank(pi):
        sql += " AND pi_no = %s"
        args.append(pi.strip())
    sql += " GROUP BY factory_code, pi_no ORDER BY factory_code, pi_no LIMIT 2000"
    return [
        {
            "factory_code": r["factory_code"],
            "pi_no": r["pi_no"],
            "customer_code": r["customer_code"],
            "to_acquire": int(r["to_acquire"]),
            "to_print": int(r["to_print"]),
            "applying": int(r["applying"]),
        }
        for r in db.all(sql, *args)
    ]


def segments(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    material: str | None,
    status: str | None,
    pi_all_factories: bool,
    page: util.Page,
) -> dict:
    """一行 = 工厂 + PI + 物料 + 连续号段 + 状态（按每枚 SN 当前状态现拼）。"""
    sc = read_scope(cu, factory, pi, pi_all_factories)
    where = " WHERE factory_code IS NOT NULL AND seq_dec IS NOT NULL"
    args: list = []
    if not util.blank(status) and status.strip() in util.SN_STATUSES:
        where += " AND status = %s"
        args.append(status.strip())
    else:
        where += " AND status IN ('TO_ACQUIRE','TO_PRINT','APPLYING')"
    if sc.factory is not None:
        where += " AND factory_code = %s"
        args.append(sc.factory)
    if not util.blank(pi):
        where += " AND pi_no = %s"
        args.append(pi.strip())
    if material is not None:
        where += " AND material_code = %s"
        args.append(material.strip())
    grouped = (
        "SELECT factory_code, pi_no, material_code, status, seq_pi_no, MIN(seq_dec) s, MAX(seq_dec) e, "
        "SUBSTRING(MIN(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) start_sn, "
        "SUBSTRING(MAX(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) end_sn, COUNT(*) qty "
        "FROM (SELECT factory_code, pi_no, material_code, status, seq_pi_no, seq_dec, sn, "
        "seq_dec - ROW_NUMBER() OVER (PARTITION BY factory_code, pi_no, material_code, status, seq_pi_no "
        "ORDER BY seq_dec) grp FROM sn_item" + where + ") t "
        "GROUP BY factory_code, pi_no, material_code, status, seq_pi_no, grp"
    )
    total = db.count("SELECT COUNT(*) FROM (" + grouped + ") g", *args)
    rows = db.all(
        grouped + " ORDER BY factory_code, pi_no, material_code, seq_pi_no, s LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    items = [
        {
            "factory_code": r["factory_code"],
            "pi_no": r["pi_no"],
            "material_code": r["material_code"],
            "status": r["status"],
            "seq_pi_no": r["seq_pi_no"],
            "start_seq": int(r["s"]),
            "end_seq": int(r["e"]),
            "start_sn": _text(r["start_sn"]),
            "end_sn": _text(r["end_sn"]),
            "qty": int(r["qty"]),
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def _text(v) -> str | None:
    return v.decode("utf-8") if isinstance(v, (bytes, bytearray)) else v


# ================================================================== 领取


def _batch_out(b: dict, replayed: bool) -> dict:
    return {
        "batch_no": b["batch_no"],
        "factory_code": b["factory_code"],
        "pi_no": b["pi_no"],
        "request_no": b["request_no"],
        "source": b["source"],
        "qty": b["qty"],
        "created_by": b["created_by"],
        "created_at": util.fmt(b["created_at"]),
        "replayed": replayed,
    }


def _request_no(raw: str | None) -> str:
    r = util.trim(raw)
    if not r or len(r) > 64:
        raise biz(ErrorCode.ACQ_REQUEST_NO_REQUIRED)
    return r


def _existing_batch(factory: str, request_no: str) -> dict | None:
    return db.one("SELECT * FROM sn_acquire_batch WHERE factory_code = %s AND request_no = %s", factory, request_no)


def _replay(b: dict, pi: str, req: str) -> dict:
    if b["pi_no"] != pi:
        raise biz(ErrorCode.ACQ_REQUEST_CONFLICT, request_no=req, factory=b["factory_code"], pi=b["pi_no"])
    return _batch_out(b, True)


def acquire(cu: CurrentUser, factory_in: str | None, pi_in: str | None, request_no_in: str | None) -> dict:
    """按 PI 整批领取：该厂该 PI 全部待领取 → 待打印。同一请求号重复调用返回同一批次，不再改任何号。"""
    factory = cu.write_factory(factory_in)
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    req = _request_no(request_no_in)
    prev = _existing_batch(factory, req)
    if prev is not None:
        return _replay(prev, pi, req)
    factories.must_get(factory)
    if settings.acquire_require_snapshot and not orders.pi_in_snapshot(pi):
        raise biz(ErrorCode.ACQ_PI_NOT_IN_ERP, pi=pi)
    try:
        with db.tx():
            now = util.now()
            batch_no = util.next_id("B")
            db.exec(
                "INSERT INTO sn_acquire_batch(batch_no, factory_code, pi_no, request_no, source, qty, created_by, "
                "created_at) VALUES (%s,%s,%s,%s,%s,0,%s,%s)",
                batch_no,
                factory,
                pi,
                req,
                cu.source,
                cu.emp_no,
                now,
            )
            n = db.exec(
                "UPDATE sn_item SET status = 'TO_PRINT', batch_no = %s, acquired_at = %s, updated_at = %s "
                "WHERE factory_code = %s AND pi_no = %s AND status = 'TO_ACQUIRE'",
                batch_no,
                now,
                now,
                factory,
                pi,
            )
            if n == 0:
                raise biz(ErrorCode.ACQ_NOTHING, factory=factory, pi=pi)
            db.exec("UPDATE sn_acquire_batch SET qty = %s WHERE batch_no = %s", n, batch_no)
            first, last = _range("batch_no", batch_no)
            audit.record(
                cu,
                audit.entry(audit.SN_ACQUIRE)
                .factory(factory)
                .pi(pi)
                .count(n)
                .range(first, last)
                .status(util.TO_ACQUIRE, util.TO_PRINT)
                .batch(batch_no)
                .request(req),
            )
            return _batch_out(
                {
                    "batch_no": batch_no,
                    "factory_code": factory,
                    "pi_no": pi,
                    "request_no": req,
                    "source": cu.source,
                    "qty": n,
                    "created_by": cu.emp_no,
                    "created_at": now,
                },
                False,
            )
    except pymysql.err.IntegrityError as e:
        # 同一请求号并发：另一请求已建批次，返回它
        if is_duplicate(e):
            other = _existing_batch(factory, req)
            if other is not None:
                return _replay(other, pi, req)
        raise


def _range(col: str, value: str) -> tuple[str | None, str | None]:
    """某列等于给定值的号中，按流水排序的首尾 SN。"""
    first = db.column(f"SELECT sn FROM sn_item WHERE {col} = %s ORDER BY seq_pi_no, seq_dec, id LIMIT 1", value)
    last = db.column(
        f"SELECT sn FROM sn_item WHERE {col} = %s ORDER BY seq_pi_no DESC, seq_dec DESC, id DESC LIMIT 1", value
    )
    return (first[0] if first else None, last[0] if last else None)


def batch(cu: CurrentUser, batch_no: str | None) -> dict:
    b = db.one("SELECT * FROM sn_acquire_batch WHERE batch_no = %s", util.trim(batch_no))
    if b is None:
        raise biz(ErrorCode.ACQ_BATCH_NOT_FOUND, batch_no=util.trim(batch_no))
    if cu.bound and cu.factory_code != b["factory_code"]:
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.factory_code)
    return b


def batch_items(cu: CurrentUser, batch_no: str | None, page: util.Page) -> dict:
    """批次明细：按流水分页，可反复读取；当前状态以每枚 SN 为准。"""
    b = batch(cu, batch_no)
    total = db.count("SELECT COUNT(*) FROM sn_item WHERE batch_no = %s", b["batch_no"])
    rows = db.all(
        f"SELECT {sn_items.COLS} FROM sn_item WHERE batch_no = %s ORDER BY seq_pi_no, seq_dec, id LIMIT %s, %s",
        b["batch_no"],
        page.offset,
        page.size,
    )
    return util.page_result([sn_items.view(r) for r in rows], total, page)


def _paged(
    table: str,
    fields: tuple[str, ...],
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    page: util.Page,
    exact: dict[str, str | None] | None = None,
) -> dict:
    """exact：额外的等值筛选（单号、请求号、来源），空值不参与。"""
    where = " WHERE 1=1"
    args: list = []
    f = cu.read_factory(factory)
    if f is not None:
        where += " AND factory_code = %s"
        args.append(f)
    if not util.blank(pi):
        where += " AND pi_no = %s"
        args.append(util.trim(pi))
    for col, v in (exact or {}).items():
        if not util.blank(v):
            where += f" AND {col} = %s"
            args.append(util.trim(v))
    total = db.count(f"SELECT COUNT(*) FROM {table}{where}", *args)
    rows = db.all(f"SELECT * FROM {table}{where} ORDER BY created_at DESC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result([util.jrow(r, fields) for r in rows], total, page)


def batches(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    page: util.Page,
    batch_no: str | None = None,
    request_no: str | None = None,
    source: str | None = None,
) -> dict:
    exact = {"batch_no": batch_no, "request_no": request_no, "source": source}
    return _paged("sn_acquire_batch", BATCH_FIELDS, cu, factory, pi, page, exact)


# ================================================================== 页面打印


def print_pi(cu: CurrentUser, factory_in: str | None, pi_in: str | None, request_no_in: str | None) -> dict:
    """页面「待打印」：该厂该 PI 全部待打印 → 已打印，返回打印单号（下载打印文件）。"""
    factory = cu.write_factory(factory_in)
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    req = _request_no(request_no_in)
    prev = _existing_print(factory, req)
    if prev is not None:
        return _replay_print(prev, pi, req)
    try:
        return _print_in_tx(cu, factory, pi, req)
    except pymysql.err.IntegrityError as e:
        # 同一请求号并发：另一请求已建打印单，返回它
        if is_duplicate(e):
            other = _existing_print(factory, req)
            if other is not None:
                return _replay_print(other, pi, req)
        raise


def _existing_print(factory: str, request_no: str) -> dict | None:
    return db.one("SELECT * FROM sn_print WHERE factory_code = %s AND request_no = %s", factory, request_no)


def _replay_print(p: dict, pi: str, req: str) -> dict:
    if p["pi_no"] != pi:
        raise biz(ErrorCode.ACQ_REQUEST_CONFLICT, request_no=req, factory=p["factory_code"], pi=p["pi_no"])
    return {
        "print_no": p["print_no"],
        "factory_code": p["factory_code"],
        "pi_no": p["pi_no"],
        "qty": p["qty"],
        "replayed": True,
    }


def _print_in_tx(cu: CurrentUser, factory: str, pi: str, req: str) -> dict:
    with db.tx():
        now = util.now()
        print_no = util.next_id("P")
        db.exec(
            "INSERT INTO sn_print(print_no, factory_code, pi_no, request_no, qty, created_by, created_at) "
            "VALUES (%s,%s,%s,%s,0,%s,%s)",
            print_no,
            factory,
            pi,
            req,
            cu.emp_no,
            now,
        )
        n = db.exec(
            "UPDATE sn_item SET status = 'PRINTED', print_no = %s, printed_at = %s, updated_at = %s "
            "WHERE factory_code = %s AND pi_no = %s AND status = 'TO_PRINT'",
            print_no,
            now,
            now,
            factory,
            pi,
        )
        if n == 0:
            raise biz(ErrorCode.PRINT_NOTHING, factory=factory, pi=pi)
        db.exec("UPDATE sn_print SET qty = %s WHERE print_no = %s", n, print_no)
        first, last = _range("print_no", print_no)
        audit.record(
            cu,
            audit.entry(audit.SN_PRINT)
            .factory(factory)
            .pi(pi)
            .count(n)
            .range(first, last)
            .status(util.TO_PRINT, util.PRINTED)
            .request(req)
            .info(f"print_no={print_no}"),
        )
        return {"print_no": print_no, "factory_code": factory, "pi_no": pi, "qty": n, "replayed": False}


def print_record(cu: CurrentUser, print_no: str | None) -> dict:
    p = db.one("SELECT * FROM sn_print WHERE print_no = %s", util.trim(print_no))
    if p is None:
        raise biz(ErrorCode.PRINT_NOT_FOUND, print_no=util.trim(print_no))
    if cu.bound and cu.factory_code != p["factory_code"]:
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.factory_code)
    return p


def prints(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    page: util.Page,
    print_no: str | None = None,
    request_no: str | None = None,
) -> dict:
    return _paged("sn_print", PRINT_FIELDS, cu, factory, pi, page, {"print_no": print_no, "request_no": request_no})


def print_file_rows(print_no: str) -> Iterator[list]:
    """打印文件：按 id 游标分页读，不把整单装入内存。"""
    last_id = 0
    no = 0
    while True:
        page = db.all(
            f"SELECT {sn_items.COLS} FROM sn_item WHERE print_no = %s AND id > %s ORDER BY id LIMIT 5000",
            print_no,
            last_id,
        )
        if not page:
            return
        for r in page:
            no += 1
            yield sn_items.file_row(no, sn_items.view(r))
            last_id = r["id"]


def print_file(p: dict, fmt: str) -> Iterator[bytes]:
    return files.stream(fmt, p["print_no"], sn_items.FILE_HEADERS, print_file_rows(p["print_no"]))


# ================================================================== 回调


def callback(
    cu: CurrentUser,
    batch_no: str | None,
    target_status: str | None,
    sns_in: list[str | None] | None,
    ranges: list[dict] | None,
) -> dict:
    """回调：只把属于该厂、该批次、当前待打印的号写成已打印；其余逐一列出原因。"""
    if util.trim(target_status) != util.PRINTED:
        raise biz(ErrorCode.CALLBACK_STATUS_INVALID)
    b = batch(cu, batch_no)
    if cu.is_factory and cu.factory_code != b["factory_code"]:
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.factory_code)
    if cu.is_query:
        raise biz(ErrorCode.FORBIDDEN)
    wanted: dict[str, None] = {}
    for s in sns_in or []:
        if not util.blank(s):
            wanted[s.strip()] = None
    for r in ranges or []:
        for sn in _expand_range(b["batch_no"], r):
            wanted[sn] = None
    if not wanted:
        raise biz(ErrorCode.CALLBACK_EMPTY)
    if len(wanted) > settings.callback_max:
        raise biz(ErrorCode.CALLBACK_TOO_MANY, max=settings.callback_max)
    all_sns = list(wanted)
    with db.tx():
        ts = util.now()
        updated = already = 0
        skipped: list[dict] = []
        first = last = None
        for i in range(0, len(all_sns), CHUNK):
            chunk = all_sns[i : i + CHUNK]
            ph = marks(len(chunk))
            status = {
                r["sn"]: r["status"]
                for r in db.all(
                    f"SELECT sn, status FROM sn_item WHERE batch_no = %s AND factory_code = %s AND sn IN ({ph}) "
                    "FOR UPDATE",
                    b["batch_no"],
                    b["factory_code"],
                    *chunk,
                )
            }
            transferred = set(
                db.column(
                    f"SELECT sn FROM sn_transfer_item WHERE from_batch_no = %s AND sn IN ({ph})", b["batch_no"], *chunk
                )
            )
            to_update = []
            for sn in chunk:
                st = status.get(sn)
                if st == util.TO_PRINT:
                    to_update.append(sn)
                elif st == util.PRINTED:
                    already += 1
                elif st == util.APPLYING:
                    skipped.append({"sn": sn, "reason": "APPLYING"})
                elif sn in transferred:
                    skipped.append({"sn": sn, "reason": "TRANSFERRED"})
                else:
                    skipped.append({"sn": sn, "reason": "NOT_IN_BATCH"})
            if to_update:
                updated += db.exec(
                    "UPDATE sn_item SET status = 'PRINTED', printed_at = %s, updated_at = %s WHERE batch_no = %s "
                    f"AND status = 'TO_PRINT' AND sn IN ({marks(len(to_update))})",
                    ts,
                    ts,
                    b["batch_no"],
                    *to_update,
                )
                if first is None:
                    first = to_update[0]
                last = to_update[-1]
        audit.record(
            cu,
            audit.entry(audit.SN_CALLBACK)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(updated)
            .range(first, last)
            .status(util.TO_PRINT, util.PRINTED)
            .batch(b["batch_no"])
            .info(f"requested={len(all_sns)} already_printed={already} skipped={len(skipped)}"),
        )
        return {
            "batch_no": b["batch_no"],
            "requested": len(all_sns),
            "updated": updated,
            "already_printed": already,
            "skipped": skipped,
        }


def _expand_range(batch_no: str, r: dict) -> list[str]:
    """起止号 → 该批次内（含已从该批次转走的）同一流水归属下的连续号。"""
    start = util.trim(r.get("start_sn"))
    end = util.trim(r.get("end_sn"))
    s = _locate(batch_no, start)
    e = _locate(batch_no, end)
    if s is None or e is None or s["seq_pi_no"] != e["seq_pi_no"]:
        raise biz(ErrorCode.CALLBACK_RANGE_INVALID, start_sn=start, end_sn=end, batch_no=batch_no)
    lo, hi = min(s["seq_dec"], e["seq_dec"]), max(s["seq_dec"], e["seq_dec"])
    if hi - lo + 1 > settings.callback_max:
        raise biz(ErrorCode.CALLBACK_TOO_MANY, max=settings.callback_max)
    seq_pi = s["seq_pi_no"]
    out = db.column(
        "SELECT sn FROM sn_item WHERE batch_no = %s AND seq_pi_no = %s AND seq_dec BETWEEN %s AND %s ORDER BY seq_dec",
        batch_no,
        seq_pi,
        lo,
        hi,
    )
    out += db.column(
        "SELECT sn FROM sn_transfer_item WHERE from_batch_no = %s AND seq_pi_no = %s AND seq_dec BETWEEN %s AND %s",
        batch_no,
        seq_pi,
        lo,
        hi,
    )
    return out


def _locate(batch_no: str, sn: str) -> dict | None:
    hit = db.one(
        "SELECT seq_pi_no, seq_dec FROM sn_item WHERE batch_no = %s AND sn = %s AND seq_dec IS NOT NULL LIMIT 1",
        batch_no,
        sn,
    )
    if hit is None:
        hit = db.one(
            "SELECT seq_pi_no, seq_dec FROM sn_transfer_item WHERE from_batch_no = %s AND sn = %s "
            "AND seq_dec IS NOT NULL LIMIT 1",
            batch_no,
            sn,
        )
    return hit
