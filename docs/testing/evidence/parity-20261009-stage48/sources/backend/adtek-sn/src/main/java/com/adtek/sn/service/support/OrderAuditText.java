package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.domain.SnAuditEntity;

/** 订单痕迹的原型文本；仅接收受控错误参数，不接受原始异常或HTTP请求体。 */
public final class OrderAuditText {
    private OrderAuditText() {}

    /** 定时任务保留内部责任人授权，但业务痕迹与原型一样显示系统执行身份。 */
    public static void system(SnAuditEntity row) {
        row.setActorId(null);
        row.setOperator("system");
        row.setOperatorName("系统");
        row.setRoles("[\"SN_HQ\"]");
        row.setSource("SYSTEM");
        row.setTraceId(null);
    }

    public static String message(BusinessException error) {
        return message(error.getError().code(), error.getParams().getOrDefault("message", ""));
    }

    /** 只拼接受适配器约束的K3摘要，内部存储异常使用固定文案。 */
    public static String message(int code, String safeMessage) {
        return switch (code) {
            case 1005000002 -> "金蝶登录失败：" + safeMessage;
            case 1005000006 -> "金蝶查询失败：" + safeMessage;
            case 1000000004, 1003000004, 1002000011 -> "当前角色无权执行该操作";
            case 1000000008, 1002000008 -> "数据正被其他操作占用，请稍后重试";
            default -> "订单同步失败";
        };
    }
}
