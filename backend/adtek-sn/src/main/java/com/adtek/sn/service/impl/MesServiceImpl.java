package com.adtek.sn.service.impl;

import static com.adtek.sn.service.impl.BatchServiceImpl.FAILED;
import static com.adtek.sn.service.impl.BatchServiceImpl.RECEIPT_NG;
import static com.adtek.sn.service.impl.BatchServiceImpl.RECEIPT_OK;
import static com.adtek.sn.service.impl.BatchServiceImpl.SRC_PULL;
import static com.adtek.sn.service.impl.BatchServiceImpl.SUCCESS;
import static com.adtek.sn.service.impl.BatchServiceImpl.VOIDED;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.PrincipalPort;
import com.adtek.framework.security.SessionService;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.FactoryEntity;
import com.adtek.sn.domain.UserEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.DeliveryMapper;
import com.adtek.sn.service.MesService;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.MesException;
import com.adtek.sn.support.MesWire;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Trace;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.MesReplyVO;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * MES 对外接口（原系统 api/routes/mes.py、delivery/pull.py）：② 取批、③ 回执、④ 已交付查询、⑤ 批次明细。
 *
 * <ul>
 *   <li>一律 POST + JSON 请求体；请求体支持 gzip（③ 回执除外），解压后不超过 1 MB；
 *   <li>应答信封 {"schemaVersion", "code", "message", "batchNo", "timestamp", ...}：成功为 HTTP 200 且 code
 *       = OK；出错另带 details；
 *   <li>限流：同一生产组织每分钟 SN_MES_PULL_RATE_PER_MINUTE 次，超出 429 RATE_LIMITED + Retry-After。
 * </ul>
 */
@Service
public class MesServiceImpl implements MesService {
    public static final int MAX_INFLATED = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> RECEIPT_MSG =
            Map.of(
                    "CONFIRMED", "回执已确认，批次成功",
                    "REJECTED", "整批退回已登记，批次失败",
                    "ARCHIVED", "批次此前已确认，本次回执只存档");

    private final DeliveryMapper mapper;
    private final BatchServiceImpl batches;
    private final ReceiptServiceImpl receipts;
    private final FactoryServiceImpl factories;
    private final UserServiceImpl users;
    private final ChangeServiceImpl changes;
    private final RuleServiceImpl rules;
    private final AuditServiceImpl audit;
    private final FailureAuditServiceImpl failures;
    private final SessionService sessions;
    private final PrincipalPort principals;
    private final SnLocks locks;
    private final SnSettings settings;
    private final Tx tx;

    public MesServiceImpl(
            DeliveryMapper mapper,
            BatchServiceImpl batches,
            ReceiptServiceImpl receipts,
            FactoryServiceImpl factories,
            UserServiceImpl users,
            ChangeServiceImpl changes,
            RuleServiceImpl rules,
            AuditServiceImpl audit,
            FailureAuditServiceImpl failures,
            SessionService sessions,
            PrincipalPort principals,
            SnLocks locks,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.batches = batches;
        this.receipts = receipts;
        this.factories = factories;
        this.users = users;
        this.changes = changes;
        this.rules = rules;
        this.audit = audit;
        this.failures = failures;
        this.sessions = sessions;
        this.principals = principals;
        this.locks = locks;
        this.settings = settings;
        this.tx = tx;
    }

    // ---------------------------------------------------------------- 入口
    @Override
    public MesReplyVO handle(
            Op op, String authorization, String contentEncoding, byte[] raw, String client) {
        CurrentUser cu = null;
        try {
            cu = authenticate(authorization, client);
            Map<String, Object> body = json(raw, contentEncoding, op != Op.RECEIPT);
            Map<String, Object> out =
                    switch (op) {
                        case NEXT -> {
                            onlyMes(cu);
                            yield nextBatch(
                                    cu,
                                    str(body, "pi", false),
                                    str(body, "billNo", false),
                                    str(body, "batchNo", false));
                        }
                        case RECEIPT -> {
                            onlyMes(cu);
                            yield submitReceipt(cu, str(body, "batchNo", true), body);
                        }
                        case DELIVERED ->
                                readDelivered(
                                        cu,
                                        str(body, "orgCode", false),
                                        str(body, "pi", false),
                                        str(body, "cursor", false),
                                        integer(body, "pageSize"));
                        case ITEMS ->
                                readBatchItems(
                                        cu,
                                        str(body, "batchNo", true),
                                        str(body, "cursor", false),
                                        integer(body, "pageSize"));
                    };
            return new MesReplyVO(200, MesWire.canonical(out), null);
        } catch (MesException e) {
            return error(cu, op, raw, contentEncoding, e);
        } catch (BusinessException e) {
            MesException me;
            if (e.errorCode() instanceof SnErrorCode sn) {
                me = new MesException(sn.httpStatus(), sn.name(), sn.render(e.args()));
            } else {
                me =
                        new MesException(
                                e.errorCode().httpStatus(), e.errorCode().key(), e.getMessage());
            }
            return error(cu, op, raw, contentEncoding, me);
        }
    }

