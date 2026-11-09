"""保留账户、按 V1 重建其他表（测试阶段 V1__init.sql 改过表结构，又不想重建账户时用；运维手册 8.7）。

保留（不删不改）：sn_user、sn_user_factory（账户与授权组织）、sn_factory（生产组织与 MES 对接配置）、
sn_k3_config（金蝶连接）、flyway_schema_history（迁移记录）。保留的表先逐张与 V1 比对结构（字段、索引），
有一张不一致就不执行、列出差异。sn_seq 里批次号的当天序号（PS- / PL- / XO- 开头的行）重建后写回，
避免同一天再建批与 MES 已收过的批次号重复。其余 sn_ 表（含旧版本留下的）全部删除，按 V1 的顺序重建（空表），
最后把 flyway_schema_history 里 V1 的校验和改成当前脚本的，启动时不再报 migration_checksum_mismatch。

    uv run python -m devtools.rebuild_keep_accounts          # 只检查，列出会做什么（不改库）
    uv run python -m devtools.rebuild_keep_accounts --yes    # 检查通过后执行

读仓库根 .env 的 DB_*（与启动服务相同）。只用于 sn_dev / sn_test（其他库名加 --allow-db <库名>，库名含 prod 一律拒绝）；
执行前先停服务、备份整库：库里还有其他连接时不执行。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field

from app.core.config import settings
from app.db.migrate import HISTORY, migrations
from app.db.session import db

#: 保留的表（结构须与 V1 一致）
KEEP = ("sn_user", "sn_user_factory", "sn_factory", "sn_k3_config")
#: 不能不保留的账户表
REQUIRED_KEEP = ("sn_user", "sn_user_factory")
#: 批次号当天序号：重建 sn_seq 后写回这些行
SEQ_KEEP = r"^(PS|PL|XO)-"
ALLOWED_DB = ("sn_dev", "sn_test")
PROBE = "tmp_rebuild_chk_"

_CREATE = re.compile(r"^CREATE TABLE (\w+) ", re.S)
_INSERT = re.compile(r"^INSERT INTO (\w+)", re.S)

_COLS = (
    "SELECT column_name AS name, ordinal_position AS pos, column_type AS type, is_nullable AS nullable, "
    "column_default AS dflt, extra, collation_name AS coll FROM information_schema.columns "
    "WHERE table_schema = DATABASE() AND table_name = %s ORDER BY ordinal_position"
)
_IDX = (
    "SELECT index_name AS name, seq_in_index AS seq, column_name AS col, non_unique AS nu, sub_part AS part "
    "FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = %s "
    "ORDER BY index_name, seq_in_index"
)
_TBL = (
    "SELECT engine, table_collation AS coll FROM information_schema.tables "
    "WHERE table_schema = DATABASE() AND table_name = %s"
)


@dataclass
class Plan:
    database: str
    keep: list[str]
    #: V1 的建表顺序，(表名, 语句)
    creates: list[tuple[str, str]]
    #: V1 里建表之外的语句（sn_sync_state 的初始行），(表名, 语句)
    inserts: list[tuple[str, str]]
    #: 库里现有的 sn_ 表
    existing: list[str]
    #: 保留表与 V1 的差异：表名 → 差异说明
    diffs: dict[str, list[str]] = field(default_factory=dict)
    seq_rows: list[dict] = field(default_factory=list)
    history: list[dict] = field(default_factory=list)
    checksum: int = 0
    rows: dict[str, int] = field(default_factory=dict)

    @property
    def drop(self) -> list[str]:
        return [t for t in self.existing if t not in self.keep]

    @property
    def create(self) -> list[str]:
        return [t for t, _ in self.creates if t not in self.keep or t not in self.existing]

    @property
    def obsolete(self) -> list[str]:
        v1 = {t for t, _ in self.creates}
        return [t for t in self.drop if t not in v1]


def v1() -> tuple[list[tuple[str, str]], list[tuple[str, str]], int]:
    m = next(m for m in migrations() if m.version == "1")
    creates, inserts = [], []
    for s in m.statements():
        if c := _CREATE.match(s):
            creates.append((c.group(1), s))
        elif i := _INSERT.match(s):
            inserts.append((i.group(1), s))
        else:
            raise SystemExit(f"V1 里有不认识的语句：{s[:60]}")
    return creates, inserts, m.checksum()


def _shape(c, table: str) -> tuple[list[tuple], list[tuple], tuple]:
    c.execute(_COLS, (table,))
    cols = [tuple(r.values()) for r in c.fetchall()]
    c.execute(_IDX, (table,))
    idx = [tuple(r.values()) for r in c.fetchall()]
    c.execute(_TBL, (table,))
    return cols, idx, tuple(c.fetchone().values())


def diff_table(c, table: str, create_sql: str) -> list[str]:
    """库里的表与 V1 建表语句的差异（字段、索引、引擎与排序规则；注释不比）。"""
    probe = PROBE + table
    c.execute(f"DROP TABLE IF EXISTS `{probe}`")
    c.execute(create_sql.replace(f"CREATE TABLE {table} ", f"CREATE TABLE `{probe}` ", 1))
    try:
        have, want = _shape(c, table), _shape(c, probe)
    finally:
        c.execute(f"DROP TABLE IF EXISTS `{probe}`")
    out = []
    hc, wc = {r[0]: r for r in have[0]}, {r[0]: r for r in want[0]}
    for name in wc:
        if name not in hc:
            out.append(f"缺字段 {name}")
        elif hc[name] != wc[name]:
            out.append(f"字段 {name}：库里 {hc[name][1:]}，V1 {wc[name][1:]}")
    out += [f"多出字段 {name}（V1 没有）" for name in hc if name not in wc]
    hi, wi = {}, {}
    for r in have[1]:
        hi.setdefault(r[0], []).append(r[1:])
    for r in want[1]:
        wi.setdefault(r[0], []).append(r[1:])
    for name in wi:
        if name not in hi:
            out.append(f"缺索引 {name}")
        elif hi[name] != wi[name]:
            out.append(f"索引 {name} 与 V1 不同")
    out += [f"多出索引 {name}（V1 没有）" for name in hi if name not in wi]
    if have[2] != want[2]:
        out.append(f"引擎 / 排序规则：库里 {have[2]}，V1 {want[2]}")
    return out


def inspect(c, keep: tuple[str, ...] = KEEP) -> Plan:
    """只读检查（只建、删比对用的临时表），返回执行计划。"""
    creates, inserts, checksum = v1()
    v1_tables = {t for t, _ in creates}
    bad = [t for t in keep if t not in v1_tables]
    if bad:
        raise SystemExit(f"--keep 里的表不在 V1：{', '.join(bad)}")
    c.execute("SELECT DATABASE() AS d")
    database = c.fetchone()["d"]
    c.execute(
        "SELECT table_name AS t FROM information_schema.tables WHERE table_schema = DATABASE() "
        "AND table_type = 'BASE TABLE' AND table_name LIKE 'sn\\_%' ORDER BY table_name"
    )
    existing = [r["t"] for r in c.fetchall()]
    p = Plan(database, list(keep), creates, inserts, existing, checksum=checksum)
    sql = dict(creates)
    for t in keep:
        if t in existing:
            d = diff_table(c, t, sql[t])
            if d:
                p.diffs[t] = d
    for t in existing:
        c.execute(f"SELECT COUNT(*) AS n FROM `{t}`")
        p.rows[t] = c.fetchone()["n"]
    if "sn_seq" in existing:
        c.execute("SELECT name, val FROM sn_seq WHERE name REGEXP %s ORDER BY name", (SEQ_KEEP,))
        p.seq_rows = list(c.fetchall())
    c.execute("SHOW TABLES LIKE %s", (HISTORY,))
    if c.fetchone():
        c.execute(f"SELECT installed_rank, version, type, checksum, success FROM {HISTORY} ORDER BY installed_rank")
        p.history = list(c.fetchall())
    return p


def problems(p: Plan) -> list[str]:
    out = []
    missing = [t for t in REQUIRED_KEEP if t not in p.existing]
    if missing:
        out.append(f"库里没有账户表 {', '.join(missing)}：没有账户可保留，按运维手册 8.1 整体重建")
    for t, d in p.diffs.items():
        out.append(f"{t} 与 V1 结构不一致：" + "；".join(d))
    if not p.history:
        out.append(f"库里没有 {HISTORY}（或为空）：不是本系统按 V1 建的库，按运维手册 8.1 整体重建")
    elif any(not h["success"] for h in p.history):
        out.append(f"{HISTORY} 里有失败的迁移记录，先人工处理")
    elif any(h["version"] not in (None, "0", "1") for h in p.history):
        out.append(f"{HISTORY} 里有 V1 之外的迁移（{[h['version'] for h in p.history]}），本工具只处理 V1")
    return out


def report(p: Plan) -> None:
    print(f"保留（{len(p.keep)} 张 + {HISTORY}）：")
    for t in p.keep:
        if t not in p.existing:
            print(f"  {t:<24} 库里没有，按 V1 新建")
        else:
            print(f"  {t:<24} {p.rows.get(t, 0):>8} 行  结构{'与 V1 一致' if t not in p.diffs else '与 V1 不一致'}")
    print(f"删除后按 V1 重建（空表）{len(p.drop) - len(p.obsolete)} 张：")
    for t in p.drop:
        if t not in p.obsolete:
            print(f"  {t:<24} {p.rows.get(t, 0):>8} 行")
    if p.obsolete:
        print(f"删除、不再重建（V1 已没有的旧表）{len(p.obsolete)} 张：")
        for t in p.obsolete:
            print(f"  {t:<24} {p.rows.get(t, 0):>8} 行")
    new = [t for t in p.create if t not in p.existing]
    if new:
        print(f"库里没有、按 V1 新建：{', '.join(new)}")
    print(f"sn_seq 写回批次号当天序号 {len(p.seq_rows)} 行" + (f"：{_seq_text(p)}" if p.seq_rows else ""))
    v1_row = next((h for h in p.history if h["version"] == "1" and h["type"] == "SQL"), None)
    if v1_row is not None:
        same = v1_row["checksum"] == p.checksum
        print(f"{HISTORY}：V1 校验和 {v1_row['checksum']} → {p.checksum}{'（已一致）' if same else ''}")


def _seq_text(p: Plan) -> str:
    return ", ".join(f"{r['name']}={r['val']}" for r in p.seq_rows)


def execute(c, p: Plan) -> None:
    """按计划删表、重建。DDL 不能回滚：中途失败时修好原因再执行一次（缺的表照常补建），或从备份恢复。"""
    for t in p.drop:
        c.execute(f"DROP TABLE IF EXISTS `{t}`")
    created = set()
    for t, sql in p.creates:
        if t in p.keep and t in p.existing:
            continue
        c.execute(f"DROP TABLE IF EXISTS `{t}`")
        c.execute(sql)
        created.add(t)
    for t, sql in p.inserts:
        if t in created:
            c.execute(sql)
    if p.seq_rows:
        c.executemany(
            "INSERT INTO sn_seq(name, val) VALUES (%s, %s) ON DUPLICATE KEY UPDATE val = GREATEST(val, VALUES(val))",
            [(r["name"], r["val"]) for r in p.seq_rows],
        )
    c.execute(
        f"UPDATE {HISTORY} SET checksum = %s WHERE version = '1' AND type = 'SQL'",
        (p.checksum,),
    )


def other_connections(c) -> list[dict]:
    c.execute(
        "SELECT id, user, host, command, time FROM information_schema.processlist "
        "WHERE db = DATABASE() AND id <> CONNECTION_ID()"
    )
    return list(c.fetchall())


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--yes", action="store_true", help="检查通过后执行删表、重建")
    p.add_argument(
        "--keep",
        default=",".join(KEEP),
        help=f"保留的表，逗号分隔（默认 {','.join(KEEP)}；sn_user、sn_user_factory 必须保留）",
    )
    p.add_argument("--allow-db", default="", help="库名不是 sn_dev / sn_test 时，写上当前库名才执行")
    a = p.parse_args(argv)
    keep = tuple(dict.fromkeys(t.strip() for t in a.keep.split(",") if t.strip()))
    if not set(REQUIRED_KEEP) <= set(keep):
        print("--keep 必须包含 sn_user、sn_user_factory；账户也不保留时按运维手册 8.1 整体重建")
        return 2
    db.configure(settings.db_target(), 1)
    t = db.target
    print(f"数据库 {t.host}:{t.port}/{t.database}")
    if "prod" in t.database.lower():
        print("库名含 prod：正式库不做")
        return 2
    if t.database not in ALLOWED_DB and a.allow_db != t.database:
        print(f"库名不是 {' / '.join(ALLOWED_DB)}：确认无误后加 --allow-db {t.database}")
        return 2
    with db.connection() as conn, conn.cursor() as c:
        plan = inspect(c, keep)
        report(plan)
        bad = problems(plan)
        if bad:
            print("不能执行：")
            for b in bad:
                print(f"  - {b}")
            return 1
        others = other_connections(c)
        if others:
            print(f"库里还有 {len(others)} 个其他连接（服务没停干净，或开着 Navicat 等客户端），执行前先断开：")
            for o in others:
                print(f"  id={o['id']} {o['user']}@{o['host']} {o['command']} {o['time']}s")
            if a.yes:
                print("未执行：断开上面的连接后重新执行")
                return 1
        if not a.yes:
            print("检查通过。确认已停服务、已备份整库后，加 --yes 执行")
            return 0
        execute(c, plan)
        after = inspect(c, keep)
    print(f"已完成：保留 {', '.join(keep)}；重建 {len(plan.create)} 张表；sn_seq 写回 {len(plan.seq_rows)} 行")
    missing = [t for t, _ in after.creates if t not in after.existing]
    if problems(after) or missing:
        print("复查发现问题：", problems(after), f"缺表 {missing}" if missing else "")
        return 1
    print("启动服务后日志应有 partition_added、sn_center_ready，不应有 migration_checksum_mismatch")
    return 0


if __name__ == "__main__":
    sys.exit(main())
