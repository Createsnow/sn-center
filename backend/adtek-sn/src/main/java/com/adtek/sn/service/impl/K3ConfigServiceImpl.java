package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.K3ConfigEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.K3ConfigDTO;
import com.adtek.sn.mapper.K3ConfigMapper;
import com.adtek.sn.service.K3ConfigService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.K3Params;
import com.adtek.sn.support.SecretBox;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.AuditBriefVO;
import com.adtek.sn.vo.K3ConfigVO;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 金蝶连接参数（原系统 app/services/k3_config.py）：页面保存的优先，没有时沿用 .env 的 K3_*；应用密钥加密保存，不回显、不写进痕迹； 保存前先连接测试（登录 +
 * 读组织机构），通过才保存。
 */
@Service
public class K3ConfigServiceImpl implements K3ConfigService {
    private static final Pattern URL = Pattern.compile("^https?://\\S+$");
    private static final int SECRET_MAX = 255;
    private static final List<Integer> LCIDS = List.of(2052, 1033, 3076);

    private final K3ServiceImpl k3;
    private final K3ConfigMapper mapper;
    private final AuditServiceImpl audit;
    private final SecretBox secretBox;
    private final SnSettings settings;
    private final Tx tx;

    public K3ConfigServiceImpl(
            K3ServiceImpl k3,
            K3ConfigMapper mapper,
            AuditServiceImpl audit,
            SecretBox secretBox,
            SnSettings settings,
            Tx tx) {
        this.k3 = k3;
        this.mapper = mapper;
        this.audit = audit;
        this.secretBox = secretBox;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser admin() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireAdmin();
    }

    @Override
    public K3ConfigVO view() {
        admin();
        return viewOf();
    }

    private K3ConfigVO viewOf() {
        K3ConfigEntity row = k3.storedRow();
        K3Params env = k3.envValues();
        K3ConfigVO v = new K3ConfigVO();
        K3Params values;
        if (row == null) {
            values = env;
            v.secretSet = !env.appSecret().isEmpty();
            v.secretUnreadable = false;
            v.source = K3ServiceImpl.SOURCE_ENV;
        } else {
            values = K3ServiceImpl.paramsOf(row, "");
            v.secretSet = row.appSecret != null && !row.appSecret.isEmpty();
            v.secretUnreadable = secretBox.open(row.appSecret) == null;
            v.updatedAt = row.updatedAt;
            v.updatedBy = row.updatedBy;
            v.source = K3ServiceImpl.SOURCE_PAGE;
        }
        v.serverUrl = values.serverUrl();
        v.acctId = values.acctId();
        v.appId = values.appId();
        v.username = values.username();
        v.lcid = values.lcid();
        v.loginTimeout = values.loginTimeout();
        v.queryTimeout = values.queryTimeout();
        v.viewTimeout = values.viewTimeout();
        v.pageRetries = values.pageRetries();
        v.pageRetrySeconds = values.pageRetrySeconds();
        AuditBriefVO last = mapper.lastAction(AuditAction.K3_CONFIG_TEST);
        if (last != null) {
            K3ConfigVO.LastTest t = new K3ConfigVO.LastTest();
            t.ok = "OK".equals(last.result);
            t.at = last.createdAt;
            t.by = last.operator;
            t.byName = last.operatorName;
            t.message = last.errorMsg == null ? "" : last.errorMsg;
            v.lastTest = t;
        }
        v.history = history();
        K3ConfigVO.Env e = new K3ConfigVO.Env();
        e.serverUrl = env.serverUrl();
        e.acctId = env.acctId();
        e.appId = env.appId();
        e.username = env.username();
        e.secretSet = !env.appSecret().isEmpty();
        v.env = e;
        return v;
    }

    private List<K3ConfigVO.History> history() {
        List<K3ConfigVO.History> out = new ArrayList<>();
        for (AuditBriefVO r : mapper.okHistory(AuditAction.K3_CONFIG, 5)) {
            Map<String, String> parts = new LinkedHashMap<>();
            for (String p : Util.nz(r.detail).split("\\s+")) {
                int i = p.indexOf('=');
                if (i > 0) {
                    parts.put(p.substring(0, i), p.substring(i + 1));
                }
            }
            K3ConfigVO.History h = new K3ConfigVO.History();
            h.at = r.createdAt;
            h.by = r.operator;
            h.byName = r.operatorName;
            h.fields = new ArrayList<>();
            for (String f : parts.getOrDefault("changed", "").split(",")) {
                if (!f.isEmpty() && !"-".equals(f)) {
                    h.fields.add(f);
                }
            }
            h.fromEnv = "env".equals(parts.get("from"));
            h.reset = "env".equals(parts.get("reset"));
            out.add(h);
        }
        return out;
    }

