"""历史领取批次（只读）与 SN 读范围。

页面领取已改为 SN 导出（见 sn_export，只读、不改状态）：不再新建领取批次。此前领取的号仍为已领取，
领取批次可在 SN 查询中查看明细、反复下载文件；转走的号不再出现在原批次文件中。
启动时把中途失败、未完成的领取批次放回号池（release_incomplete），这些号照常推送、拉取或导出。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import factories, files, rules

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


# ================================================================== 历史领取批次


def release_incomplete() -> int:
    """未完成的领取批次（领取中途失败、已不能重试）：号清掉领取批次回到号池，批次删除。返回放回的枚数。"""
    nos = db.column("SELECT batch_no FROM sn_acquire_batch WHERE status = %s", INCOMPLETE)
    n = 0
    for no in nos:
        with db.tx():
            n += db.exec(
                "UPDATE sn_item SET acquire_batch_no = NULL, updated_at = %s "
                "WHERE acquire_batch_no = %s AND status IN ('TO_PUSH','TO_ACQUIRE')",
                util.now(),
                no,
            )
            db.exec("DELETE FROM sn_acquire_batch WHERE batch_no = %s AND status = %s", no, INCOMPLETE)
    return n


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
