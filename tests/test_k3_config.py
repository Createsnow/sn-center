"""金蝶连接参数（生产订单页「金蝶连接」）：页面保存的优先、没有时沿用 .env；保存前须连接测试通过；密钥加密、不回显。"""

from __future__ import annotations

from conftest import K3, emp, install_k3, m, ok, uid

from app.db.migrate import ensure_added_tables
from app.db.session import db


def _expect_secret(secret: str) -> None:
    """假金蝶：只有应用密钥为 secret 时登录成功；组织机构查询返回空。"""

    def handler(call):
        if "LoginByAppSecret" in call.path:
            ok_ = call.body["parameters"][3] == secret
            return (
                {"LoginResultType": 1, "KDSVCSessionId": "sid"}
                if ok_
                else {"LoginResultType": 0, "Message": "密钥错误"}
            )
        return []

    K3.handle(handler)


def _logins() -> list[list]:
    return [c.body["parameters"] for c in K3.calls if "LoginByAppSecret" in c.path]


def test_k3_config_env_then_page(w, api, sql):
    op = w.user(emp("K"), "operator", w.org(uid("O")[:10]))
    try:
        # 没有在页面保存过：带出 .env 的参数，密钥不回显
        v = ok(api.get("/api/k3-config", w.admin))
        assert v["source"] == "ENV" and v["server_url"] == K3.url and v["acct_id"] == "acct"
        assert v["username"] == "tester" and v["lcid"] == 2052 and v["page_retry_seconds"] == 0
        assert v["secret_set"] is True and "app_secret" not in v.body and v["updated_at"] is None

        # 只有系统管理员
        assert api.get("/api/k3-config", op).code == "FORBIDDEN"
        assert api.post("/api/k3-config/test", m(), op).code == "FORBIDDEN"
        assert api.put("/api/k3-config", m(acct_id="x"), op).code == "FORBIDDEN"

        # 参数校验
        for bad in (
            m(server_url="ftp://k3/"),
            m(server_url=" "),
            m(lcid=9999),
            m(login_timeout=1),
            m(page_retries=11),
            m(app_secret="s" * 256),
        ):
            assert api.put("/api/k3-config", bad, w.admin).code == "K3_CONFIG_INVALID", bad

        # 连接测试：不通过只提示，不保存；通过时带组织数
        _expect_secret("secret2")
        t = ok(api.post("/api/k3-config/test", m(acct_id="acct2", app_secret="wrong"), w.admin))
        assert t["ok"] is False and "密钥错误" in t["message"]
        assert api.put("/api/k3-config", m(acct_id="acct2", app_secret="wrong"), w.admin).code == "K3_TEST_FAILED"
        assert sql.count("SELECT COUNT(*) FROM sn_k3_config") == 0, "测试不通过不保存"
        t = ok(api.post("/api/k3-config/test", m(acct_id="acct2", app_secret="secret2"), w.admin))
        assert t["ok"] is True and t["orgs"] == 0
        assert sql.count("SELECT COUNT(*) FROM sn_k3_config") == 0, "连接测试不保存"

        # 保存：先测试通过再写入；密钥加密保存，痕迹只记是否更换
        v = ok(api.put("/api/k3-config", m(acct_id=" acct2 ", app_secret="secret2", query_timeout=90), w.admin))
        assert v["source"] == "PAGE" and v["acct_id"] == "acct2" and v["query_timeout"] == 90
        assert v["updated_by"] == "admin" and v["last_test"]["ok"] is True and "app_secret" not in v.body
        row = sql.one("SELECT * FROM sn_k3_config WHERE id = 1")
        assert row["app_secret"].startswith("v1:") and "secret2" not in row["app_secret"]
        a = sql.one("SELECT detail FROM sn_audit WHERE action = 'K3_CONFIG' ORDER BY id DESC LIMIT 1")
        assert "secret=changed" in a["detail"] and "from=env" in a["detail"] and "secret2" not in a["detail"]
        assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE detail LIKE %s", "%secret2%") == 0
        h = v["history"][0]
        assert h["by"] == "admin" and h["from_env"] is True
        assert h["fields"] == ["acct_id", "app_secret", "query_timeout"], "与 .env 相比改了哪些项"

        # 之后调用金蝶用页面保存的参数
        K3.calls.clear()
        ok(api.post("/api/factories/sync", m(), w.admin))
        assert _logins() and _logins()[-1][:4] == ["acct2", "tester", "app", "secret2"]

        # 密钥留空 = 沿用已存的
        v = ok(api.put("/api/k3-config", m(username="tester2", app_secret=""), w.admin))
        assert v["username"] == "tester2"
        assert v["history"][0]["fields"] == ["username"] and v["history"][0]["from_env"] is False, "密钥未更换"
        a = sql.one("SELECT detail FROM sn_audit WHERE action = 'K3_CONFIG' ORDER BY id DESC LIMIT 1")
        assert "secret=kept" in a["detail"] and "from=env" not in a["detail"]
        K3.calls.clear()
        ok(api.post("/api/factories/sync", m(), w.admin))
        assert _logins()[-1][:4] == ["acct2", "tester2", "app", "secret2"]

        # SN_SECRET 换过、密钥解不开：调用金蝶明确报错，页面提示重新填写
        sql.exec("UPDATE sn_k3_config SET app_secret = 'v1:AAAA' WHERE id = 1")
        assert ok(api.get("/api/k3-config", w.admin))["secret_unreadable"] is True
        assert api.post("/api/factories/sync", m(), w.admin).code == "K3_SECRET_UNREADABLE"
        assert api.put("/api/k3-config", m(), w.admin).code == "K3_CONFIG_INVALID", "须重新填写密钥"
        v = ok(api.put("/api/k3-config", m(app_secret="secret2"), w.admin))
        assert v["secret_unreadable"] is False
    finally:
        sql.exec("DELETE FROM sn_k3_config")
        install_k3()

    # 删掉页面保存的参数后回到 .env
    K3.calls.clear()
    ok(api.post("/api/factories/sync", m(), w.admin))
    assert _logins()[-1][:4] == ["acct", "tester", "app", "secret"]


def test_k3_config_table_added_to_existing_db(app_client, sql):
    """已建好的库（V1 已执行过）没有 sn_k3_config：启动时按 V1 的建表语句补建，已有的不动。"""
    sql.exec("DROP TABLE sn_k3_config")
    assert ensure_added_tables(db) == ["sn_k3_config"]
    assert ensure_added_tables(db) == []
    assert sql.count("SELECT COUNT(*) FROM sn_k3_config") == 0