    private record Candidate(K3Params next, K3Params current) {}

    private static String text(String v, String label, int max) {
        if (v.isBlank()) {
            throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", label + "必填");
        }
        if (v.strip().length() > max) {
            throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", label + "不超过 " + max + " 字");
        }
        return v.strip();
    }

    private static int limit(Integer v, int cur, String name, int lo, int hi) {
        if (v == null) {
            return cur;
        }
        if (v < lo || v > hi) {
            throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", name + " 须为 " + lo + "–" + hi);
        }
        return v;
    }

    private Candidate candidate(K3ConfigDTO b) {
        K3Params cur;
        try {
            cur = k3.currentValues().values();
        } catch (BusinessException e) {
            // 已存的密钥解不开：其余参数照常带出，密钥须重新填写
            K3ConfigEntity row = k3.storedRow();
            cur = row == null ? k3.envValues() : K3ServiceImpl.paramsOf(row, "");
        }
        String url = b.serverUrl == null ? cur.serverUrl() : text(b.serverUrl, "服务地址", 500);
        String acct = b.acctId == null ? cur.acctId() : text(b.acctId, "账套 ID", 128);
        String app = b.appId == null ? cur.appId() : text(b.appId, "应用 ID", 255);
        String user = b.username == null ? cur.username() : text(b.username, "集成用户", 128);
        if (url == null || !URL.matcher(url).matches()) {
            throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", "服务地址须以 http:// 或 https:// 开头");
        }
        String secret = cur.appSecret();
        if (b.appSecret != null && !b.appSecret.isBlank()) {
            if (b.appSecret.strip().length() > SECRET_MAX) {
                throw Biz.err(
                        SnErrorCode.K3_CONFIG_INVALID, "detail", "应用密钥不超过 " + SECRET_MAX + " 字");
            }
            secret = b.appSecret.strip();
        }
        if (secret == null || secret.isEmpty()) {
            throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", "应用密钥必填");
        }
        String[][] required = {{acct, "账套 ID"}, {app, "应用 ID"}, {user, "集成用户"}};
        for (String[] r : required) {
            if (r[0] == null || r[0].isEmpty()) {
                throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", r[1] + "必填");
            }
        }
        int lcid = cur.lcid();
        if (b.lcid != null) {
            if (!LCIDS.contains(b.lcid)) {
                throw Biz.err(SnErrorCode.K3_CONFIG_INVALID, "detail", "语言只能是 2052、1033 或 3076");
            }
            lcid = b.lcid;
        }
        K3Params next =
                new K3Params(
                        url,
                        acct,
                        app,
                        secret,
                        user,
                        lcid,
                        limit(b.loginTimeout, cur.loginTimeout(), "login_timeout", 5, 300),
                        limit(b.queryTimeout, cur.queryTimeout(), "query_timeout", 10, 600),
                        limit(b.viewTimeout, cur.viewTimeout(), "view_timeout", 5, 300),
                        limit(b.pageRetries, cur.pageRetries(), "page_retries", 0, 10),
                        limit(
                                b.pageRetrySeconds,
                                cur.pageRetrySeconds(),
                                "page_retry_seconds",
                                0,
                                120));
        return new Candidate(next, cur);
    }

    private static String summary(K3Params c, boolean secretChanged) {
        return "url="
                + c.serverUrl()
                + " acct="
                + c.acctId()
                + " app="
                + c.appId()
                + " user="
                + c.username()
                + " lcid="
                + c.lcid()
                + " secret="
                + (secretChanged ? "changed" : "kept")
                + " timeout="
                + c.loginTimeout()
                + "/"
                + c.queryTimeout()
                + "/"
                + c.viewTimeout()
                + " retries="
                + c.pageRetries()
                + "x"
                + c.pageRetrySeconds()
                + "s";
    }

