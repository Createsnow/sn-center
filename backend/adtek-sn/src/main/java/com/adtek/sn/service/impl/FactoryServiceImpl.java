package com.adtek.sn.service.impl;

import com.adtek.framework.integration.K3GatewayPort;
import com.adtek.framework.integration.MesPushPort;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.FactoryEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.MesConfigDTO;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.service.FactoryService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.MesWire;
import com.adtek.sn.support.SecretBox;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.FactorySyncVO;
import com.adtek.sn.vo.FactoryToggleVO;
import com.adtek.sn.vo.FactoryVO;
import com.adtek.sn.vo.KeyCountVO;
import com.adtek.sn.vo.MesTestVO;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生产组织（F-03）与 MES 对接配置（F-04）（原系统 app/services/factories.py）。
 *
 * <p>主数据只来自金蝶组织机构：本地没有的新增（禁用的不新增），名称、启用状态随金蝶更新；本系统只维护 MES 对接配置。「对接 MES」开启须
 * 接口地址、凭证（无认证时不需要）已填且连接测试通过；开启时已分配 → 待推送（未入批）；关闭须没有进行中的推送任务、未确认或失败的批次、 等待改挂重推的号，关闭时待推送（未入批）→ 已分配。
 */
@Service
public class FactoryServiceImpl implements FactoryService {
    private static final Pattern URL = Pattern.compile("^https?://\\S+$");
    private static final DateTimeFormatter PING_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final FactoryMapper mapper;
    private final K3ServiceImpl k3;
    private final MesPushPort mes;
    private final AuditServiceImpl audit;
    private final BillStatsServiceImpl billStats;
    private final SecretBox secretBox;
    private final SnSettings settings;
    private final Tx tx;

