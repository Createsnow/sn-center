"""生产组织（F-03）与 MES 对接配置（F-04）。

生产组织主数据只来自金蝶组织机构：本地没有的编码新增（金蝶已禁用的不新增），名称、启用状态随金蝶更新；
本系统只维护 MES 对接配置。号的归属一律按订单上的生产组织编码，不与列表校验。

金蝶禁用的生产组织：不能再绑定账户、不能作转组织转入方；已分到它的号照常领取、推送、拉取、转出。
「对接 MES」开启须接口地址、凭证已填且连接测试通过；开启时已分配 → 待推送（未入批），不自动推送；
关闭须没有进行中的推送任务、未确认或失败的批次（含拉取、转出批次）、等待改挂重推的号，关闭时待推送（未入批）→ 已分配。
"""

from __future__ import annotations

import hashlib
import re

from app.core import secretbox
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, k3, mes_client

BASIC = ("factory_code", "factory_name", "enabled", "mes_enabled", "tz_offset_min")
CONFIG = (
    "mes_url",
    "mes_auth_type",
    "batch_size",
    "push_timeout_sec",
    "push_concurrency",
    "cfg_status",
    "cfg_message",
    "cfg_checked_at",
    "created_at",
    "updated_at",
)
#: 单批枚数、推送超时、并发上限的取值范围
LIMITS = {"batch_size": (1, 50_000), "push_timeout_sec": (5, 600), "push_concurrency": (1, 20)}
_URL = re.compile(r"^https?://\S+$")


def factory_json(f: dict | None, full: bool = False) -> dict | None:
    if f is None:
        return None
    out = util.jrow(f, BASIC + (CONFIG if full else ()), ("enabled", "mes_enabled"))
    if full:
        out["credential_set"] = bool(f["mes_credential"])
        out["tested"] = bool(f["cfg_tested"]) and f["cfg_tested"] == stored_digest(f)
    return out


def scope_sql(facs: tuple[str, ...] | None, col: str = "factory_code") -> tuple[str, list]:
    """读范围的生产组织条件（" AND ..."）与参数；None = 全部，条件为空。"""
    if facs is None:
        return "", []
    if len(facs) == 1:
        return f" AND {col} = %s", [facs[0]]
    return f" AND {col} IN ({marks(len(facs))})", list(facs)


def get(code: str | None, lock: bool = False) -> dict | None:
    if code is None:
        return None
    return db.one("SELECT * FROM sn_factory WHERE factory_code = %s" + (" FOR UPDATE" if lock else ""), code)


def must_get(code: str | None) -> dict:
    f = get(util.trim(code))
    if f is None:
        raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
    return f


def must_be_enabled(code: str | None) -> dict:
    """账户绑定、转组织转入方：须在列表中且金蝶启用。"""
    f = must_get(code)
    if not f["enabled"]:
        raise biz(ErrorCode.FACTORY_DISABLED, factory=f"{f['factory_name']}（{f['factory_code']}）")
    return f


def disabled_codes() -> set[str]:
    return set(db.column("SELECT factory_code FROM sn_factory WHERE enabled = 0"))


def mes_codes() -> set[str]:
    return set(db.column("SELECT factory_code FROM sn_factory WHERE mes_enabled = 1"))


def is_mes(code: str | None) -> bool:
    """订单上的生产组织不在列表中或未开启「对接 MES」时按未对接处理。"""
    return code is not None and db.exists(
        "SELECT EXISTS(SELECT 1 FROM sn_factory WHERE factory_code = %s AND mes_enabled = 1)", code
    )


