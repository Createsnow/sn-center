"""金蝶连接参数（生产订单页「金蝶连接」）：页面保存的优先、没有时沿用 .env；保存前须连接测试通过；密钥加密、不回显。"""

from __future__ import annotations

import pytest
from conftest import K3, emp, install_k3, m, ok, uid

from app.db.migrate import MigrationError, check_tables, create_statement
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

        # 恢复默认：先用 .env 的参数连接测试，不通过不恢复，页面参数原样保留
        env = {"server_url": K3.url, "acct_id": "acct", "app_id": "app", "username": "tester", "secret_set": True}
        assert v["env"] == env, "恢复前可看到会改用的 .env 参数（不含密钥）"
        assert api.post("/api/k3-config/reset", m(), op).code == "FORBIDDEN"
        assert api.post("/api/k3-config/reset", m(), w.admin).code == "K3_RESET_FAILED"
        assert sql.count("SELECT COUNT(*) FROM sn_k3_config") == 1
        last = ok(api.get("/api/k3-config", w.admin))["last_test"]
        assert last["ok"] is False and "密钥错误" in last["message"]
        # .env 的参数能连上：删掉页面参数，之后按 .env 连接
        install_k3()
        v = ok(api.post("/api/k3-config/reset", m(), w.admin))
        assert v["source"] == "ENV" and v["acct_id"] == "acct" and v["username"] == "tester"
        assert v["updated_at"] is None and v["history"][0]["reset"] is True and v["last_test"]["ok"] is True
        assert sql.count("SELECT COUNT(*) FROM sn_k3_config") == 0
        K3.calls.clear()
        ok(api.post("/api/factories/sync", m(), w.admin))
        assert _logins()[-1][:4] == ["acct", "tester", "app", "secret"]
        # 本来就在用 .env：不做任何事、不留痕
        n = sql.count("SELECT COUNT(*) FROM sn_audit WHERE action IN ('K3_CONFIG', 'K3_CONFIG_TEST')")
        assert ok(api.post("/api/k3-config/reset", m(), w.admin))["source"] == "ENV"
        assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action IN ('K3_CONFIG', 'K3_CONFIG_TEST')") == n
    finally:
        sql.exec("DELETE FROM sn_k3_config")
        install_k3()

    # 删掉页面保存的参数后回到 .env
    K3.calls.clear()
    ok(api.post("/api/factories/sync", m(), w.admin))
    assert _logins()[-1][:4] == ["acct", "tester", "app", "secret"]


def test_missing_table_is_not_created_on_startup(app_client, sql):
    """已建好的库缺建表脚本里的表（按旧版 V1 建的）：启动检查报错、不补建；表结构只来自建表脚本。"""
    sql.exec("DROP TABLE sn_k3_config")
    try:
        with pytest.raises(MigrationError, match="sn_k3_config"):
            check_tables(db)
        assert (
            sql.count(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                "AND table_name = 'sn_k3_config'"
            )
            == 0
        ), "不补建"
    finally:
        sql.exec(create_statement("sn_k3_config"))
    check_tables(db)
