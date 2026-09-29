"""上线导入：每张 PI 在首次生成前可指定一次起始号，并可同时导入历史已发出的 SN 清单。

导入的号状态为已打印，参与查重；能按规则反解的记下十进制流水，计数器抬到其最大值。
完成后（或首次生成后）这张 PI 不再提供起始号。
"""

from __future__ import annotations

from app.core import encoding as codec
from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, factories, generate, orders, rules

CHUNK = 1000
IMPORT_FIELDS = (
    "id",
    "pi_no",
    "start_seq_dec",
    "import_qty",
    "max_seq_dec",
    "factory_code",
    "file_name",
    "created_by",
    "created_at",
)


def status(pi_in: str | None) -> dict:
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    c = generate.counter(pi)
    customer = orders.customer_of_pi(pi)
    r = rules.resolve(pi, customer)
    imports = db.all("SELECT * FROM sn_pi_import WHERE pi_no = %s ORDER BY id DESC", pi)
    return {
        "counter": c,
        "start_allowed": not c["start_locked"],
        "customer_code": customer,
        "rule": None if r is None else rules.rule_info(r),
        "in_snapshot": orders.pi_in_snapshot(pi),
        "imports": [util.jrow(i, IMPORT_FIELDS) for i in imports],
    }


def _jstr(v) -> str:
    return "null" if v is None else str(v)


def init(
    cu: CurrentUser,
    pi_in: str | None,
    start_seq: int | None,
    sns_in: list[str | None] | None,
    customer_in: str | None,
    factory_in: str | None,
    material_in: str | None,
    file_name: str | None,
) -> dict:
    with db.tx():
        pi = util.trim(pi_in)
        if not pi:
            raise biz(ErrorCode.PI_REQUIRED)
        if len(pi) > util.CODE_MAX:
            raise biz(ErrorCode.VALIDATION, detail="pi_no")
        sns: list[str] = []
        if sns_in is not None:
            seen: set[str] = set()
            for line, raw in enumerate(sns_in, start=1):
                sn = util.trim(raw)
                if not sn:
                    continue
                if len(sn) > 64:
                    raise biz(ErrorCode.PI_IMPORT_SN_INVALID, line=line)
                if sn in seen:
                    raise biz(ErrorCode.PI_IMPORT_DUP_IN_LIST, sn=sn)
                seen.add(sn)
                sns.append(sn)
        if start_seq is None and not sns:
            raise biz(ErrorCode.PI_INIT_EMPTY)
        if len(sns) > settings.import_max:
            raise biz(ErrorCode.PI_IMPORT_TOO_LARGE, max=settings.import_max)
        factory = util.trim_or_none(factory_in)
        if factory is not None:
            factories.must_get(factory)
        customer = util.trim(customer_in) or util.trim(orders.customer_of_pi(pi))
        material = util.trim(material_in)
        now = util.now()
        db.exec(
            "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, imported_qty, start_locked, "
            "updated_at) VALUES (%s,0,0,0,0,%s) ON DUPLICATE KEY UPDATE pi_no = pi_no",
            pi,
            now,
        )
        c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
        if c["start_locked"]:
            raise biz(ErrorCode.PI_INIT_LOCKED, pi=pi)
        r = rules.resolve(pi, customer)
        spec = None if r is None else rules.spec_of(r.version)
        if start_seq is not None:
            mx = codec.LONG_MAX if spec is None else codec.max_seq(spec)
            if start_seq < 1 or start_seq > mx:
                raise biz(ErrorCode.GEN_START_INVALID, max=mx)
            if spec is not None:
                generate.check_start_above_foreign(pi, start_seq, generate.next_start(pi, c["last_seq_dec"], spec))
        decoded = 0
        max_seq: int | None = None
        month = util.month_of(now)
        for i in range(0, len(sns), CHUNK):
            chunk = sns[i : i + CHUNK]
            dup = db.column(
                f"SELECT sn FROM sn_key WHERE pi_no = %s AND sn IN ({marks(len(chunk))}) LIMIT 1", pi, *chunk
            )
            if dup:
                raise biz(ErrorCode.PI_IMPORT_DUP_EXISTING, sn=dup[0], pi=pi)
            keys, items, seqs = [], [], []
            for sn in chunk:
                seq = None if spec is None else codec.parse(sn, spec)
                if seq is not None:
                    decoded += 1
                    max_seq = seq if max_seq is None else max(max_seq, seq)
                    seqs.append(seq)
                keys.append((pi, sn, None if seq is None else pi, seq))
                items.append(
                    (
                        month,
                        sn,
                        pi,
                        customer,
                        material,
                        factory,
                        "",
                        None if seq is None else r.version["id"],
                        pi,
                        seq,
                        util.PRINTED,
                        "IMPORT",
                        now,
                        now,
                        now,
                    )
                )
            if seqs:
                taken = db.column(
                    f"SELECT seq_dec FROM sn_key WHERE seq_pi_no = %s AND seq_dec IN ({marks(len(seqs))}) LIMIT 1",
                    pi,
                    *seqs,
                )
                if taken:
                    raise biz(ErrorCode.PI_IMPORT_SEQ_DUP, seq=taken[0], pi=pi)
            db.exec_many("INSERT INTO sn_key(pi_no, sn, seq_pi_no, seq_dec) VALUES (%s,%s,%s,%s)", keys)
            db.exec_many(
                "INSERT INTO sn_item(gen_month, sn, pi_no, customer_code, material_code, factory_code, bill_no, "
                "rule_version_id, seq_pi_no, seq_dec, status, source, created_at, printed_at, updated_at) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                items,
            )
        last = c["last_seq_dec"]
        if start_seq is not None:
            last = max(last, start_seq - 1)
        if max_seq is not None:
            last = max(last, max_seq)
        db.exec(
            "UPDATE sn_pi_counter SET last_seq_dec = %s, imported_qty = %s, start_seq_dec = %s, start_locked = 1, "
            "locked_by = %s, locked_at = %s, updated_at = %s WHERE pi_no = %s",
            last,
            c["imported_qty"] + len(sns),
            start_seq,
            cu.emp_no,
            now,
            now,
            pi,
        )
        if spec is not None and decoded > 0:
            rules.lock_for_generation(r.version["id"])
        db.insert(
            "INSERT INTO sn_pi_import(pi_no, start_seq_dec, import_qty, max_seq_dec, factory_code, file_name, "
            "created_by, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
            pi,
            start_seq,
            len(sns),
            max_seq,
            factory,
            util.cut(util.trim_or_none(file_name), 255),
            cu.emp_no,
            now,
        )
        audit.record(
            cu,
            audit.entry(audit.PI_INIT)
            .pi(pi)
            .factory(factory)
            .customer(customer)
            .material(material)
            .count(len(sns))
            .range(sns[0] if sns else None, sns[-1] if sns else None)
            .status(None, util.PRINTED if sns else None)
            .info(f"start={_jstr(start_seq)} decoded={decoded} max_seq={_jstr(max_seq)} last={last}"),
        )
        return {
            "pi_no": pi,
            "start_seq_dec": start_seq,
            "imported_qty": len(sns),
            "decoded_qty": decoded,
            "max_seq_dec": max_seq,
            "last_seq_dec": last,
        }
