"""工厂主数据：来自金蝶组织机构，也可手工建档。快照里的生产组织名称按工厂名称对应。

只停用、不删除。停用的工厂：不能作为生成、分配、转厂转入的目标，也不能再绑定给账户（改绑到它）；
已经分到该厂的号仍可领取、打印、回调和转出，绑定它的账户不自动停用。金蝶同步时按组织的禁用状态同步启停（只管来源为金蝶的工厂）。
"""

from __future__ import annotations

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, k3

FIELDS = ("factory_code", "factory_name", "enabled", "source", "created_at", "updated_at")


def factory_json(f: dict | None) -> dict | None:
    return util.jrow(f, FIELDS, ("enabled",))


def scope_sql(facs: tuple[str, ...] | None, col: str = "factory_code") -> tuple[str, list]:
    """读范围的工厂条件（" AND ..."）与参数；None = 全部工厂，条件为空。"""
    if facs is None:
        return "", []
    if len(facs) == 1:
        return f" AND {col} = %s", [facs[0]]
    return f" AND {col} IN ({marks(len(facs))})", list(facs)


def list_factories() -> list[dict]:
    """全部工厂，附绑定该厂的启用账户数（停用前提示用）。"""
    users = {
        r["factory_code"]: int(r["n"])
        for r in db.all(
            "SELECT factory_code, COUNT(*) n FROM (SELECT factory_code, id FROM sn_user "
            "WHERE status = 'ACTIVE' AND factory_code IS NOT NULL UNION ALL SELECT b.factory_code, u.id "
            "FROM sn_user_factory b JOIN sn_user u ON u.id = b.user_id WHERE u.status = 'ACTIVE') t "
            "GROUP BY factory_code"
        )
    }
    return [
        {**factory_json(f), "bound_users": users.get(f["factory_code"], 0)}
        for f in db.all("SELECT * FROM sn_factory ORDER BY factory_code ASC")
    ]


def get(code: str | None) -> dict | None:
    return None if code is None else db.one("SELECT * FROM sn_factory WHERE factory_code = %s", code)


def must_get(code: str | None) -> dict:
    f = get(util.trim(code))
    if f is None:
        raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
    return f


def must_be_enabled(code: str | None) -> dict:
    """作为生成、分配、转入或账户绑定目标的工厂：须存在且启用。"""
    f = must_get(code)
    if not f["enabled"]:
        raise biz(ErrorCode.FACTORY_DISABLED, factory=f"{f['factory_name']}（{f['factory_code']}）")
    return f


def disabled_codes() -> set[str]:
    return set(db.column("SELECT factory_code FROM sn_factory WHERE enabled = 0"))


def by_name(name: str) -> dict | None:
    return db.one("SELECT * FROM sn_factory WHERE factory_name = %s", name)