    private static String externalPath(Op op) {
        return switch (op) {
            case NEXT -> "/api/mes/v1/batches/next";
            case RECEIPT -> "/api/mes/v1/receipts";
            case DELIVERED -> "/api/mes/v1/sn/delivered";
            case ITEMS -> "/api/mes/v1/batches/items";
        };
    }

    private static String actionOf(Op op) {
        return switch (op) {
            case NEXT -> AuditAction.SN_PULL;
            case RECEIPT -> AuditAction.SN_RECEIPT;
            default -> AuditAction.SN_READ;
        };
    }

    private MesReplyVO error(CurrentUser cu, Op op, byte[] raw, String enc, MesException e) {
        if (cu != null) {
            try {
                byte[] body = raw;
                if (raw != null && "gzip".equalsIgnoreCase(Util.trim(enc))) {
                    try {
                        body = gunzip(raw);
                    } catch (MesException ignored) {
                        body = null;
                    }
                }
                failures.recordMes(
                        cu, actionOf(op), externalPath(op), body, e.code + " " + e.getMessage());
            } catch (RuntimeException ignored) {
                // 失败痕迹写不进去不影响应答
            }
        }
        Map<String, Object> b = envelope(e.code, e.getMessage(), e.batchNo);
        b.put("details", e.details);
        return new MesReplyVO(e.status, MesWire.canonical(b), e.retryAfter);
    }