    public FactoryServiceImpl(
            FactoryMapper mapper,
            K3ServiceImpl k3,
            MesPushPort mes,
            AuditServiceImpl audit,
            BillStatsServiceImpl billStats,
            SecretBox secretBox,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.k3 = k3;
        this.mes = mes;
        this.audit = audit;
        this.billStats = billStats;
        this.secretBox = secretBox;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo());
    }

    // ------------------------------------------------------------------ 供其他用例

    public FactoryEntity get(String code) {
        return code == null ? null : mapper.get(code);
    }

    public FactoryEntity mustGet(String code) {
        FactoryEntity f = get(Util.trim(code));
        if (f == null) {
            throw Biz.err(SnErrorCode.FACTORY_NOT_FOUND, "factory", Util.trim(code));
        }
        return f;
    }

    /** 账户绑定、转组织转入方：须在列表中且金蝶启用。 */
    public FactoryEntity mustBeEnabled(String code) {
        FactoryEntity f = mustGet(code);
        if (!f.enabled) {
            throw Biz.err(
                    SnErrorCode.FACTORY_DISABLED,
                    "factory",
                    f.factoryName + "（" + f.factoryCode + "）");
        }
        return f;
    }

    public Set<String> disabledCodes() {
        return new HashSet<>(mapper.disabledCodes());
    }

    public Set<String> mesCodes() {
        return new HashSet<>(mapper.mesCodes());
    }

    /** 订单上的生产组织不在列表中或未开启「对接 MES」时按未对接处理。 */
    public boolean isMes(String code) {
        return code != null && mapper.isMes(code);
    }

    public Map<String, String> names() {
        Map<String, String> out = new LinkedHashMap<>();
        for (FactoryEntity f : mapper.selectAll()) {
            out.put(f.factoryCode, f.factoryName);
        }
        return out;
    }

    /** 账户的显示时区：只绑一个生产组织时用该组织的时区，否则 UTC+8。 */
    public int tzOffset(List<String> codes) {
        if (codes.size() != 1) {
            return Util.DEFAULT_TZ_OFFSET_MIN;
        }
        Integer v = mapper.tzOffset(codes.getFirst());
        return v == null ? Util.DEFAULT_TZ_OFFSET_MIN : v;
    }

    public FactoryVO json(FactoryEntity f, boolean full) {
        if (f == null) {
            return null;
        }
        FactoryVO v = new FactoryVO();
        v.factoryCode = f.factoryCode;
        v.factoryName = f.factoryName;
        v.enabled = f.enabled;
        v.mesEnabled = f.mesEnabled;
        v.tzOffsetMin = f.tzOffsetMin;
        if (full) {
            v.mesUrl = f.mesUrl;
            v.mesAuthType = f.mesAuthType;
            v.mesGzip = f.mesGzip;
            v.batchSize = f.batchSize;
            v.pushTimeoutSec = f.pushTimeoutSec;
            v.pushConcurrency = f.pushConcurrency;
            v.cfgStatus = f.cfgStatus;
            v.cfgMessage = f.cfgMessage;
            v.cfgCheckedAt = f.cfgCheckedAt;
            v.createdAt = f.createdAt;
            v.updatedAt = f.updatedAt;
            v.credentialSet = f.mesCredential != null && !f.mesCredential.isEmpty();
            v.tested =
                    f.cfgTested != null
                            && !f.cfgTested.isEmpty()
                            && f.cfgTested.equals(storedDigest(f));
        }
        return v;
    }

    // ------------------------------------------------------------------ 列表与同步

    @Override
    public List<FactoryVO> list() {
        CurrentUser cu = cu();
        boolean full = cu.isAdmin();
        Map<String, Long> users = new HashMap<>();
        if (full) {
            for (KeyCountVO r : mapper.boundUsers()) {
                users.put(r.k, r.n);
            }
        }
        List<FactoryVO> out = new ArrayList<>();
        for (FactoryEntity f : mapper.selectAll()) {
            FactoryVO v = json(f, full);
            if (full) {
                v.boundUsers = users.getOrDefault(f.factoryCode, 0L);
            }
            out.add(v);
        }
        return out;
    }

    @Override
    public FactorySyncVO sync() {
        return syncFromK3(cu().requireAdmin());
    }

    /** 金蝶调用失败直接抛出，本地列表不动（每日维护也调用）。 */
    public FactorySyncVO syncFromK3(CurrentUser cu) {
        List<K3GatewayPort.Org> orgs = k3.fetchOrganizations();
        return tx.run(
                () -> {
                    FactorySyncVO r = new FactorySyncVO();
                    var now = Util.now();
                    for (K3GatewayPort.Org org : orgs) {
                        FactoryEntity cur = get(org.number());
                        if (cur == null) {
                            if (!org.enabled()) {
                                continue;
                            }
                            mapper.insert(org.number(), Util.cut(org.name(), 128), now);
                            r.inserted++;
                            continue;
                        }
                        if (!Objects.equals(org.name(), cur.factoryName)) {
                            mapper.rename(org.number(), Util.cut(org.name(), 128), now);
                            r.renamed++;
                        }
                        if (cur.enabled != org.enabled()) {
                            mapper.setEnabled(org.number(), org.enabled(), now);
                            if (org.enabled()) {
                                r.enabled++;
                            } else {
                                r.disabled++;
                            }
                        }
                    }
                    r.total = orgs.size();
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.FACTORY_SYNC)
                                    .count(orgs.size())
                                    .info(
                                            "inserted="
                                                    + r.inserted
                                                    + " renamed="
                                                    + r.renamed
                                                    + " disabled="
                                                    + r.disabled
                                                    + " enabled="
                                                    + r.enabled));
                    return r;
                });
    }

    // ================================================================== MES 对接配置

    /** 连接测试通过时记下的配置摘要：生产组织编码、地址、认证方式、凭证、gzip 压缩（关闭时不计入）。 */
    static String configDigest(
            String code, String url, String auth, String credential, boolean gz) {
        String raw = String.join("\n", code, url, auth, credential) + (gz ? "\ngzip" : "");
        return MesWire.sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    String storedDigest(FactoryEntity f) {
        String cred = secretBox.open(f.mesCredential);
        return configDigest(
                f.factoryCode, f.mesUrl, f.mesAuthType, cred == null ? "" : cred, f.mesGzip);
    }

    static boolean complete(String url, String auth, boolean credentialSet) {
        return url != null && !url.isEmpty() && (credentialSet || !MesWire.needsCredential(auth));
    }

    /** 可开启「对接 MES」：配置已填全且当前配置就是最近一次连接测试通过的。 */
    boolean ready(FactoryEntity f) {
        return complete(
                        f.mesUrl,
                        f.mesAuthType,
                        f.mesCredential != null && !f.mesCredential.isEmpty())
                && Objects.equals(f.cfgTested, storedDigest(f));
    }

    /** 页面提交的配置合成的待测试 / 待保存配置。 */
    private static final class Candidate {
        FactoryEntity base;
        String url;
        String auth;
        String credential;
        boolean gzip;
        int batchSize;
        int pushTimeoutSec;
        int pushConcurrency;
        int tzOffsetMin;

        String digest() {
            return configDigest(base.factoryCode, url, auth, credential, gzip);
        }
    }

    private static int limit(Integer v, int cur, String name, int lo, int hi) {
        if (v == null) {
            return cur;
        }
        if (v < lo || v > hi) {
            throw Biz.err(SnErrorCode.MES_CONFIG_INVALID, "detail", name + " 须为 " + lo + "–" + hi);
        }
        return v;
    }

    private Candidate candidate(FactoryEntity f, MesConfigDTO body) {
        Candidate c = new Candidate();
        c.base = f;
        c.url = body.mesUrl != null ? Util.trim(body.mesUrl) : f.mesUrl;
        String auth = Util.trim(body.mesAuthType);
        c.auth = auth.isEmpty() ? f.mesAuthType : auth;
        if (body.mesCredential != null && !body.mesCredential.isBlank()) {
            c.credential = body.mesCredential.strip();
        } else {
            String stored = secretBox.open(f.mesCredential);
            c.credential = stored == null ? "" : stored;
        }
        if (!c.url.isEmpty() && !URL.matcher(c.url).matches()) {
            throw Biz.err(SnErrorCode.MES_CONFIG_INVALID, "detail", "接口地址须以 http:// 或 https:// 开头");
        }
        if (c.url.length() > 500) {
            throw Biz.err(SnErrorCode.MES_CONFIG_INVALID, "detail", "接口地址过长");
        }
        if (!MesWire.AUTH_TYPES.contains(c.auth)) {
            throw Biz.err(SnErrorCode.MES_CONFIG_INVALID, "detail", "认证方式只能是 TOKEN、HMAC 或 NONE");
        }
        if (!MesWire.needsCredential(c.auth)) {
            c.credential = "";
        }
        if (c.credential.length() > 500) {
            throw Biz.err(SnErrorCode.MES_CONFIG_INVALID, "detail", "凭证过长");
        }
        c.gzip = body.mesGzip == null ? f.mesGzip : body.mesGzip;
        c.batchSize = limit(body.batchSize, f.batchSize, "batch_size", 1, 50_000);
        c.pushTimeoutSec = limit(body.pushTimeoutSec, f.pushTimeoutSec, "push_timeout_sec", 5, 600);
        c.pushConcurrency =
                limit(body.pushConcurrency, f.pushConcurrency, "push_concurrency", 1, 20);
        c.tzOffsetMin = f.tzOffsetMin;
        if (body.tzOffsetMin != null) {
            int tz = body.tzOffsetMin;
            if (tz < -720 || tz > 840 || tz % 15 != 0) {
                throw Biz.err(
                        SnErrorCode.MES_CONFIG_INVALID,
                        "detail",
                        "时区须为 UTC-12:00 到 UTC+14:00，按 15 分钟");
            }
            c.tzOffsetMin = tz;
        }
        return c;
    }

    @Override
    public MesTestVO test(String code, MesConfigDTO body) {
        CurrentUser cu = cu().requireAdmin();
        FactoryEntity f = mustGet(code);
        Candidate c = candidate(f, body);
        if (!complete(c.url, c.auth, !c.credential.isEmpty())) {
            boolean needCred = MesWire.needsCredential(c.auth);
            throw Biz.err(
                    SnErrorCode.MES_CONFIG_INVALID, "detail", needCred ? "接口地址和凭证必填" : "接口地址必填");
        }
        MesPushPort.Target t =
                new MesPushPort.Target(c.url, c.auth, c.credential, c.pushTimeoutSec, c.gzip);
        String batchNo = "PING-" + PING_TS.format(Util.mesLocal(null));
        MesPushPort.Outcome out =
                mes.send(t, batchNo, MesWire.pingBody(f.factoryCode, batchNo), 1, true);
        boolean ok = MesPushPort.RECEIVED.equals(out.kind());
        String message =
                ok
                        ? ""
                        : !Util.blank(out.message())
                                ? out.message()
                                : !Util.blank(out.code()) ? out.code() : "未返回 OK";
        var now = Util.now();
        tx.exec(
                () -> {
                    boolean same = c.digest().equals(storedDigest(f));
                    String tested = ok ? c.digest() : (same ? "" : f.cfgTested);
                    String status = same ? (ok ? "OK" : "FAILED") : f.cfgStatus;
                    mapper.updateTest(f.factoryCode, tested, now, Util.cut(message, 500), status);
                    AuditEntryDTO e = AuditEntryDTO.of(AuditAction.FACTORY_MES_TEST);
                    if (!ok) {
                        e.fail(message);
                    }
                    audit.record(
                            cu,
                            e.factory(f.factoryCode)
                                    .info(
                                            "url="
                                                    + c.url
                                                    + " auth="
                                                    + c.auth
                                                    + " gzip="
                                                    + (c.gzip ? 1 : 0)
                                                    + " batch="
                                                    + batchNo));
                });
        MesTestVO r = new MesTestVO();
        r.ok = ok;
        r.message = message;
        r.batchNo = batchNo;
        r.checkedAt = now;
        return r;
    }

    @Override
    public FactoryVO saveConfig(String code, MesConfigDTO body) {
        CurrentUser cu = cu().requireAdmin();
        String fc =
                tx.run(
                        () -> {
                            FactoryEntity f = mapper.lock(Util.trim(code));
                            if (f == null) {
                                throw Biz.err(
                                        SnErrorCode.FACTORY_NOT_FOUND, "factory", Util.trim(code));
                            }
                            Candidate c = candidate(f, body);
                            String digest = c.digest();
                            boolean connChanged = !digest.equals(storedDigest(f));
                            if (f.mesEnabled
                                    && connChanged
                                    && !Objects.equals(f.cfgTested, digest)) {
                                throw Biz.err(SnErrorCode.MES_TEST_REQUIRED);
                            }
                            String status = f.cfgStatus;
                            if (connChanged) {
                                status =
                                        Objects.equals(f.cfgTested, digest) ? "OK" : "UNCONFIGURED";
                            }
                            FactoryEntity u = new FactoryEntity();
                            u.factoryCode = f.factoryCode;
                            u.mesUrl = c.url;
                            u.mesAuthType = c.auth;
                            u.mesCredential = secretBox.seal(c.credential);
                            u.mesGzip = c.gzip;
                            u.batchSize = c.batchSize;
                            u.pushTimeoutSec = c.pushTimeoutSec;
                            u.pushConcurrency = c.pushConcurrency;
                            u.tzOffsetMin = c.tzOffsetMin;
                            u.cfgStatus = status;
                            u.updatedAt = Util.now();
                            mapper.updateConfig(u);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.FACTORY_MES_CONFIG)
                                            .factory(f.factoryCode)
                                            .info(
                                                    "url="
                                                            + c.url
                                                            + " auth="
                                                            + c.auth
                                                            + " gzip="
                                                            + (c.gzip ? 1 : 0)
                                                            + " credential="
                                                            + (connChanged ? "changed" : "kept")
                                                            + " batch="
                                                            + c.batchSize
                                                            + " timeout="
                                                            + c.pushTimeoutSec
                                                            + " concurrency="
                                                            + c.pushConcurrency
                                                            + " tz="
                                                            + c.tzOffsetMin));
                            return f.factoryCode;
                        });
        return json(get(fc), true);
    }

    /** 关闭对接的阻塞：运行中的推送任务、未确认或失败的批次、等待改挂重推的号。 */
    public FactoryToggleVO.Blockers mesBlockers(String code) {
        FactoryToggleVO.Blockers b = new FactoryToggleVO.Blockers();
        b.tasks = mapper.activeTasks(code);
        b.batches = mapper.openBatches(code);
        b.batchTotal = mapper.openBatchTotal(code);
        b.batchQty = mapper.openBatchQty(code);
        b.reassignQty = mapper.reassignQty(code);
        return b;
    }

    @Override
    public FactoryToggleVO togglePreview(String code) {
        cu().requireAdmin();
        FactoryEntity f = mustGet(code);
        FactoryToggleVO v = new FactoryToggleVO();
        v.factoryCode = f.factoryCode;
        v.mesEnabled = f.mesEnabled;
        v.allocated = mapper.allocatedCount(f.factoryCode);
        v.unbatched = mapper.unbatchedCount(f.factoryCode);
        if (f.mesEnabled) {
            v.blockers = mesBlockers(f.factoryCode);
        } else {
            v.canEnable = ready(f);
        }
        return v;
    }

    @Override
    public FactoryVO setMesEnabled(String code, boolean enabled) {
        CurrentUser cu = cu().requireAdmin();
        String fc =
                tx.run(
                        () -> {
                            FactoryEntity f = mapper.lock(Util.trim(code));
                            if (f == null) {
                                throw Biz.err(
                                        SnErrorCode.FACTORY_NOT_FOUND, "factory", Util.trim(code));
                            }
                            String c = f.factoryCode;
                            if (f.mesEnabled == enabled) {
                                return c;
                            }
                            var now = Util.now();
                            int n;
                            String before;
                            String after;
                            if (enabled) {
                                if (!ready(f)) {
                                    throw Biz.err(SnErrorCode.MES_TEST_REQUIRED);
                                }
                                n = mapper.allocatedToPush(c, now);
                                if (n > 0) {
                                    billStats.touchFactory(c, Util.TO_PUSH);
                                }
                                before = Util.ALLOCATED;
                                after = Util.TO_PUSH;
                            } else {
                                FactoryToggleVO.Blockers b = mesBlockers(c);
                                if (b.tasks > 0 || b.batchTotal > 0 || b.reassignQty > 0) {
                                    List<String> parts = new ArrayList<>();
                                    if (b.tasks > 0) {
                                        parts.add("推送任务 " + b.tasks + " 个");
                                    }
                                    if (b.batchTotal > 0) {
                                        List<String> nos = new ArrayList<>();
                                        for (int i = 0; i < Math.min(5, b.batches.size()); i++) {
                                            nos.add(b.batches.get(i).batchNo);
                                        }
                                        parts.add(
                                                "未确认或失败的批次 "
                                                        + b.batchTotal
                                                        + " 个（"
                                                        + b.batchQty
                                                        + " 枚："
                                                        + String.join("、", nos)
                                                        + "）");
                                    }
                                    if (b.reassignQty > 0) {
                                        parts.add("等待改挂重推的号 " + b.reassignQty + " 枚");
                                    }
                                    throw Biz.err(
                                            SnErrorCode.MES_DISABLE_BLOCKED,
                                            "factory",
                                            c,
                                            "detail",
                                            String.join("；", parts));
                                }
                                n = mapper.unbatchedToAllocated(c, now);
                                if (n > 0) {
                                    billStats.touchFactory(c, Util.ALLOCATED);
                                }
                                before = Util.TO_PUSH;
                                after = Util.ALLOCATED;
                                mapper.deleteFinishedTasks(c);
                            }
                            mapper.setMesEnabled(c, enabled, now);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.FACTORY_MES_TOGGLE)
                                            .factory(c)
                                            .count(n)
                                            .status(before, after)
                                            .info("mes_enabled=" + (enabled ? "True" : "False")));
                            return c;
                        });
        return json(get(fc), true);
    }
}
