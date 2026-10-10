"""Read only checks before stopping the dedicated synthetic order-audit services."""
from pathlib import Path
import pymysql, json
root = Path.cwd()
private = root / "deploy/.secrets/comparison-20261008"
result = {}
with pymysql.connect(host="127.0.0.1", port=33308, user="root", password=(private/"mysql-root").read_text().strip(), cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
    for kind in ("java", "python"):
        db = "sn_" + kind + "_a48"
        c.execute("SELECT enabled,interval_minutes,next_run_at FROM " + db + ".sn_order_sync_schedule WHERE id=1")
        schedule = c.fetchone()
        assert not schedule["enabled"] and schedule["interval_minutes"] == 1440 and schedule["next_run_at"] is None, kind
        c.execute("SELECT COUNT(*) n FROM information_schema.triggers WHERE trigger_schema=%s", (db,))
        triggers = c.fetchone()["n"]
        assert triggers == 0
        result[kind] = {"schedule": schedule, "triggers": triggers}
    c.execute("SELECT state,COUNT(*) n FROM sn_java_a48.sn_order_sync GROUP BY state")
    result["java"]["syncStates"] = c.fetchall()
    assert all(r["state"] in ("SUCCESS", "FAILED") for r in result["java"]["syncStates"])
    c.execute("SELECT state,COUNT(*) n FROM sn_java_a48.sys_task GROUP BY state")
    result["java"]["taskStates"] = c.fetchall()
    assert all(r["state"] in ("SUCCESS", "FAILED", "CANCELLED") for r in result["java"]["taskStates"])
(root/"tools/.runtime/comparison-20261008/stage49-dedicated-state.json").write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str), "utf8")
print(json.dumps(result, ensure_ascii=False, default=str))