    /** MES 应答信封（成功与出错相同）：协议版本、结果码、说明、相关批次号、应答时间（UTC+8）。 */
    static Map<String, Object> envelope(String code, String message, String batchNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "1.0");
        m.put("code", code);
        m.put("message", message);
        m.put("batchNo", batchNo);
        m.put("timestamp", Util.mesNow());
        return m;
    }

    // ---------------------------------------------------------------- 认证
    private CurrentUser authenticate(String header, String client) {
        String token =
                header != null
                                && header.length() >= 7
                                && header.substring(0, 7).equalsIgnoreCase("bearer ")
                        ? header.substring(7).strip()
                        : "";
        if (token.isEmpty()) {
            throw new MesException(
                    401, "INVALID_CREDENTIALS", "缺少认证信息：Authorization: Bearer <工号>:<密钥>");
        }
        int colon = token.indexOf(':');
        if (colon >= 0) {
            UserEntity u;
            try {
                u =
                        users.mesAuthenticate(
                                token.substring(0, colon), token.substring(colon + 1), client);
            } catch (UserServiceImpl.MesAuthException e) {
                throw new MesException(e.status, e.code, e.getMessage());
            }
            return new CurrentUser(
                    u.id,
                    u.empNo,
                    u.name,
                    u.role,
                    u.factoryCode,
                    CurrentUser.API,
                    Trace.id(),
                    users.readScope(u.id, u.role, u.factoryCode),
                    false);
        }
        Optional<SessionService.Session> verified = sessions.verify(token);
        if (verified.isEmpty()) {
            throw new MesException(401, "TOKEN_INVALID", "令牌无效");
        }
        SessionService.Session s = verified.get();
        if (s.expired()) {
            throw new MesException(401, "TOKEN_EXPIRED", "令牌已过期，请重新登录");
        }
        if (s.revokeReason() != null) {
            if (SessionService.REASON_REPLACED.equals(s.revokeReason())) {
                throw new MesException(401, "TOKEN_INVALID", "该账户已在其他页面登录");
            }
            throw new MesException(401, "TOKEN_INVALID", "令牌已失效");
        }
        PrincipalPort.Snapshot u = principals.load(s.userId()).orElse(null);
        if (u == null || u.passwordVersion() != s.passwordVersion()) {
            throw new MesException(401, "TOKEN_INVALID", "令牌已失效");
        }
        if (u.interfaceOnly()) {
            throw new MesException(
                    401, "TOKEN_INVALID", "MES 接口账户请用 Authorization: Bearer <工号>:<密钥>");
        }
        if (!u.active()) {
            throw new MesException(403, "ACCOUNT_DISABLED", "账户已停用");
        }
        if (u.scopeMissing()) {
            throw new MesException(403, "ORG_NOT_ALLOWED", "账户未授权任何生产组织");
        }
        return new CurrentUser(
                u.userId(),
                u.empNo(),
                u.name(),
                u.role(),
                u.factoryCode(),
                CurrentUser.API,
                Trace.id(),
                u.factories(),
                Util.isSuperAdmin(u.empNo(), u.role(), settings.initAdminEmpNo()));
    }

    private static void onlyMes(CurrentUser cu) {
        if (!cu.isMes()) {
            throw new MesException(403, "FORBIDDEN", "只有 MES 接口账户能调用");
        }
    }

    // ---------------------------------------------------------------- 请求体
    /**
     * 解压 gzip 请求体，最多解出 {@link #MAX_INFLATED} 字节；空请求体按空处理，多段 gzip 依次解压拼接，段后补的 0 字节忽略，其他尾随内容按不合法处理。
     */
    static byte[] gunzip(byte[] raw) {
        MesException tooLarge =
                new MesException(400, "INVALID_REQUEST", "请求体解压后超过 " + MAX_INFLATED / 1024 + " KB");
        MesException invalid = new MesException(400, "INVALID_REQUEST", "请求体不是合法的 gzip");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        byte[] buf = new byte[8192];
        while (pos < raw.length) {
            int start = pos;
            // gzip 头（RFC 1952）
            if (raw.length - pos < 10
                    || (raw[pos] & 0xff) != 0x1f
                    || (raw[pos + 1] & 0xff) != 0x8b
                    || raw[pos + 2] != 8) {
                throw invalid;
            }
            int flg = raw[pos + 3] & 0xff;
            pos += 10;
            try {
                if ((flg & 4) != 0) {
                    int xlen = (raw[pos] & 0xff) | ((raw[pos + 1] & 0xff) << 8);
                    pos += 2 + xlen;
                }
                if ((flg & 8) != 0) {
                    while (raw[pos] != 0) {
                        pos++;
                    }
                    pos++;
                }
                if ((flg & 16) != 0) {
                    while (raw[pos] != 0) {
                        pos++;
                    }
                    pos++;
                }
                if ((flg & 2) != 0) {
                    pos += 2;
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                throw invalid;
            }
            if (pos > raw.length) {
                throw invalid;
            }
            Inflater inf = new Inflater(true);
            long member = 0;
            try {
                inf.setInput(raw, pos, raw.length - pos);
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) {
                            throw invalid;
                        }
                    }
                    out.write(buf, 0, n);
                    member += n;
                    if (out.size() > MAX_INFLATED) {
                        throw tooLarge;
                    }
                }
                pos = raw.length - inf.getRemaining();
            } catch (DataFormatException e) {
                throw invalid;
            } finally {
                inf.end();
            }
            // 尾部 CRC32 + ISIZE
            if (raw.length - pos < 8) {
                throw invalid;
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            byte[] all = out.toByteArray();
            crc.update(all, (int) (all.length - member), (int) member);
            long want =
                    (raw[pos] & 0xffL)
                            | ((raw[pos + 1] & 0xffL) << 8)
                            | ((raw[pos + 2] & 0xffL) << 16)
                            | ((raw[pos + 3] & 0xffL) << 24);
            if (want != crc.getValue()) {
                throw invalid;
            }
            pos += 8;
            while (pos < raw.length && raw[pos] == 0) {
                pos++;
            }
            if (pos == start) {
                throw invalid;
            }
        }
        return out.toByteArray();
    }

    private static Map<String, Object> json(byte[] raw, String encoding, boolean gzipOk) {
        byte[] data = raw == null ? new byte[0] : raw;
        if ("gzip".equalsIgnoreCase(Util.trim(encoding))) {
            if (!gzipOk) {
                throw new MesException(400, "INVALID_REQUEST", "本接口不支持 gzip，请发原始 JSON");
            }
            data = gunzip(data);
        }
        if (data.length == 0) {
            return new LinkedHashMap<>();
        }
        Object v;
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data))
                            .toString();
            v = JSON.readValue(text, Object.class);
        } catch (CharacterCodingException | RuntimeException e) {
            throw new MesException(400, "INVALID_REQUEST", "请求体无法解析");
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new MesException(400, "INVALID_REQUEST", "请求体须为 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    private static String str(Map<String, Object> body, String field, boolean required) {
        Object v = body.get(field);
        if (v == null || (required && v instanceof String s && s.isBlank())) {
            if (required) {
                throw MesException.bad(field, "必填");
            }
            return null;
        }
        if (!(v instanceof String s)) {
            throw MesException.bad(field, "须为字符串");
        }
        return s;
    }

    private static Integer integer(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (v == null) {
            return null;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return l > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : l < Integer.MIN_VALUE ? Integer.MIN_VALUE : l.intValue();
        }
        if (v instanceof BigInteger b) {
            return b.signum() > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        throw MesException.bad(field, "须为整数");
    }

    // ---------------------------------------------------------------- 限流
    private void rateLimit(String org) {
        LocalDateTime now = Util.now();
        LocalDateTime win = now.withSecond(0);
        String key = "mes:" + org;
        long n =
                tx.isolated(
                        () -> {
                            mapper.rateBump(key, win);
                            Long v = mapper.rateValue(key, win);
                            return v == null ? 0L : v;
                        });
        if (n > settings.mesPullRatePerMinute()) {
            throw new MesException(
                    429, "RATE_LIMITED", "请求过于频繁", null, null, Math.max(1, 60 - now.getSecond()));
        }
    }

    // ---------------------------------------------------------------- ② 取下一批
    private static final String PENDING = "PENDING";
    private static final String PENDING_SCOPED = "PENDING_SCOPED";
    private static final String FAILED_PULLED = "FAILED_PULLED";

    private long[] remaining(
            String org, boolean mes, String pi, String bill, Long exclude, Long firstTry) {
        long batched = mapper.qtyInScope(org, mes, pi, bill, PENDING, exclude);
        long unb = mes ? mapper.unbatchedCount(org, pi, bill, firstTry == null ? 0 : firstTry) : 0;
        long paused = mapper.pausedCount(org, pi, bill);
        return new long[] {unb + batched, paused};
    }

    Map<String, Object> nextBatch(CurrentUser cu, String piIn, String billIn, String batchNoIn) {
        String org = cu.factoryCode();
        rateLimit(org);
        FactoryEntity f = factories.mustGet(org);
        boolean mes = f.mesEnabled;
        String pi = Util.trimOrNull(piIn);
        String bill = Util.trimOrNull(billIn);
        String batchNo = Util.trimOrNull(batchNoIn);
        if (batchNo != null) {
            Map<String, Object> b = batches.findBatch(batchNo, false);
            if (b == null || !org.equals(R.s(b, "factory_code"))) {
                throw new MesException(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batchNo);
            }
            if (VOIDED.equals(R.s(b, "result"))) {
                throw new MesException(409, "BATCH_VOIDED", "该批已作废，丢弃这批内容", batchNo);
            }
            if (SUCCESS.equals(R.s(b, "result"))) {
                throw new MesException(409, "BATCH_CONFIRMED", "该批已确认，不用再处理", batchNo);
            }
            long[] rp = remaining(org, mes, null, null, R.l(b, "id"), null);
            return nextOut(b, rp[0], rp[1]);
        }
        if (pi != null && !(mapper.piHasItems(org, pi) || mapper.piHasTransferOut(org, pi))) {
            throw new MesException(403, "ORG_NOT_ALLOWED", "PI " + pi + " 下没有号分到本组织，也没有本组织的转出批次");
        }
        Object[] res =
                locks.withOrgLock(
                        org,
                        10,
                        () -> {
                            boolean created = false;
                            Map<String, Object> b =
                                    mapper.firstInScope(
                                            org,
                                            mes,
                                            pi,
                                            bill,
                                            pi != null || bill != null ? PENDING_SCOPED : PENDING,
                                            null,
                                            false);
                            if (b == null && mes) {
                                String targetPi = pi != null ? pi : mapper.firstPoolPi(org, bill);
                                if (targetPi != null) {
                                    Map<String, Object> blocking =
                                            mapper.blockingFailed(org, targetPi);
                                    if (blocking != null) {
                                        b = blocking;
                                    } else {
                                        b =
                                                batches.buildDelivery(
                                                        cu, org, targetPi, SRC_PULL, bill);
                                        created = b != null;
                                    }
                                }
                            }
                            if (b == null) {
                                b =
                                        mapper.firstInScope(
                                                org, mes, pi, bill, FAILED_PULLED, null, true);
                            }
                            Long firstTry = null;
                            if (b != null) {
                                if (FAILED.equals(R.s(b, "result")) && b.get("pulled_at") == null) {
                                    firstTry = R.l(b, "id");
                                }
                                mapper.markPulled(R.l(b, "id"), Util.now());
                            }
                            return new Object[] {b, created, firstTry};
                        });
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) res[0];
        boolean created = (Boolean) res[1];
        Long firstTry = (Long) res[2];
        long[] rp = remaining(org, mes, pi, bill, b == null ? null : R.l(b, "id"), firstTry);
        if (b == null) {
            changes.countDaily("MES_PULL_EMPTY", org);
            return nextOut(null, rp[0], rp[1]);
        }
        audit.recordIsolated(
                cu,
                AuditEntryDTO.of(AuditAction.SN_PULL)
                        .factory(org)
                        .pi(pi != null ? pi : R.s(b, "pi_no"))
                        .bill(bill)
                        .count(R.l(b, "qty"))
                        .batch(R.s(b, "batch_no"))
                        .info(
                                "scope=pi:"
                                        + (pi != null ? pi : "-")
                                        + " bill:"
                                        + (bill != null ? bill : "-")
                                        + " new="
                                        + (created ? 1 : 0)
                                        + " type="
                                        + R.s(b, "batch_type")));
        return nextOut(batches.mustBatch(R.s(b, "batch_no"), false), rp[0], rp[1]);
    }

    private static Map<String, Object> nextOut(Map<String, Object> b, long remaining, long paused) {
        if (b == null) {
            Map<String, Object> m =
                    envelope(
                            "NO_BATCH",
                            paused > 0 ? "暂无可拉取批次，" + paused + " 枚暂停下发" : "暂无可拉取批次",
                            null);
            m.put("batch", null);
            m.put("remaining", remaining);
            m.put("paused", paused);
            return m;
        }
        Map<String, Object> m = envelope("OK", "成功", R.s(b, "batch_no"));
        m.put("batch", BatchServiceImpl.bodyObj(b));
        m.put("remaining", remaining);
        m.put("paused", paused);
        return m;
    }

    // ---------------------------------------------------------------- ③ 回执
    Map<String, Object> submitReceipt(CurrentUser cu, String batchNo, Map<String, Object> body) {
        String org = cu.factoryCode();
        rateLimit(org);
        Object code = body.get("code");
        if (!RECEIPT_OK.equals(code) && !RECEIPT_NG.equals(code)) {
            throw new MesException(
                    400,
                    "INVALID_REQUEST",
                    "参数不合法：code",
                    batchNo,
                    List.of(Map.of("field", "code", "reason", "只能是 OK / NG")),
                    null);
        }
        Map<String, Object> b = batches.findBatch(batchNo, false);
        if (b == null
                || !R.s(b, "batch_no").equals(batchNo)
                || !org.equals(R.s(b, "factory_code"))) {
            throw new MesException(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batchNo);
        }
        if (VOIDED.equals(R.s(b, "result"))) {
            throw new MesException(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batchNo);
        }
        ReceiptServiceImpl.Applied applied = receipts.applyReceipt(cu, R.s(b, "batch_no"), body);
        if ("VOIDED".equals(applied.verdict())) {
            throw new MesException(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batchNo);
        }
        Map<String, Object> m =
                envelope("OK", RECEIPT_MSG.get(applied.verdict()), R.s(b, "batch_no"));
        m.put("batchResult", R.s(applied.batch(), "result"));
        m.put("firstConfirmation", !"ARCHIVED".equals(applied.verdict()));
        return m;
    }

    // ---------------------------------------------------------------- ④⑤ 只读
    private static long cursor(String v) {
        String t = Util.trim(v);
        if (t.isEmpty()) {
            return 0;
        }
        if (!t.chars().allMatch(c -> c >= '0' && c <= '9') || t.length() > 18) {
            throw new MesException(
                    400,
                    "INVALID_REQUEST",
                    "参数不合法：cursor",
                    null,
                    List.of(Map.of("field", "cursor", "reason", "须为上一页返回的 nextCursor")),
                    null);
        }
        return Long.parseLong(t);
    }

    private static int pageSize(Integer v) {
        return v == null || v < 1 ? 1000 : Math.min(v, 5000);
    }

    private Map<String, Object> readItem(
            Map<String, Object> r, Map<Long, Map<String, Object>> details) {
        Map<String, Object> imp = BatchServiceImpl.importedItem(r, details);
        Map<String, Object> m;
        if (imp != null) {
            m = imp;
        } else {
            m = new LinkedHashMap<>();
            m.put("sn", R.s(r, "sn"));
            m.put("rev", R.l(r, "rev"));
            m.put("serial", rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")));
            m.put("serialDec", String.valueOf(R.l(r, "seq_dec")));
            m.put("serialPi", R.s(r, "seq_pi_no"));
            m.put("pi", R.s(r, "pi_no"));
            m.put("billNo", R.s(r, "bill_no"));
            m.put("billSeq", R.l(r, "bill_seq"));
            m.put("attachNo", BatchServiceImpl.strOrNull(R.s(r, "attach_no")));
            m.put("materialCode", BatchServiceImpl.strOrNull(R.s(r, "material_code")));
            m.put("length", R.s(r, "length_text"));
            m.put("customerCode", R.s(r, "customer_code"));
        }
        m.put("status", R.s(r, "status"));
        m.put("batchNo", R.s(r, "delivery_batch_no"));
        m.put("deliveredAt", Util.mesFmt(R.t(r, "delivered_at")));
        return m;
    }

    private Map<String, Object> page(String batchNo, List<Map<String, Object>> rows, int n) {
        boolean more = rows.size() > n;
        List<Map<String, Object>> page = more ? rows.subList(0, n) : rows;
        Map<Long, Map<String, Object>> details = batches.importDetails(page);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : page) {
            items.add(readItem(r, details));
        }
        Map<String, Object> m = envelope("OK", "成功", batchNo);
        m.put("items", items);
        m.put("nextCursor", more ? String.valueOf(R.l(page.get(page.size() - 1), "id")) : null);
        return m;
    }

    Map<String, Object> readDelivered(
            CurrentUser cu, String orgIn, String piIn, String cursorIn, Integer size) {
        String org;
        boolean holder;
        if (cu.isMes()) {
            rateLimit(cu.factoryCode());
            org = cu.factoryCode();
            holder = true;
        } else {
            org = Util.trim(orgIn);
            if (org.isEmpty()) {
                throw new MesException(400, "INVALID_REQUEST", "orgCode 必填");
            }
            if (!cu.canRead(org)) {
                throw new MesException(403, "ORG_NOT_ALLOWED", "无权查看该生产组织");
            }
            holder = false;
        }
        int n = pageSize(size);
        long cur = cursor(cursorIn);
        List<Map<String, Object>> rows =
                mapper.readDelivered(org, holder, Util.trimOrNull(piIn), cur, n + 1);
        return page(null, rows, n);
    }

    Map<String, Object> readBatchItems(
            CurrentUser cu, String batchNo, String cursorIn, Integer size) {
        if (cu.isMes()) {
            rateLimit(cu.factoryCode());
        }
        Map<String, Object> b = batches.findBatch(batchNo, false);
        if (b == null
                || !(cu.isMes()
                        ? R.s(b, "factory_code").equals(cu.factoryCode())
                        : cu.canRead(R.s(b, "factory_code")))) {
            throw new MesException(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batchNo);
        }
        int n = pageSize(size);
        long cur = cursor(cursorIn);
        List<Map<String, Object>> rows =
                mapper.readBatchItems(R.s(b, "batch_no"), R.s(b, "factory_code"), cur, n + 1);
        return page(R.s(b, "batch_no"), rows, n);
    }
}
