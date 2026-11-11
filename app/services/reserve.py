"""PI 号段预留（生成排号的补充，见 generate）。

同一 PI 下的单据可能分属不同生产组织、分几次生成。为了各次生成后整张 PI 仍按附件项次接号，第一次生成时
（表 sn_pi_reserve_head 还没有本 PI）把本 PI 快照里全部还有额度、附件项次不为空的物料行（不分组织）按
「附件项次 → 行号 → 单据」排好（单据：本次勾选 / 点选的在前、按勾选先后，其余按单据编号），从预留起号起逐行预留号段
（sn_pi_reserve，一行可有几段）。之后：

- 物料行生成用自己预留号段里还空着的流水（删号后再生成回到原号段）；
- 没有预留（后补附件项次、预留之后才同步进来的单据或物料行）或预留不够（数量调大）的部分接在 PI 最大号之后，
  接上的号段记为该行新的预留；数量调小时生成后多出的预留退回成空号段；
- 自定义起始号与预留起号不同时，该行按「新号 = 原预留号 − 预留起号 + 起始号」平移，原预留空着的部分退回成空号段；
- 手动起止号：本次生成的行放弃预留，用范围内（及所选空号段里）空着、不是别的行预留的流水。

PI 最大号（sn_pi_counter.last_seq_dec）含预留号段，所以「最大号之后」不会占用别的行的预留；
空号段 = 1～最大号之间没有号、也不是预留的流水。预留只在生成（PI 操作锁内）时变动。
"""

from __future__ import annotations

from app.core import utils as util
from app.db.session import db

Seg = tuple[int, int]

#: 「最大号之后」的上界（区间运算用，不是流水上限）
INF = 10**18


# ================================================================== 区间运算（闭区间，升序、不重叠）


def merge(segs: list[Seg] | list[list[int]]) -> list[Seg]:
    out: list[list[int]] = []
    for a, b in sorted((int(s), int(e)) for s, e in segs if s <= e):
        if out and a <= out[-1][1] + 1:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def intersect(xs: list[Seg], ys: list[Seg]) -> list[Seg]:
    xs, ys = merge(xs), merge(ys)
    out = []
    i = j = 0
    while i < len(xs) and j < len(ys):
        a, b = max(xs[i][0], ys[j][0]), min(xs[i][1], ys[j][1])
        if a <= b:
            out.append((a, b))
        if xs[i][1] < ys[j][1]:
            i += 1
        else:
            j += 1
    return out


def subtract(xs: list[Seg], ys: list[Seg]) -> list[Seg]:
    out = []
    ys = merge(ys)
    for a, b in merge(xs):
        pos = a
        for s, e in ys:
            if e < pos or s > b:
                continue
            if s > pos:
                out.append((pos, s - 1))
            pos = max(pos, e + 1)
            if pos > b:
                break
        if pos <= b:
            out.append((pos, b))
    return out


def total(segs: list[Seg]) -> int:
    return sum(b - a + 1 for a, b in segs)


def take(free: list[Seg], n: int) -> list[Seg]:
    """从 free（升序）的前头取 n 个流水（不够时取完）。"""
    out = []
    for a, b in free:
        if n <= 0:
            break
        e = min(b, a + n - 1)
        out.append((a, e))
        n -= e - a + 1
    return out


def shift(segs: list[Seg], off: int) -> list[Seg]:
    return [(a + off, b + off) for a, b in segs]


# ================================================================== 读取


def head(pi: str) -> dict | None:
    return db.one("SELECT * FROM sn_pi_reserve_head WHERE pi_no = %s", pi)


def start_of(pi: str) -> int | None:
    """预留起号（起始号平移的基准）；本 PI 还没有预留为 None。"""
    v = db.scalar("SELECT start_seq FROM sn_pi_reserve_head WHERE pi_no = %s", pi)
    return None if v is None else int(v)


def rows(pi: str) -> dict[tuple[str, int], list[Seg]]:
    """各物料行的预留号段：(单据, 序号) → 号段（升序）。"""
    out: dict[tuple[str, int], list[Seg]] = {}
    for r in db.all(
        "SELECT bill_no, seq, start_seq, end_seq FROM sn_pi_reserve WHERE pi_no = %s ORDER BY start_seq", pi
    ):
        out.setdefault((r["bill_no"], int(r["seq"])), []).append((int(r["start_seq"]), int(r["end_seq"])))
    return {k: merge(v) for k, v in out.items()}


def all_segs(rs: dict[tuple[str, int], list[Seg]], but: set[tuple[str, int]] | None = None) -> list[Seg]:
    """预留号段合在一起（but 里的物料行不算）。"""
    return merge([s for k, v in rs.items() if not but or k not in but for s in v])


# ================================================================== 第一次生成时排预留


def build(pi: str, picked_bills: list[str], start: int) -> list[tuple[str, int, int, int]]:
    """排预留（不落库）：本 PI 快照里全部还有额度、附件项次不为空的物料行，按「附件项次 → 行号 → 单据」
    （单据：picked_bills 里的按其先后在前，其余按单据编号）从 start 起逐行接号。返回 [(单据, 序号, 起, 止)]。"""
    from app.services import generate, orders

    snap = db.all("SELECT * FROM sn_prd_mo WHERE pi = %s", pi)
    keys = [(r["bill_no"], r["seq"]) for r in snap]
    counts, fills, _lens, _units = orders.line_extras(keys)
    rank = {b: i for i, b in enumerate(dict.fromkeys(picked_bills))}
    lines = []
    for r in snap:
        k = (r["bill_no"], r["seq"])
        c = counts.get(k)
        quota = orders.sn_qty(r) - (0 if c is None else int(c["own_generated"]))
        attach, _src = orders.effective_attach(r, fills.get(k))
        if quota > 0 and attach:
            lines.append((generate.attach_order(attach), int(r["seq"]), r["bill_no"], quota))
    lines.sort(key=lambda x: (x[0], x[1], (0, rank[x[2]], "") if x[2] in rank else (1, 0, x[2])))
    out = []
    pos = start
    for _o, seq, bill, quota in lines:
        out.append((bill, seq, pos, pos + quota - 1))
        pos += quota
    return out


# ================================================================== 落库（生成事务内）


def save(pi: str, plan_reserve: dict, emp_no: str) -> None:
    """按计划写预留：new 时建表头与其他物料行的预留；本次生成的物料行改为计划给出的号段（set）。"""
    now = util.now()
    if plan_reserve.get("new"):
        db.exec(
            "INSERT INTO sn_pi_reserve_head(pi_no, start_seq, created_by, created_at) VALUES (%s,%s,%s,%s)",
            pi,
            plan_reserve["start"],
            emp_no,
            now,
        )
        rows_ = [(pi, b, s, a, e, now) for b, s, a, e in plan_reserve.get("others") or []]
        if rows_:
            db.exec_many(
                "INSERT INTO sn_pi_reserve(pi_no, bill_no, seq, start_seq, end_seq, created_at) "
                "VALUES (%s,%s,%s,%s,%s,%s)",
                rows_,
            )
    for x in plan_reserve.get("set") or []:
        db.exec("DELETE FROM sn_pi_reserve WHERE pi_no = %s AND bill_no = %s AND seq = %s", pi, x["bill_no"], x["seq"])
        rows_ = [(pi, x["bill_no"], x["seq"], a, e, now) for a, e in x["segs"]]
        if rows_:
            db.exec_many(
                "INSERT INTO sn_pi_reserve(pi_no, bill_no, seq, start_seq, end_seq, created_at) "
                "VALUES (%s,%s,%s,%s,%s,%s)",
                rows_,
            )
