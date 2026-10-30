"""错误路径补充用例：《测试用例》（docs/测试用例.md）中原来没有自动化断言的错误码——规则库、生成与撤销、PI 操作锁、
账户、SN 查询、交付批次人工处理、订单变更提醒。"""

from __future__ import annotations

from conftest import BILLS, emp, m, ok, q, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def mk(w, *lines, pi=None, org=None):
    org = org or w.org(uid("O")[:10])
    pi = pi or uid("PI")
    bill = uid("MO")
    w.order(bill, org, "C1", pi, *lines)
    return org, pi, bill


def test_rule_code_name_and_delete_checks(w, api):
    """RULE-03、RULE-12：编码不合法、编码重复、名称为空；指定给 PI 的规则不能删，没用过、没指定的可删。"""
    base = m(rule_name="x", category_value=uid("PI"), base=10, seq_len=4, with_length=False)
    assert api.post("/api/rules", {**base, "rule_code": "a b", "prefix": P()}, w.admin).code == "RULE_CODE_INVALID"
    assert api.post("/api/rules", {**base, "rule_code": "x" * 65, "prefix": P()}, w.admin).code == "RULE_CODE_INVALID"
    code = uid("R")
    rid = ok(api.post("/api/rules", {**base, "rule_code": code, "prefix": P()}, w.admin))["rule"]["id"]
    assert api.post("/api/rules", {**base, "rule_code": code, "prefix": P()}, w.admin).code == "RULE_CODE_EXISTS"
    r = api.post("/api/rules", {**base, "rule_code": uid("R"), "rule_name": " ", "prefix": P()}, w.admin)
    assert r.code == "RULE_NAME_REQUIRED"
    pi = uid("PI")
    ok(w.bind_pi(pi, rid))
    r = api.delete(f"/api/rules/{rid}", w.admin)
    assert r.code == "RULE_ASSIGNED" and pi in r.body["message"]
    free = w.rule(P())
    assert ok(api.delete(f"/api/rules/{free}", w.admin))["id"] == free
    assert api.delete(f"/api/rules/{free}", w.admin).code == "RULE_NOT_FOUND"


def test_preview_expired_changed_and_rule_exhausted(w, sql):
    """GEN-02、GEN-03、GEN-05：没有预演令牌或用别人的令牌不能生成；预演过期、同步改了物料行数量后预演失效；
    流水位数用尽时预演即报，不自动加宽。"""
    org, pi, bill = mk(w, ("M1", 3))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi))
    other = w.user(emp("F"), "operator", org)
    for tok, who in ((None, w.admin), ("nope", w.admin), (p["token"], other)):
        assert w.api.post("/api/generate/run", m(token=tok), who).code == "GEN_PREVIEW_REQUIRED", tok
    sql.exec("UPDATE sn_gen_preview SET expires_at = created_at - INTERVAL 1 MINUTE WHERE token = %s", p["token"])
    assert w.api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "GEN_PREVIEW_EXPIRED"
    p = ok(w.preview(pi))
    w.order(bill, org, "C1", pi, ("M1", 5))
    assert w.api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "GEN_PREVIEW_CHANGED"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert w.generate(pi)["qty"] == 5, "重新预演后按新数量生成"

    _, pi2, _ = mk(w, ("M1", 10))
    ok(w.bind_pi(pi2, w.rule(P(), seq_len=1, with_length=False)))
    r = w.preview(pi2)
    assert r.code == "GEN_RULE_EXHAUSTED" and r.body["params"] == {"max": 9, "end": 10}


def test_duplicate_full_sn_rolls_back_whole_generation(w, sql):
    """NFR-01：前缀只是开头相同（P1 与 P10）可以建，万一拼出的完整 SN 相同，由号池唯一约束拦下、整次回滚、不跳号。"""
    p = P()
    _, pi1, _ = mk(w, ("M1", 1))
    ok(w.bind_pi(pi1, w.rule(p + "1", seq_len=2, with_length=False)))
    w.generate(pi1)
    _, pi2, bill2 = mk(w, ("M1", 2))
    ok(w.bind_pi(pi2, w.rule(p + "10", seq_len=1, with_length=False)))
    pv = ok(w.preview(pi2))
    assert pv["start_sn"] == p + "101", "与 PI1 的第一枚号相同"
    r = w.api.post("/api/generate/run", m(token=pv["token"]), w.admin)
    assert r.code == "GEN_DUPLICATE_SN" and r.body["params"] == {"sn": p + "101", "pi": pi1}
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi2) == 0, "整次回滚"
    assert sql.scalar("SELECT COALESCE(MAX(last_seq_dec), 0) FROM sn_pi_counter WHERE pi_no = %s", pi2) == 0
    assert sql.scalar("SELECT status FROM sn_gen_job WHERE pi_no = %s", pi2) == "FAILED"
    assert sql.scalar("SELECT own_generated FROM sn_line_count WHERE bill_no = %s", bill2) in (None, 0)


