package com.adtek.equipment.service.impl;

import com.adtek.framework.audit.RequestAuditRoutes;

import org.springframework.stereotype.Component;

/** 拒绝日志只保留模板，不记录请求体、名称或动态 ID。 */
@Component
public class EquipmentRequestAuditRoutes implements RequestAuditRoutes {
    public String template(String method, String path) {
        if (path.equals("/api/v1/equipment/assets")) return path;
        if (path.matches("/api/v1/equipment/assets/[^/]+")) return "/api/v1/equipment/assets/{id}";
        if (path.matches("/api/v1/equipment/assets/[^/]+/update"))
            return "/api/v1/equipment/assets/{id}/update";
        return "/api/**";
    }
}
