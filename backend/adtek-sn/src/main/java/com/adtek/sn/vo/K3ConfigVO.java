package com.adtek.sn.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 「金蝶连接」页面：当前参数（不含应用密钥）、来源、最近测试、保存历史与 .env 参数。 */
public class K3ConfigVO {
    public String source;
    public String serverUrl;
    public String acctId;
    public String appId;
    public String username;
    public int lcid;
    public int loginTimeout;
    public int queryTimeout;
    public int viewTimeout;
    public int pageRetries;
    public int pageRetrySeconds;
    public boolean secretSet;
    public boolean secretUnreadable;
    public LocalDateTime updatedAt;
    public String updatedBy;
    public LastTest lastTest;
    public List<History> history;
    public Env env;

    /** 最近一次连接测试。 */
    public static class LastTest {
        public boolean ok;
        public LocalDateTime at;
        public String by;
        public String byName;
        public String message;
    }

    /** 一次保存。 */
    public static class History {
        public LocalDateTime at;
        public String by;
        public String byName;
        public List<String> fields;
        public boolean fromEnv;
        public boolean reset;
    }

    /** 恢复默认后使用的 .env 参数（不含应用密钥）。 */
    public static class Env {
        public String serverUrl;
        public String acctId;
        public String appId;
        public String username;
        public boolean secretSet;
    }

    /** 连接测试结果。 */
    public static class TestResult {
        public boolean ok;
        public String message;
        public int orgs;
        public long elapsedMs;
        public LocalDateTime checkedAt;
    }
}