def code_by_name() -> dict[str, str]:
    """生产组织名称 → 工厂编码。"""
    return {f["factory_name"]: f["factory_code"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}


def sync_from_k3(cu: CurrentUser) -> dict:
    """金蝶调用失败直接抛出，本地工厂不动。组织在金蝶禁用 → 本地（来源为金蝶的）工厂停用，重新启用则恢复。"""
    orgs = k3.fetch_organizations()
    with db.tx():
        inserted = updated = disabled = enabled = 0
        conflicts: list[str] = []
        now = util.now()
        for org in orgs:
            by_code = get(org.number)
            same_name = by_name(org.name)
            if by_code is not None:
                if org.name != by_code["factory_name"]:
                    if same_name is not None:
                        conflicts.append(f"{org.number}/{org.name}")
                        continue
                    db.exec(
                        "UPDATE sn_factory SET factory_name = %s, updated_at = %s WHERE factory_code = %s",
                        org.name,
                        now,
                        org.number,
                    )
                    updated += 1
                if by_code["source"] == "K3" and bool(by_code["enabled"]) != org.enabled:
                    db.exec(
                        "UPDATE sn_factory SET enabled = %s, updated_at = %s WHERE factory_code = %s",
                        1 if org.enabled else 0,
                        now,
                        org.number,
                    )
                    if org.enabled:
                        enabled += 1
                    else:
                        disabled += 1
                continue
            if not org.enabled:
                continue  # 金蝶里已禁用、本地也没有的组织不建档
            if same_name is not None:
                conflicts.append(f"{org.number}/{org.name}")
                continue
            db.exec(
                "INSERT INTO sn_factory(factory_code, factory_name, enabled, source, created_at, updated_at) "
                "VALUES (%s,%s,1,'K3',%s,%s)",
                org.number,
                org.name,
                now,
                now,
            )
            inserted += 1
        audit.record(
            cu,
            audit.entry(audit.FACTORY_SYNC)
            .count(len(orgs))
            .info(
                f"inserted={inserted} updated={updated} disabled={disabled} enabled={enabled} "
                f"conflicts=[{', '.join(conflicts)}]"
            ),
        )
        return {
            "total": len(orgs),
            "inserted": inserted,
            "updated": updated,
            "disabled": disabled,
            "enabled": enabled,
            "conflicts": conflicts,
        }


def create(cu: CurrentUser, code: str | None, name: str | None) -> dict:
    with db.tx():
        c = util.trim(code)
        n = util.trim(name)
        if not c or not n or len(c) > 64 or len(n) > 128:
            raise biz(ErrorCode.FACTORY_CODE_INVALID)
        if get(c) is not None or by_name(n) is not None:
            raise biz(ErrorCode.FACTORY_EXISTS, factory=f"{c}/{n}")
        now = util.now()
        db.exec(
            "INSERT INTO sn_factory(factory_code, factory_name, enabled, source, created_at, updated_at) "
            "VALUES (%s,%s,1,'MANUAL',%s,%s)",
            c,
            n,
            now,
            now,
        )
        audit.record(cu, audit.entry(audit.FACTORY_CREATE).factory(c).info(n))
        return factory_json(get(c))


def update(cu: CurrentUser, code: str | None, name: str | None) -> dict:
    """改名称。快照里的生产组织按名称对应工厂：名称须与金蝶生产订单上的生产组织名称一致。"""
    with db.tx():
        f = db.one("SELECT * FROM sn_factory WHERE factory_code = %s FOR UPDATE", util.trim(code))
        if f is None:
            raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
        n = util.trim(name)
        if not n or len(n) > 128:
            raise biz(ErrorCode.FACTORY_CODE_INVALID)
        if n != f["factory_name"]:
            other = by_name(n)
            if other is not None:
                raise biz(ErrorCode.FACTORY_EXISTS, factory=f"{other['factory_code']}/{n}")
            db.exec(
                "UPDATE sn_factory SET factory_name = %s, updated_at = %s WHERE factory_code = %s",
                n,
                util.now(),
                f["factory_code"],
            )
            audit.record(
                cu, audit.entry(audit.FACTORY_UPDATE).factory(f["factory_code"]).info(f"{f['factory_name']} -> {n}")
            )
        return factory_json(get(f["factory_code"]))


def set_enabled(cu: CurrentUser, code: str | None, enabled: bool) -> dict:
    """停用 / 启用（只停用、不删除）。停用不影响已分到该厂的号的领取、打印与转出，也不停用绑定它的账户。"""
    with db.tx():
        f = db.one("SELECT * FROM sn_factory WHERE factory_code = %s FOR UPDATE", util.trim(code))
        if f is None:
            raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
        if bool(f["enabled"]) != enabled:
            db.exec(
                "UPDATE sn_factory SET enabled = %s, updated_at = %s WHERE factory_code = %s",
                1 if enabled else 0,
                util.now(),
                f["factory_code"],
            )
            users = db.count(
                "SELECT COUNT(*) FROM sn_user WHERE factory_code = %s AND status = 'ACTIVE'", f["factory_code"]
            )
            audit.record(
                cu,
                audit.entry(audit.FACTORY_ENABLE if enabled else audit.FACTORY_DISABLE)
                .factory(f["factory_code"])
                .info(f"{f['factory_name']} bound_active_users={users}"),
            )
        return factory_json(get(f["factory_code"]))