def test_revoke_twice_and_pi_lock_busy(w, sql):
    """REV-09、NFR-02：已整次撤销的生成不能再撤；PI 操作锁被占用时分配、撤销、生成立即提示稍后再试（不排队）。"""
    from app.services import locks

    _, pi, bill = mk(w, ("M1", 2), ("M2", 1))
    ok(w.bind_pi(pi, w.rule(P())))
    j = w.generate(pi, lines=[(bill, 1)])
    ok(w.revoke(j["gen_batch"]))
    assert w.revoke(j["gen_batch"]).code == "GEN_REVOKE_NOT_SUCCESS"
    j = w.generate(pi, lines=[(bill, 1)])
    p = ok(w.preview(pi, lines=[(bill, 2)]))
    held = locks.pi_lock(pi)
    try:
        assert w.api.post("/api/allocation/allocate", m(pi_no=pi), w.admin).code == "PI_BUSY"
        assert w.revoke(j["gen_batch"]).code == "PI_BUSY"
        assert w.api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "PI_BUSY"
    finally:
        held.release()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'", pi) == 2
    # 生成中（有任务占着 running_pi）：撤销到所选物料行提示正在生成
    sql.exec("UPDATE sn_gen_job SET running_pi = %s WHERE gen_batch = %s", pi, j["gen_batch"])
    try:
        r = w.api.post("/api/generate/revoke", m(pi_no=pi, lines=[m(bill_no=bill, seq=1)]), w.admin)
        assert r.code == "GEN_BUSY"
    finally:
        sql.exec("UPDATE sn_gen_job SET running_pi = NULL WHERE gen_batch = %s", j["gen_batch"])
    assert w.allocate(pi)["qty"] == 2, "锁释放后照常分配"


def test_account_error_paths(w, api):
    """AUTH-04、AUTH-07、AUTH-09、AUTH-13：不能停用自己；系统管理员不能改角色；原密码错、新旧相同；白名单格式。"""
    me = ok(api.get("/api/auth/me", w.admin))
    assert api.post(f"/api/users/{me['id']}/disable", {}, w.admin).code == "USER_SELF_DISABLE"
    r = api.put(f"/api/users/{me['id']}", m(name="x", role="operator", factory_codes=[w.org(uid("O")[:10])]), w.admin)
    assert r.code == "USER_SUPER_ADMIN_FIXED"
    org = w.org(uid("O")[:10])
    e = emp("F")
    tok = w.user(e, "operator", org)
    dup = m(emp_no=e, name="x", role="operator", factory_codes=[org], password="Pass1234")
    assert api.post("/api/users", dup, w.admin).code == "USER_EMP_NO_EXISTS"
    pw = "/api/auth/password"
    assert api.post(pw, m(old_password="Wrong1234", new_password="Pass5678"), tok).code == "USER_PASSWORD_WRONG"
    assert api.post(pw, m(old_password="Pass1234", new_password="Pass1234"), tok).code == "USER_PASSWORD_SAME"
    assert api.get("/api/auth/me", tok).status == 200, "改密失败不影响当前登录"
    body = m(emp_no=emp("M"), name="MES", role="mes", factory_code=org)
    for bad in ("1.2.3.999", "10.0.0.0/33", "abc"):
        r = api.post("/api/users", {**body, "ip_whitelist": bad}, w.admin)
        assert r.code == "USER_IP_LIST_INVALID" and bad in r.body["message"], bad
    assert ok(api.post("/api/users", {**body, "ip_whitelist": "10.0.0.1, 192.168.1.0/24"}, w.admin))["secret"]
    uid_ = ok(api.get("/api/auth/me", tok))["id"]
    ok(api.post(f"/api/users/{uid_}/disable", {}, w.admin))
    assert api.get("/api/auth/me", tok).code == "ACCOUNT_DISABLED", "停用后已登录的页面立即失效"
    assert api.page_login(e, "Pass1234").code == "ACCOUNT_DISABLED"


