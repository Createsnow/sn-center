"""页面领取与导出（F-14）。

只按「生产组织 + PI」整批领取：该组织该 PI 下全部可领取的号一起变为已领取并下载文件。
- 可领取：未对接 MES 的组织为待领取的号；对接 MES 的组织为「待推送、未入批」的号，MES 持有组织为本组织的除外
  （同组织内转移后等待改挂重推的号）。所在批次未确认或失败、单据暂停交付、已推送、已被拉取建批、申请中的号都不能领取。
- 与推送、拉取从同一「未入批」号池认领（SKIP LOCKED），一次超过 5000 枚按 5000 枚一段分段认领；
  中途失败时批次记为「未完成」：已认领的号留在该批次、其他通道取不到，但不计已领取、不能下载，
  由原操作人或总部用原请求号「重试」接着认领，全部完成后改为已领取。
- 同一组织同一请求号重复提交返回同一批次。领取后交付方式记为页面领取，不再推送、不能拉取，暂不通知 MES。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

import pymysql

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, is_duplicate
from app.services import audit, factories, files, rules

CHUNK = 5000
PIS_LIMIT = 2000
INCOMPLETE = "INCOMPLETE"
DONE = "DONE"
BATCH_FIELDS = (
    "batch_no",
    "factory_code",
    "pi_no",
    "request_no",
    "mes_org",
    "status",
    "qty",
    "created_by",
    "created_at",
    "finished_at",
)

#: 可领取（i = sn_item，f = sn_factory）
_ELIGIBLE = (
    "(CASE WHEN COALESCE(f.mes_enabled, 0) = 1 THEN (i.status = 'TO_PUSH' AND i.delivery_batch_no IS NULL "
    "AND NOT (i.mes_holder <=> i.factory_code)) ELSE i.status = 'TO_ACQUIRE' END) AND i.suspend IS NULL "
    "AND i.acquire_batch_no IS NULL"
)


def batch_json(b: dict | None) -> dict | None:
    return util.jrow(b, BATCH_FIELDS, ("mes_org",))


@dataclass(frozen=True)
class Scope:
    """可读范围：factories = None 为全部；all_factories_of_pi = 只读查看同一 PI 在各组织的号。"""

    factories: tuple[str, ...] | None
    all_factories_of_pi: bool

    def sql(self, col: str = "i.factory_code") -> tuple[str, list]:
        return factories.scope_sql(self.factories, col)


def read_scope(cu: CurrentUser, factory: str | None, pi: str | None, pi_all: bool) -> Scope:
    """操作员切换为只读查看同一 PI 各组织的号：只限本组织也有号的 PI（Q2）。"""
    if pi_all and cu.is_factory:
        if util.blank(pi):
            raise biz(ErrorCode.QUERY_PI_REQUIRED)
        if not db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_item WHERE factory_code = %s AND pi_no = %s)", cu.factory_code, pi.strip()
        ):
            raise biz(ErrorCode.QUERY_PI_NOT_OWNED, pi=pi.strip())
        return Scope(None, True)
    return Scope(cu.read_factories(factory), False)


# ================================================================== 列表


def pis(
    cu: CurrentUser, factory: str | None, pi: str | None, tab: str | None = None, mes: bool | None = None
) -> list[dict]:
    """每个「生产组织 + PI」一行：可领取、申请中；对接 MES 的另有在未确认或失败批次中的枚数、暂停枚数、是否推送中。"""
    sc = read_scope(cu, factory, pi, False)
    fsql, args = sc.sql()
    where = " WHERE i.factory_code IS NOT NULL AND i.status IN ('TO_PUSH','TO_ACQUIRE','APPLYING')" + fsql
    if not util.blank(pi):
        where += " AND i.pi_no = %s"
        args.append(pi.strip())
    if mes is not None:
        where += " AND COALESCE(f.mes_enabled, 0) = %s"
        args.append(1 if mes else 0)
    having = " HAVING acquirable > 0 OR applying > 0"
    if tab == "ACQUIRABLE":
        having = " HAVING acquirable > 0"
    elif tab == "APPLYING":
        having = " HAVING applying > 0"
    rows = db.all(
        "SELECT i.factory_code, i.pi_no, MAX(i.customer_code) customer_code, MAX(COALESCE(f.mes_enabled, 0)) mes, "
        f"SUM({_ELIGIBLE}) acquirable, SUM(i.status = 'APPLYING') applying, "
        "SUM(i.status = 'TO_PUSH' AND i.delivery_batch_no IS NOT NULL) frozen, "
        "SUM(i.suspend IS NOT NULL) suspended, MAX(i.updated_at) touched "
        "FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code"
        + where
        + " GROUP BY i.factory_code, i.pi_no"
        + having
        + f" ORDER BY touched DESC, i.factory_code, i.pi_no LIMIT {PIS_LIMIT}",
        *args,
    )
    running = {
        (t["factory_code"], t["task_key"])
        for t in db.all("SELECT factory_code, task_key FROM sn_push_task WHERE state IN ('QUEUED','RUNNING','WAITING')")
    }
    return [
        {
            "factory_code": r["factory_code"],
            "pi_no": r["pi_no"],
            "customer_code": r["customer_code"],
            "mes_enabled": bool(r["mes"]),
            "acquirable": int(r["acquirable"] or 0),
            "applying": int(r["applying"] or 0),
            "frozen": int(r["frozen"] or 0),
            "suspended": int(r["suspended"] or 0),
            "pushing": (r["factory_code"], r["pi_no"]) in running,
        }
        for r in rows
    ]


def acquire_preview(cu: CurrentUser, factory_in: str | None, pi_in: str | None) -> dict:
    """确认框：可领取枚数；对接 MES 的另显示在未确认或失败批次中不能领取的、暂停的、等待改挂重推的枚数。"""
    factory = cu.write_factory(factory_in)
    pi = util.trim(pi_in)
    f = factories.must_get(factory)
    r = db.one(
        f"SELECT SUM({_ELIGIBLE}) acquirable, SUM(i.status = 'TO_PUSH' AND i.delivery_batch_no IS NOT NULL) frozen, "
        "SUM(i.suspend IS NOT NULL AND i.status IN ('TO_PUSH','TO_ACQUIRE')) suspended, "
        "SUM(i.status = 'TO_PUSH' AND i.delivery_batch_no IS NULL AND i.mes_holder <=> i.factory_code) reassign, "
        "SUM(i.status = 'APPLYING') applying FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code "
        "WHERE i.factory_code = %s AND i.pi_no = %s AND i.status IN ('TO_PUSH','TO_ACQUIRE','APPLYING')",
        factory,
        pi,
    )
    return {
        "factory_code": factory,
        "pi_no": pi,
        "mes_enabled": bool(f["mes_enabled"]),
        "acquirable": int(r["acquirable"] or 0),
        "frozen": int(r["frozen"] or 0),
        "suspended": int(r["suspended"] or 0),
        "reassign": int(r["reassign"] or 0),
        "applying": int(r["applying"] or 0),
    }


def segments(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    status: str | None,
    pi_all: bool,
    page: util.Page,
) -> dict:
    """号段：一行 = 生产组织 + PI + 单据 + 序号 + 物料 + 连续号段 + 状态（转入号注明流水归属 PI）。"""
    sc = read_scope(cu, factory, pi, pi_all)
    where = " WHERE i.factory_code IS NOT NULL"
    args: list = []
    if not util.blank(status) and status.strip() in util.SN_STATUSES:
        where += " AND i.status = %s"
        args.append(status.strip())
    fsql, fargs = sc.sql()
    where += fsql
    args += fargs
    if not util.blank(pi):
        where += " AND i.pi_no = %s"
        args.append(pi.strip())
    grouped = (
        "SELECT factory_code, pi_no, bill_no, bill_seq, material_code, status, seq_pi_no, MIN(seq_dec) s, "
        "MAX(seq_dec) e, SUBSTRING(MIN(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) start_sn, "
        "SUBSTRING(MAX(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) end_sn, COUNT(*) qty, MAX(suspend) suspend "
        "FROM (SELECT i.factory_code, i.pi_no, i.bill_no, i.bill_seq, i.material_code, i.status, i.seq_pi_no, "
        "i.seq_dec, i.sn, i.suspend, i.seq_dec - ROW_NUMBER() OVER (PARTITION BY i.factory_code, i.pi_no, i.bill_no, "
        "i.bill_seq, i.material_code, i.status, i.seq_pi_no ORDER BY i.seq_dec) grp FROM sn_item i"
        + where
        + ") t GROUP BY factory_code, pi_no, bill_no, bill_seq, material_code, status, seq_pi_no, grp"
    )
    total = db.count("SELECT COUNT(*) FROM (" + grouped + ") g", *args)
    rows = db.all(
        grouped + " ORDER BY factory_code, pi_no, bill_no, bill_seq, seq_pi_no, s LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    items = [
        {
            "factory_code": r["factory_code"],
            "pi_no": r["pi_no"],
            "bill_no": r["bill_no"],
            "bill_seq": r["bill_seq"],
            "material_code": r["material_code"],
            "status": r["status"],
            "seq_pi_no": r["seq_pi_no"],
            "transferred_in": r["seq_pi_no"] != r["pi_no"],
            "start_seq": int(r["s"]),
            "end_seq": int(r["e"]),
            "start_sn": _text(r["start_sn"]),
            "end_sn": _text(r["end_sn"]),
            "qty": int(r["qty"]),
            "suspend": _text(r["suspend"]),
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def _text(v):
    return v.decode("utf-8") if isinstance(v, (bytes, bytearray)) else v


# ================================================================== 领取


def _request_no(raw: str | None) -> str:
    r = util.trim(raw)
    if not r or len(r) > 64:
        raise biz(ErrorCode.ACQ_REQUEST_NO_REQUIRED)
    return r


def _existing(factory: str, req: str) -> dict | None:
    return db.one("SELECT * FROM sn_acquire_batch WHERE factory_code = %s AND request_no = %s", factory, req)


def _out(b: dict, replayed: bool) -> dict:
    out = batch_json(b)
    out["replayed"] = replayed
    out["inline_file"] = b["status"] == DONE and b["qty"] <= settings.acquire_sync_file_limit
    return out


def acquire(cu: CurrentUser, factory_in: str | None, pi_in: str | None, request_no_in: str | None) -> dict:
    """按 PI 整批领取。同一请求号：已完成的返回同一批次；未完成的接着认领（原操作人或总部）。"""
    factory = cu.write_factory(factory_in)
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    req = _request_no(request_no_in)
    f = factories.must_get(factory)
    prev = _existing(factory, req)
    if prev is not None:
        if prev["pi_no"] != pi:
            raise biz(ErrorCode.ACQ_REQUEST_CONFLICT, request_no=req, factory=factory, pi=prev["pi_no"])
        if prev["status"] == DONE:
            return _out(prev, True)
        if prev["created_by"] != cu.emp_no and not cu.is_admin:
            raise biz(ErrorCode.ACQ_RETRY_FORBIDDEN)
        b = prev
    else:
        try:
            batch_no = util.next_id("AQ")
            db.exec(
                "INSERT INTO sn_acquire_batch(batch_no, factory_code, pi_no, request_no, mes_org, status, qty, created_by, "
                "created_at) VALUES (%s,%s,%s,%s,%s,%s,0,%s,%s)",
                batch_no,
                factory,
                pi,
                req,
                1 if f["mes_enabled"] else 0,
                INCOMPLETE,
                cu.emp_no,
                util.now(),
            )
        except pymysql.err.IntegrityError as e:
            if is_duplicate(e):
                other = _existing(factory, req)
                if other is not None:
                    return acquire(cu, factory, pi, req)
            raise
        b = _existing(factory, req)
    # 分段认领：每段一个短事务；中途失败批次保持未完成
    while True:
        with db.tx():
            ids = db.column(
                f"SELECT i.id FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code "
                f"WHERE i.pi_no = %s AND i.factory_code = %s AND i.status IN ('TO_PUSH','TO_ACQUIRE') AND {_ELIGIBLE} "
                "ORDER BY i.seq_pi_no, i.seq_dec LIMIT %s FOR UPDATE OF i SKIP LOCKED",
                pi,
                factory,
                CHUNK,
            )
            if ids:
                db.exec(
                    f"UPDATE sn_item SET acquire_batch_no = %s, updated_at = %s WHERE id IN ({','.join(['%s'] * len(ids))})",
                    b["batch_no"],
                    util.now(),
                    *ids,
                )
        if len(ids) < CHUNK:
            break
    with db.tx():
        now = util.now()
        n = db.exec(
            "UPDATE sn_item SET status = 'ACQUIRED', delivery_mode = 'PAGE', delivered_at = %s, updated_at = %s "
            "WHERE acquire_batch_no = %s AND status IN ('TO_PUSH','TO_ACQUIRE')",
            now,
            now,
            b["batch_no"],
        )
        total = db.count("SELECT COUNT(*) FROM sn_item WHERE acquire_batch_no = %s", b["batch_no"])
        if total == 0:
            db.exec("DELETE FROM sn_acquire_batch WHERE batch_no = %s", b["batch_no"])
            raise biz(ErrorCode.ACQ_NOTHING, factory=factory, pi=pi)
        db.exec(
            "UPDATE sn_acquire_batch SET status = %s, qty = %s, finished_at = %s WHERE batch_no = %s",
            DONE,
            total,
            now,
            b["batch_no"],
        )
        first, last = _range(b["batch_no"])
        audit.record(
            cu,
            audit.entry(audit.SN_ACQUIRE)
            .factory(factory)
            .pi(pi)
            .count(total)
            .range(first, last)
            .status(util.TO_PUSH if f["mes_enabled"] else util.TO_ACQUIRE, util.ACQUIRED)
            .batch(b["batch_no"])
            .request(req)
            .info(f"mes_org={int(bool(f['mes_enabled']))} claimed_now={n}"),
        )
    return _out(_existing(factory, req), prev is not None)


def _range(batch_no: str) -> tuple[str | None, str | None]:
    first = db.column(
        "SELECT sn FROM sn_item WHERE acquire_batch_no = %s ORDER BY seq_pi_no, seq_dec LIMIT 1", batch_no
    )
    last = db.column(
        "SELECT sn FROM sn_item WHERE acquire_batch_no = %s ORDER BY seq_pi_no DESC, seq_dec DESC LIMIT 1", batch_no
    )
    return (first[0] if first else None, last[0] if last else None)


def batch(cu: CurrentUser, batch_no: str | None) -> dict:
    b = db.one("SELECT * FROM sn_acquire_batch WHERE batch_no = %s", util.trim(batch_no))
    if b is None:
        raise biz(ErrorCode.ACQ_BATCH_NOT_FOUND, batch_no=util.trim(batch_no))
    cu.check_read(b["factory_code"])
    return b


def batches(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    page: util.Page,
    batch_no: str | None = None,
    request_no: str | None = None,
) -> dict:
    fsql, fargs = factories.scope_sql(cu.read_factories(factory))
    where = " WHERE 1=1" + fsql
    args = list(fargs)
    for col, v in (("pi_no", pi), ("batch_no", batch_no), ("request_no", request_no)):
        if not util.blank(v):
            where += f" AND {col} = %s"
            args.append(util.trim(v))
    total = db.count("SELECT COUNT(*) FROM sn_acquire_batch" + where, *args)
    rows = db.all(
        f"SELECT * FROM sn_acquire_batch{where} ORDER BY created_at DESC LIMIT %s, %s", *args, page.offset, page.size
    )
    return util.page_result([batch_json(r) for r in rows], total, page)


# ================================================================== 领取文件

FILE_HEADERS = [
    "行号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "PI",
    "单据编号",
    "单据序号",
    "物料编码",
    "长度",
    "客户编码",
    "生产组织",
    "状态",
    "领取批次",
]

_FILE_COLS = (
    "id, sn, rule_version_id, seq_dec, pi_no, bill_no, bill_seq, material_code, length_text, customer_code, "
    "factory_code, status, acquire_batch_no, delivery_mode"
)


def file_row(no: int, r: dict) -> list:
    """领取文件的一行：导出时刻的当前值。"""
    return [
        no,
        r["sn"],
        rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        str(r["seq_dec"]),
        r["pi_no"],
        r["bill_no"],
        r["bill_seq"],
        r["material_code"],
        r["length_text"] or "",
        r["customer_code"],
        r["factory_code"],
        r["status"],
        r["acquire_batch_no"],
    ]


def _file_rows(batch_no: str) -> Iterator[list]:
    last_id = 0
    no = 0
    while True:
        page = db.all(
            f"SELECT {_FILE_COLS} FROM sn_item WHERE acquire_batch_no = %s AND id > %s ORDER BY id LIMIT 5000",
            batch_no,
            last_id,
        )
        if not page:
            return
        for r in page:
            no += 1
            last_id = r["id"]
            yield file_row(no, r)


def batch_file(cu: CurrentUser, batch_no: str | None, fmt: str) -> tuple[dict, Iterator[bytes]]:
    """领取文件（可反复下载，只读）；转走的号不再出现在原批次文件中。未完成的批次不能下载。"""
    b = batch(cu, batch_no)
    if b["status"] != DONE:
        raise biz(ErrorCode.ACQ_BATCH_INCOMPLETE, batch_no=b["batch_no"])
    return b, files.stream(fmt, b["batch_no"], FILE_HEADERS, _file_rows(b["batch_no"]))


def batch_items(cu: CurrentUser, batch_no: str | None, page: util.Page) -> dict:
    b = batch(cu, batch_no)
    total = db.count("SELECT COUNT(*) FROM sn_item WHERE acquire_batch_no = %s", b["batch_no"])
    rows = db.all(
        f"SELECT {_FILE_COLS} FROM sn_item WHERE acquire_batch_no = %s ORDER BY seq_pi_no, seq_dec LIMIT %s, %s",
        b["batch_no"],
        page.offset,
        page.size,
    )
    return util.page_result(
        [
            dict(zip(["line_no", *[h for h in _KEYS]], file_row(i + 1 + page.offset, r), strict=True))
            for i, r in enumerate(rows)
        ],
        total,
        page,
    )


_KEYS = (
    "sn",
    "seq_text",
    "seq_dec",
    "pi_no",
    "bill_no",
    "bill_seq",
    "material_code",
    "length",
    "customer_code",
    "factory_code",
    "status",
    "acquire_batch_no",
)