    private static List<String> changed(K3Params c, K3Params cur) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(c.serverUrl(), cur.serverUrl())) {
            out.add("server_url");
        }
        if (!Objects.equals(c.acctId(), cur.acctId())) {
            out.add("acct_id");
        }
        if (!Objects.equals(c.appId(), cur.appId())) {
            out.add("app_id");
        }
        if (!Objects.equals(c.appSecret(), cur.appSecret())) {
            out.add("app_secret");
        }
        if (!Objects.equals(c.username(), cur.username())) {
            out.add("username");
        }
        if (c.lcid() != cur.lcid()) {
            out.add("lcid");
        }
        if (c.loginTimeout() != cur.loginTimeout()) {
            out.add("login_timeout");
        }
        if (c.queryTimeout() != cur.queryTimeout()) {
            out.add("query_timeout");
        }
        if (c.viewTimeout() != cur.viewTimeout()) {
            out.add("view_timeout");
        }
        if (c.pageRetries() != cur.pageRetries()) {
            out.add("page_retries");
        }
        if (c.pageRetrySeconds() != cur.pageRetrySeconds()) {
            out.add("page_retry_seconds");
        }
        return out;
    }

    private record Probe(boolean ok, String message, int orgs, long ms) {}

    private Probe probe(K3Params c) {
        long started = System.nanoTime();
        try {
            int n = k3.fetchOrganizations(c).size();
            return new Probe(true, "", n, (System.nanoTime() - started) / 1_000_000);
        } catch (BusinessException e) {
            return new Probe(false, Biz.message(e), 0, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void auditProbe(CurrentUser cu, Probe p, String info) {
        AuditEntryDTO e = AuditEntryDTO.of(AuditAction.K3_CONFIG_TEST).count(p.orgs()).info(info);
        audit.recordIsolated(cu, p.ok() ? e : e.fail(p.message()));
    }

    @Override
    public K3ConfigVO.TestResult test(K3ConfigDTO body) {
        CurrentUser cu = admin();
        Candidate c = candidate(body);
        Probe p = probe(c.next());
        boolean secretChanged = !Objects.equals(c.next().appSecret(), c.current().appSecret());
        auditProbe(cu, p, summary(c.next(), secretChanged) + " ms=" + p.ms());
        K3ConfigVO.TestResult r = new K3ConfigVO.TestResult();
        r.ok = p.ok();
        r.message = p.message();
        r.orgs = p.orgs();
        r.elapsedMs = p.ms();
        r.checkedAt = Util.now();
        return r;
    }

    @Override
    public K3ConfigVO save(K3ConfigDTO body) {
        CurrentUser cu = admin();
        Candidate c = candidate(body);
        Probe p = probe(c.next());
        boolean secretChanged = !Objects.equals(c.next().appSecret(), c.current().appSecret());
        auditProbe(cu, p, summary(c.next(), secretChanged) + " ms=" + p.ms());
        if (!p.ok()) {
            throw Biz.err(SnErrorCode.K3_TEST_FAILED, "message", p.message());
        }
        tx.exec(
                () -> {
                    K3Params n = c.next();
                    K3ConfigEntity e = new K3ConfigEntity();
                    e.serverUrl = n.serverUrl();
                    e.acctId = n.acctId();
                    e.appId = n.appId();
                    e.appSecret = secretBox.seal(n.appSecret());
                    e.username = n.username();
                    e.lcid = n.lcid();
                    e.loginTimeout = n.loginTimeout();
                    e.queryTimeout = n.queryTimeout();
                    e.viewTimeout = n.viewTimeout();
                    e.pageRetries = n.pageRetries();
                    e.pageRetrySeconds = n.pageRetrySeconds();
                    e.updatedBy = cu.empNo();
                    e.updatedAt = Util.now();
                    boolean first = mapper.lockStored() == null;
                    if (first) {
                        mapper.insert(e);
                    } else {
                        mapper.update(e);
                    }
                    List<String> fields = changed(n, c.current());
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.K3_CONFIG)
                                    .info(
                                            "changed="
                                                    + (fields.isEmpty()
                                                            ? "-"
                                                            : String.join(",", fields))
                                                    + " "
                                                    + (first ? "from=env " : "")
                                                    + summary(n, secretChanged)));
                });
        return viewOf();
    }

    @Override
    public K3ConfigVO reset() {
        CurrentUser cu = admin();
        if (k3.storedRow() == null) {
            return viewOf();
        }
        K3Params env = k3.envValues();
        Probe p = probe(env);
        auditProbe(cu, p, summary(env, false) + " source=env ms=" + p.ms());
        if (!p.ok()) {
            throw Biz.err(SnErrorCode.K3_RESET_FAILED, "message", p.message());
        }
        tx.exec(
                () -> {
                    mapper.delete();
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.K3_CONFIG)
                                    .info("changed=- reset=env " + summary(env, false)));
                });
        return viewOf();
    }
}