def names() -> dict[str, str]:
    return {f["factory_code"]: f["factory_name"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}


def tz_offset(codes: tuple[str, ...]) -> int:
    """账户的显示时区：只绑一个生产组织时用该组织的时区，否则 UTC+8。"""
    if len(codes) != 1:
        return util.DEFAULT_TZ_OFFSET_MIN
    v = db.scalar("SELECT tz_offset_min FROM sn_factory WHERE factory_code = %s", codes[0])
    return util.DEFAULT_TZ_OFFSET_MIN if v is None else int(v)


def list_factories(cu: CurrentUser) -> list[dict]:
    """生产组织列表；总部另看 MES 配置与绑定的启用账户数。"""
    full = cu.is_admin
    users = {}
    if full:
        users = {
            r["factory_code"]: int(r["n"])
            for r in db.all(
                "SELECT factory_code, COUNT(*) n FROM (SELECT factory_code, id FROM sn_user "
                "WHERE status = 'ACTIVE' AND factory_code IS NOT NULL UNION ALL SELECT b.factory_code, u.id "
                "FROM sn_user_factory b JOIN sn_user u ON u.id = b.user_id WHERE u.status = 'ACTIVE') t "
                "GROUP BY factory_code"
            )
        }
    out = []
    for f in db.all("SELECT * FROM sn_factory ORDER BY factory_code ASC"):
        v = factory_json(f, full)
        if full:
            v["bound_users"] = users.get(f["factory_code"], 0)
        out.append(v)
    return out


def sync_from_k3(cu: CurrentUser) -> dict:
    """金蝶调用失败直接抛出，本地列表不动。"""
    orgs = k3.fetch_organizations()
    with db.tx():
        inserted = renamed = disabled = enabled = 0
        now = util.now()
        for org in orgs:
            cur = get(org.number)
            if cur is None:
                if not org.enabled:
                    continue
                db.exec(
                    "INSERT INTO sn_factory(factory_code, factory_name, enabled, created_at, updated_at) "
                    "VALUES (%s,%s,1,%s,%s)",
                    org.number,
                    util.cut(org.name, 128),
                    now,
                    now,
                )
                inserted += 1
                continue
            if org.name != cur["factory_name"]:
                db.exec(
                    "UPDATE sn_factory SET factory_name = %s, updated_at = %s WHERE factory_code = %s",
                    util.cut(org.name, 128),
                    now,
                    org.number,
                )
                renamed += 1
            if bool(cur["enabled"]) != org.enabled:
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
        audit.record(
            cu,
            audit.entry(audit.FACTORY_SYNC)
            .count(len(orgs))
            .info(f"inserted={inserted} renamed={renamed} disabled={disabled} enabled={enabled}"),
        )
        return {"total": len(orgs), "inserted": inserted, "renamed": renamed, "disabled": disabled, "enabled": enabled}


# ================================================================== MES 对接配置


def config_digest(code: str, url: str, auth: str, credential: str) -> str:
    """连接测试通过时记下的配置摘要：生产组织编码、地址、认证方式、凭证。"""
    raw = "\n".join((code, url, auth, credential))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def stored_digest(f: dict) -> str:
    return config_digest(
        f["factory_code"], f["mes_url"], f["mes_auth_type"], secretbox.open_(f["mes_credential"]) or ""
    )


def _digest(c: dict) -> str:
    return config_digest(c["factory_code"], c["mes_url"], c["mes_auth_type"], c["mes_credential_plain"])


def _candidate(f: dict, body: dict) -> dict:
    """页面提交的配置（凭证留空 = 沿用已存的）合成为一份待测试 / 待保存的配置。"""
    url = util.trim(body.get("mes_url")) if body.get("mes_url") is not None else f["mes_url"]
    auth = util.trim(body.get("mes_auth_type")) or f["mes_auth_type"]
    cred_in = body.get("mes_credential")
    cred = (
        cred_in.strip()
        if isinstance(cred_in, str) and cred_in.strip()
        else (secretbox.open_(f["mes_credential"]) or "")
    )
    if url and not _URL.match(url):
        raise biz(ErrorCode.MES_CONFIG_INVALID, detail="接口地址须以 http:// 或 https:// 开头")
    if len(url) > 500:
        raise biz(ErrorCode.MES_CONFIG_INVALID, detail="接口地址过长")
    if auth not in mes_client.AUTH_TYPES:
        raise biz(ErrorCode.MES_CONFIG_INVALID, detail="认证方式只能是 TOKEN 或 HMAC")
    if len(cred) > 500:
        raise biz(ErrorCode.MES_CONFIG_INVALID, detail="凭证过长")
    out = {**f, "mes_url": url, "mes_auth_type": auth, "mes_credential_plain": cred}
    for k, (lo, hi) in LIMITS.items():
        v = body.get(k)
        if v is None:
            continue
        if not isinstance(v, int) or isinstance(v, bool) or not lo <= v <= hi:
            raise biz(ErrorCode.MES_CONFIG_INVALID, detail=f"{k} 须为 {lo}–{hi}")
        out[k] = v
    tz = body.get("tz_offset_min")
    if tz is not None:
        if not isinstance(tz, int) or isinstance(tz, bool) or not -720 <= tz <= 840 or tz % 15:
            raise biz(ErrorCode.MES_CONFIG_INVALID, detail="时区须为 UTC-12:00 到 UTC+14:00，按 15 分钟")
        out["tz_offset_min"] = tz
    return out


def test_connection(cu: CurrentUser, code: str | None, body: dict) -> dict:
    """连接测试：向接口地址发一个不含号的 PING 批次（认证、压缩与正式推送相同），只提示结果；通过时记下配置摘要。"""
    f = must_get(code)
    c = _candidate(f, body)
    if not c["mes_url"] or not c["mes_credential_plain"]:
        raise biz(ErrorCode.MES_CONFIG_INVALID, detail="接口地址和凭证必填")
    t = mes_client.Target(c["mes_url"], c["mes_auth_type"], c["mes_credential_plain"], int(c["push_timeout_sec"]))
    batch_no = "PING-" + util.next_id("")
    out = mes_client.send(t, batch_no, mes_client.ping_body(f["factory_code"], batch_no), 1)
    ok = out.kind == mes_client.RECEIPT and isinstance(out.receipt, dict) and out.receipt.get("outcome") == "PROCESSED"
    message = "" if ok else (out.message or (out.receipt or {}).get("rejectMessage") or out.code or "未返回 PROCESSED")
    now = util.now()
    with db.tx():
        same = _digest(c) == stored_digest(f)
        tested = _digest(c) if ok else ("" if same else f["cfg_tested"])
        status = ("OK" if ok else "FAILED") if same else f["cfg_status"]
        db.exec(
            "UPDATE sn_factory SET cfg_tested = %s, cfg_checked_at = %s, cfg_message = %s, cfg_status = %s "
            "WHERE factory_code = %s",
            tested,
            now,
            util.cut(message, 500),
            status,
            f["factory_code"],
        )
        audit.record(
            cu,
            (audit.entry(audit.FACTORY_MES_TEST) if ok else audit.entry(audit.FACTORY_MES_TEST).fail(message))
            .factory(f["factory_code"])
            .info(f"url={c['mes_url']} auth={c['mes_auth_type']} batch={batch_no}"),
        )
    return {"ok": ok, "message": message, "batch_no": batch_no, "checked_at": util.fmt(now)}


def save_config(cu: CurrentUser, code: str | None, body: dict) -> dict:
    """保存 MES 对接配置；已开启对接时，改了地址、认证方式或凭证须先用新配置连接测试通过。"""
    with db.tx():
        f = get(util.trim(code), lock=True)
        if f is None:
            raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
        c = _candidate(f, body)
        digest = _digest(c)
        conn_changed = digest != stored_digest(f)
        if f["mes_enabled"] and conn_changed and f["cfg_tested"] != digest:
            raise biz(ErrorCode.MES_TEST_REQUIRED)
        status = f["cfg_status"]
        if conn_changed:
            # 刚用这份配置测试通过：保存后为正常；否则未配置（缺地址或凭证）/ 待测试（仍记未配置）
            status = "OK" if f["cfg_tested"] == digest else "UNCONFIGURED"
        db.exec(
            "UPDATE sn_factory SET mes_url = %s, mes_auth_type = %s, mes_credential = %s, batch_size = %s, "
            "push_timeout_sec = %s, push_concurrency = %s, tz_offset_min = %s, cfg_status = %s, updated_at = %s "
            "WHERE factory_code = %s",
            c["mes_url"],
            c["mes_auth_type"],
            secretbox.seal(c["mes_credential_plain"]),
            c["batch_size"],
            c["push_timeout_sec"],
            c["push_concurrency"],
            c["tz_offset_min"],
            status,
            util.now(),
            f["factory_code"],
        )
        audit.record(
            cu,
            audit.entry(audit.FACTORY_MES_CONFIG)
            .factory(f["factory_code"])
            .info(
                f"url={c['mes_url']} auth={c['mes_auth_type']} credential={'changed' if conn_changed else 'kept'} "
                f"batch={c['batch_size']} timeout={c['push_timeout_sec']} concurrency={c['push_concurrency']} "
                f"tz={c['tz_offset_min']}"
            ),
        )
    return factory_json(get(f["factory_code"]), True)


def mes_blockers(code: str) -> dict:
    """关闭对接的阻塞：运行中的推送任务、未确认或失败的批次、等待改挂重推的号。"""
    tasks = db.count(
        "SELECT COUNT(*) FROM sn_push_task WHERE factory_code = %s AND state IN ('QUEUED','RUNNING','WAITING')", code
    )
    batches = db.all(
        "SELECT batch_no, qty, result FROM sn_delivery_batch WHERE factory_code = %s AND result IN "
        "('UNCONFIRMED','FAILED') ORDER BY created_at LIMIT 50",
        code,
    )
    batch_total = db.count(
        "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND result IN ('UNCONFIRMED','FAILED')", code
    )
    reassign = db.count(
        "SELECT COUNT(*) FROM sn_item WHERE factory_code = %s AND status = 'TO_PUSH' AND push_type = 'REASSIGN' "
        "AND mes_holder = %s",
        code,
        code,
    )
    return {
        "tasks": tasks,
        "batches": [{"batch_no": b["batch_no"], "qty": b["qty"], "result": b["result"]} for b in batches],
        "batch_total": batch_total,
        "batch_qty": int(
            db.scalar(
                "SELECT COALESCE(SUM(qty), 0) FROM sn_delivery_batch WHERE factory_code = %s "
                "AND result IN ('UNCONFIRMED','FAILED')",
                code,
            )
        ),
        "reassign_qty": reassign,
    }


def mes_toggle_preview(code: str | None) -> dict:
    """切换前的提示：受影响枚数与（关闭时）阻塞项。"""
    f = must_get(code)
    fc = f["factory_code"]
    allocated = db.count("SELECT COUNT(*) FROM sn_item WHERE status = 'ALLOCATED' AND factory_code = %s", fc)
    unbatched = db.count(
        "SELECT COUNT(*) FROM sn_item WHERE status = 'TO_PUSH' AND factory_code = %s AND delivery_batch_no IS NULL", fc
    )
    out = {"factory_code": fc, "mes_enabled": bool(f["mes_enabled"]), "allocated": allocated, "unbatched": unbatched}
    if f["mes_enabled"]:
        out["blockers"] = mes_blockers(fc)
    else:
        out["can_enable"] = bool(f["mes_url"] and f["mes_credential"] and f["cfg_tested"] == stored_digest(f))
    return out


def set_mes_enabled(cu: CurrentUser, code: str | None, enabled: bool) -> dict:
    with db.tx():
        f = get(util.trim(code), lock=True)
        if f is None:
            raise biz(ErrorCode.FACTORY_NOT_FOUND, factory=util.trim(code))
        fc = f["factory_code"]
        if bool(f["mes_enabled"]) == enabled:
            return factory_json(f, True)
        now = util.now()
        if enabled:
            if not (f["mes_url"] and f["mes_credential"] and f["cfg_tested"] == stored_digest(f)):
                raise biz(ErrorCode.MES_TEST_REQUIRED)
            # 已分配 → 待推送（未入批）；不自动推送，由总部「继续推送」或等 MES 拉取
            n = db.exec(
                "UPDATE sn_item SET status = 'TO_PUSH', push_type = 'NEW', updated_at = %s "
                "WHERE status = 'ALLOCATED' AND factory_code = %s",
                now,
                fc,
            )
            before, after = util.ALLOCATED, util.TO_PUSH
        else:
            b = mes_blockers(fc)
            if b["tasks"] or b["batch_total"] or b["reassign_qty"]:
                parts = []
                if b["tasks"]:
                    parts.append(f"推送任务 {b['tasks']} 个")
                if b["batch_total"]:
                    nos = "、".join(x["batch_no"] for x in b["batches"][:5])
                    parts.append(f"未确认或失败的批次 {b['batch_total']} 个（{b['batch_qty']} 枚：{nos}）")
                if b["reassign_qty"]:
                    parts.append(f"等待改挂重推的号 {b['reassign_qty']} 枚")
                raise biz(ErrorCode.MES_DISABLE_BLOCKED, factory=fc, detail="；".join(parts))
            # 待推送（未入批）→ 已分配；已推送、已领取的号不变；接口地址和凭证保留（转出照常推送）
            n = db.exec(
                "UPDATE sn_item SET status = 'ALLOCATED', updated_at = %s "
                "WHERE status = 'TO_PUSH' AND factory_code = %s AND delivery_batch_no IS NULL",
                now,
                fc,
            )
            before, after = util.TO_PUSH, util.ALLOCATED
            db.exec("DELETE FROM sn_push_task WHERE factory_code = %s AND state IN ('STOPPED','DONE')", fc)
        db.exec(
            "UPDATE sn_factory SET mes_enabled = %s, updated_at = %s WHERE factory_code = %s",
            1 if enabled else 0,
            now,
            fc,
        )
        audit.record(
            cu,
            audit.entry(audit.FACTORY_MES_TOGGLE)
            .factory(fc)
            .count(n)
            .status(before, after)
            .info(f"mes_enabled={enabled}"),
        )
    return factory_json(get(fc), True)
