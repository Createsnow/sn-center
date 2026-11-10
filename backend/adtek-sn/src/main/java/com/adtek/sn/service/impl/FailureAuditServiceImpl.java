package com.adtek.sn.service.impl;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.common.core.ErrorCode;
import com.adtek.framework.audit.FailureAuditPort;
import com.adtek.framework.security.AuthErrorCode;
import com.adtek.framework.security.AuthPrincipal;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Trace;
import com.adtek.sn.support.Util;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 请求拒绝审计（framework {@link FailureAuditPort} 的提供方）：已登录账户的 /api 写请求被拒绝或失败时独立事务记一条 FAIL；动作按路由映射， PI /
 * 生产组织 / 单据 / 数量等只从请求体的白名单字段抽取，明细只记方法与路由模板（不含动态 ID 与查询串）。
 */
@Service
public class FailureAuditServiceImpl implements FailureAuditPort {
    static final String PREFIX = "/api/v1/sn";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Map.Entry<Pattern, String>> PATH_ACTIONS =
            List.of(
                    rule("/auth/password", AuditAction.PASSWORD_CHANGE),
                    rule("/users", AuditAction.USER_CREATE),
                    rule("/users/\\d+/disable", AuditAction.USER_DISABLE),
                    rule("/users/\\d+/enable", AuditAction.USER_ENABLE),
                    rule("/users/\\d+/reset-password", AuditAction.USER_RESET_PWD),
                    rule("/users/\\d+/reset-secret", AuditAction.USER_RESET_SECRET),
                    rule("/users/\\d+/update", AuditAction.USER_UPDATE),
                    rule("/factories/sync", AuditAction.FACTORY_SYNC),
                    rule("/factories/[^/]+/mes-config", AuditAction.FACTORY_MES_CONFIG),
                    rule("/factories/[^/]+/mes-test", AuditAction.FACTORY_MES_TEST),
                    rule("/factories/[^/]+/mes-toggle", AuditAction.FACTORY_MES_TOGGLE),
                    rule("/k3-config/test", AuditAction.K3_CONFIG_TEST),
                    rule("/k3-config/reset", AuditAction.K3_CONFIG),
                    rule("/k3-config", AuditAction.K3_CONFIG),
                    rule("/orders/attach", AuditAction.ORDER_ATTACH_FILL),
                    rule("/orders/length", AuditAction.ORDER_LENGTH_FILL),
                    rule("/orders/unit", AuditAction.ORDER_UNIT_FILL),
                    rule("/orders/alerts/\\d+/handle", AuditAction.ORDER_ALERT_HANDLE),
                    rule("/orders/.*", AuditAction.ORDER_SYNC),
                    rule("/rules/line-(select|clear)", AuditAction.RULE_LINE_SELECT),
                    rule("/rules/pi-binding/delete", AuditAction.RULE_PI_UNBIND),
                    rule("/rules/pi-binding", AuditAction.RULE_PI_BIND),
                    rule("/rules/\\d+/delete", AuditAction.RULE_DELETE),
                    rule("/rules/\\d+/update", AuditAction.RULE_UPDATE),
                    rule("/rules", AuditAction.RULE_CREATE),
                    rule("/generate/batches/[^/]+/cancel", AuditAction.SN_GEN_CANCEL),
                    rule("/generate/delete", AuditAction.SN_DELETE),
                    rule("/generate/revoke-config", AuditAction.LINE_CONFIG_REVOKE),
                    rule("/generate/.*", AuditAction.SN_GENERATE),
                    rule("/allocation/.*", AuditAction.SN_ALLOCATE),
                    rule("/sn-import/imports/[^/]+/delete", AuditAction.SN_IMPORT_DELETE),
                    rule("/sn-import(/.*)?", AuditAction.SN_IMPORT),
                    rule("/delivery/batches/[^/]+/confirm", AuditAction.SN_BATCH_MANUAL),
                    rule("/delivery/batches/[^/]+/void", AuditAction.SN_BATCH_VOID),
                    rule("/delivery/resume", AuditAction.SN_PUSH_RESUME),
                    rule("/delivery/alerts/\\d+/handle", AuditAction.SN_ALERT_HANDLE),
                    rule("/transfers/bill.*", AuditAction.TRANSFER_BILL),
                    rule("/transfers/org/direct", AuditAction.TRANSFER_DIRECT));

