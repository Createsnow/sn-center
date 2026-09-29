"""工厂主数据：来自金蝶组织机构，也可手工建档。快照里的生产组织名称按工厂名称对应。"""

from __future__ import annotations

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, k3

FIELDS = ("factory_code", "factory_name", "enabled", "source", "created_at", "updated_at")


def factory_json(f: dict | None) -> dict | None:
    return util.jrow(f, FIELDS, ("enabled",))


def list_factories() -> list[dict]:
    return [factory_json(f) for f in db.all("SELECT * FROM sn_factory ORDER BY factory_code ASC")]


def get(code: str | None) -> dict | None:
    return None if code is None else db.one("SELECT * FROM sn_factory WHERE factory_code = %s", code)


def must_get(code: str | None) -> dict:
    f = get(util.trim(code))
    if f is None:
        raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
    return f


def by_name(name: str) -> dict | None:
    return db.one("SELECT * FROM sn_factory WHERE factory_name = %s", name)


def code_by_name() -> dict[str, str]:
    """生产组织名称 → 工厂编码。"""
    return {f["factory_name"]: f["factory_code"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}


def sync_from_k3(cu: CurrentUser) -> dict:
    """金蝶调用失败直接抛出，本地工厂不动。"""
    orgs = k3.fetch_organizations()
    with db.tx():
        inserted = updated = 0
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
                continue
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
            .info(f"inserted={inserted} updated={updated} conflicts=[{', '.join(conflicts)}]"),
        )
        return {"total": len(orgs), "inserted": inserted, "updated": updated, "conflicts": conflicts}


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
