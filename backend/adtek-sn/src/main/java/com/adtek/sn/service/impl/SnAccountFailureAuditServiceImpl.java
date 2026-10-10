package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.audit.AccountAuditSnapshotPort;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.mapper.SnAuditMapper;
import com.adtek.sn.service.SnAccountFailureAuditService;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 只记录允许的账号拒绝事实；成功事务已回滚后读取非秘密身份，审计异常不得覆盖业务错误。 */
@Service
public class SnAccountFailureAuditServiceImpl implements SnAccountFailureAuditService {
    private final AccountAuditSnapshotPort identities;
    private final FactoryMapper factories;
    private final SnAuditMapper audits;
    private final ObjectMapper json;

    public SnAccountFailureAuditServiceImpl(
            AccountAuditSnapshotPort identities,
            FactoryMapper factories,
            SnAuditMapper audits,
            ObjectMapper json) {
        this.identities = identities;
        this.factories = factories;
        this.audits = audits;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            long actorId, String action, String path, String factoryId, BusinessException error) {
        var actor = identities.read(actorId).orElse(null);
        if (actor == null || !"USER".equals(actor.principalType())) return;
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(actorId);
        row.setOperator(actor.username());
        row.setOperatorName(actor.displayName());
        row.setRoles(json.writeValueAsString(actor.roleCodes()));
        row.setSource("PAGE");
        row.setAction(action);
        row.setResult("FAILED");
        row.setTraceId(MDC.get("traceId"));
        row.setDetail("{}");
        row.setDetailText(path);
        if (factoryId != null && factoryId.matches("[1-9][0-9]{0,18}")) {
            try {
                var factory = factories.selectById(Long.parseLong(factoryId));
                if (factory != null) {
                    row.setFactoryId(factory.getId());
                    row.setFactoryCode(factory.getCode());
                    row.setFactoryName(factory.getName());
                }
            } catch (NumberFormatException ignored) {
                /* 未解析的业务ID不进入SQL。 */
            }
        }
        row.setErrorCode(Integer.toString(error.getError().code()));
        row.setErrorMessage(message(error, factoryId));
        audits.insert(row);
        audits.creationIdentity(row);
    }

    private static String message(BusinessException error, String factoryId) {
        String reason = error.getParams().getOrDefault("reason", "");
        if (error.getError().code() == 1004000030 || error.getError().code() == 1004000032) {
            return switch (reason) {
                case "USER_EMP_NO_INVALID" -> "工号须为 2–32 位字母、数字、下划线或横线";
                case "USER_EMP_NO_EXISTS" ->
                        "工号已存在：" + error.getParams().getOrDefault("emp_no", "");
                case "USER_NAME_REQUIRED" -> "姓名必填";
                case "USER_ROLE_INVALID" -> "角色无效";
                case "USER_FACTORY_REQUIRED" -> "厂区操作员必须绑定工厂";
                case "USER_ADMIN_NO_FACTORY" -> "系统管理员不绑定工厂";
                case "USER_PASSWORD_WEAK" -> "密码须为 8–64 位，且同时包含字母和数字";
                case "USER_SELF_DISABLE" -> "不能停用自己的账户";
                case "USER_LAST_ADMIN" -> "系统须保留至少一名启用中的管理员";
                case "FACTORY_NOT_FOUND" ->
                        "工厂不存在："
                                + (factoryId == null
                                        ? ""
                                        : factoryId.substring(0, Math.min(64, factoryId.length())));
                default -> error.getError().message();
            };
        }
        return switch (error.getError().code()) {
            case 1000000004, 1003000004, 1002000011 -> "当前角色无权执行该操作";
            case 1002000010 -> "系统须保留至少一名启用中的管理员";
            case 1000000005 -> "账户不存在";
            case 1000000008, 1002000008 -> "数据正被其他操作占用，请稍后重试";
            default -> error.getError().message();
        };
    }
}