    private final AuditServiceImpl audit;
    private final SnSettings settings;

    public FailureAuditServiceImpl(AuditServiceImpl audit, SnSettings settings) {
        this.audit = audit;
        this.settings = settings;
    }

    private static Map.Entry<Pattern, String> rule(String regex, String action) {
        return Map.entry(Pattern.compile(regex), action);
    }

    static String actionFor(String path) {
        String p = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
        for (Map.Entry<Pattern, String> r : PATH_ACTIONS) {
            if (r.getKey().matcher(p).matches()) {
                return r.getValue();
            }
        }
        return AuditAction.OTHER;
    }

    /** 失败说明（中文，与原系统一致）。 */
    static String message(ErrorCode code, Map<String, String> args) {
        if (code instanceof SnErrorCode sn) {
            return sn.render(args);
        }
        if (code instanceof AuthErrorCode a) {
            return a.message();
        }
        if (code == CommonErrorCode.INVALID_REQUEST) {
            return "参数不合法：" + args.getOrDefault("fields", "body");
        }
        if (code == CommonErrorCode.CONFLICT) {
            return "数据正被其他操作占用，请稍后重试";
        }
        if (code == CommonErrorCode.FORBIDDEN) {
            return SnErrorCode.FORBIDDEN.zh();
        }
        if (code == CommonErrorCode.PAYLOAD_TOO_LARGE) {
            return "请求体过大";
        }
        return "系统内部错误";
    }

    @Override
    public void recordFailure(
            AuthPrincipal principal,
            String method,
            String path,
            String pathTemplate,
            byte[] body,
            ErrorCode errorCode,
            Map<String, String> args) {
        CurrentUser u =
                new CurrentUser(
                        principal.userId(),
                        principal.empNo(),
                        principal.name(),
                        principal.role(),
                        principal.factoryCode(),
                        principal.source(),
                        Trace.id(),
                        principal.factories(),
                        Util.isSuperAdmin(
                                principal.empNo(), principal.role(), settings.initAdminEmpNo()));
        AuditEntryDTO e = AuditEntryDTO.of(actionFor(path)).fail(message(errorCode, args));
        if (body != null && body.length > 0) {
            JsonNode m = null;
            try {
                m = JSON.readTree(body);
            } catch (RuntimeException ignored) {
                // 非 JSON 请求体：只记动作与原因
            }
            if (m != null && m.isObject()) {
                String factory = first(m, "factoryCode");
                e.factory(factory != null ? factory : u.factoryCode())
                        .pi(first(m, "piNo", "pi"))
                        .bill(first(m, "billNo", "fromBill"))
                        .material(first(m, "materialCode"))
                        .request(first(m, "requestNo"))
                        .batch(first(m, "batchNo"))
                        .why(first(m, "reason"));
                JsonNode q = m.get("qty");
                if (q != null && q.isNumber()) {
                    e.count(q.asLong());
                }
            }
        }
        e.info(method + " " + pathTemplate);
        audit.recordIsolated(u, e);
    }

    /** MES 接口被拒绝的请求（越权、限流、参数不合法、批次不存在等）：按原系统的对外路径记失败痕迹。 */
    public void recordMes(
            CurrentUser u, String action, String externalPath, byte[] body, String message) {
        AuditEntryDTO e = AuditEntryDTO.of(action).fail(message);
        if (body != null && body.length > 0) {
            JsonNode m = null;
            try {
                m = JSON.readTree(body);
            } catch (RuntimeException ignored) {
                // 非 JSON 请求体：只记动作与原因
            }
            if (m != null && m.isObject()) {
                String factory = first(m, "factoryCode");
                e.factory(factory != null ? factory : u.factoryCode())
                        .pi(first(m, "piNo", "pi"))
                        .bill(first(m, "billNo", "fromBill"))
                        .batch(first(m, "batchNo"))
                        .why(first(m, "reason"));
                JsonNode q = m.get("qty");
                if (q != null && q.isNumber()) {
                    e.count(q.asLong());
                }
            }
        }
        e.info("POST " + externalPath);
        audit.recordIsolated(u, e);
    }

    private static String first(JsonNode m, String... keys) {
        for (String k : keys) {
            JsonNode v = m.get(k);
            if (v != null && v.isString() && !v.asString().isBlank()) {
                return v.asString().strip();
            }
        }
        return null;
    }
}