def test_sn_query_error_paths(w, api):
    """QRY-03、QRY-05：查看本 PI 各生产组织须指定 PI、本组织须有该 PI 的号；流水范围值不合法、起大于止。"""
    _, pi, _ = mk(w, ("M1", 3))
    ok(w.bind_pi(pi, w.rule(P(), with_length=False)))
    w.generate(pi)
    op = w.user(emp("F"), "operator", w.org(uid("O")[:10]))
    assert api.get("/api/sn" + q(pi_all="true"), op).code == "QUERY_PI_REQUIRED"
    assert api.get("/api/sn" + q(pi=pi, pi_all="true"), op).code == "QUERY_PI_NOT_OWNED"
    r = api.get("/api/sn" + q(pi=pi, seq_from="ZZ"), w.admin)
    assert r.code == "QUERY_RANGE_INVALID" and r.body["params"] == {"value": "ZZ", "pi": pi}
    assert api.get("/api/sn" + q(pi=pi, seq_from="3", seq_to="1"), w.admin).code == "QUERY_RANGE_REVERSED"
    rg = ok(api.get("/api/sn" + q(pi=pi, seq_from="1", seq_to="3"), w.admin))["range"]
    assert (rg["found"], rg["expected"], rg["missing"]) == (3, 3, 0)


def test_batch_manual_and_resume_error_paths(w, api, sql):
    """BATCH-03、BATCH-04：已确认的批次不能再人工确认或作废；没有待推的号或批次时不能继续推送。"""
    org, pi, _ = mk(w, ("M1", 3))
    w.enable_mes(org, batch_size=5)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    bn = sql.scalar("SELECT batch_no FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", bn) == "SUCCESS"
    for action in ("confirm", "void"):
        r = api.post(f"/api/delivery/batches/{bn}/{action}", m(reason="x", basis="y"), w.admin)
        assert r.code == "BATCH_NOT_PENDING", action
    assert api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin).code == "PUSH_NOTHING"
    assert api.post("/api/delivery/resume", m(factory_code=org), w.admin).code == "PI_REQUIRED"


def test_alert_action_and_map_target_checks(w, api):
    """ORD-14、ORD-15：提醒只能按其类型处理；确认对应的目标须是同单据快照中的另一行；处理过的提醒不能再处理。"""
    org, pi, bill = mk(w, ("M1", 2), ("M2", 2))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    BILLS[bill]["TreeEntity"][0]["MaterialId"] = {"Number": "M9", "Name": "x"}
    w.touch(bill, pi=uid("PI"))
    w.compare()
    al = {x["alert_type"]: x for x in ok(api.get(f"/api/orders/alerts?bill_no={bill}", w.admin))["items"]}
    pi_alert, mat = al["PI"], al["MATERIAL"]

    def handle(a: dict, **body):
        return api.post(f"/api/orders/alerts/{a['id']}/handle", m(**body), w.admin)

    assert handle(pi_alert, action="ACK").code == "ALERT_ACTION_INVALID"
    assert handle(mat, action="CONFIRM").code == "ALERT_ACTION_INVALID"
    for seq in (None, 1, 99):
        assert handle(mat, action="MAP", target_seq=seq).code == "ALERT_MAP_TARGET_INVALID", seq
    ok(handle(pi_alert, action="CONFIRM"))
    assert handle(pi_alert, action="CONFIRM").code == "ALERT_NOT_FOUND"
    ok(handle(mat, action="MAP", target_seq=2))


def test_map_blocked_while_batch_unconfirmed_and_transfer_target_required(w, api, sql):
    """ORD-15、TR-10：失去对应的物料行有号在未确认批次中时不能确认对应，批次确认后可以；转移目标 PI 必填。"""
    org, pi, bill = mk(w, ("M1", 2), ("M2", 2))
    w.enable_mes(org, batch_size=10)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    w.push(receipts=False)
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE pi_no = %s AND result = 'UNCONFIRMED'", pi) == 1
    BILLS[bill]["TreeEntity"][0]["MaterialId"] = {"Number": "M9", "Name": "x"}
    w.touch(bill)
    w.compare()
    mat = next(
        x for x in ok(api.get(f"/api/orders/alerts?bill_no={bill}", w.admin))["items"] if x["alert_type"] == "MATERIAL"
    )
    r = api.post(f"/api/orders/alerts/{mat['id']}/handle", m(action="MAP", target_seq=2), w.admin)
    assert r.code == "ALERT_MAP_FROZEN" and r.body["params"] == {"count": 2}
    w.push()
    ok(api.post(f"/api/orders/alerts/{mat['id']}/handle", m(action="MAP", target_seq=2), w.admin))

    body = m(pi_no=pi, bill_no=bill, scope="BILL", to_bill=bill, to_factory=org, to_material="M2", reason="x")
    assert api.post("/api/transfers/bill/preview", body, w.admin).code == "TR_TO_PI_REQUIRED"
    assert api.post("/api/transfers/org/direct", {**body, "factory_code": org}, w.admin).code == "TR_TO_PI_REQUIRED"
