package com.adtek.sn.service.impl;

import com.adtek.framework.audit.RequestAuditRoutes;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** 仅持久化已登记SN路径模板，不记录客户单号、组织名或原始查询串。 */
@Component
public class SnRequestAuditRoutes implements RequestAuditRoutes {
    private static final List<String> PATHS =
            List.of(
                    "/context",
                    "/users",
                    "/users/{id}/update",
                    "/users/{id}/status",
                    "/users/{id}/password",
                    "/factories",
                    "/factories/sync",
                    "/factories/organizations",
                    "/factories/{id}/organization",
                    "/orders",
                    "/orders/meta",
                    "/orders/customers",
                    "/orders/pis",
                    "/orders/sync",
                    "/orders/syncs",
                    "/orders/sync-schedule",
                    "/orders/{id}/lines",
                    "/rules",
                    "/rules/preview",
                    "/rules/effective",
                    "/rules/{id}/update",
                    "/rules/{id}/versions",
                    "/pi-initializations",
                    "/pi-initializations/history",
                    "/generation/context",
                    "/generation/rules",
                    "/generation/history",
                    "/generation/previews",
                    "/generation",
                    "/allocations",
                    "/operations/PI_INIT/{id}",
                    "/operations/GENERATE/{id}",
                    "/operations/ALLOCATE/{id}",
                    "/acquisitions/pis",
                    "/acquisitions/segments",
                    "/acquisitions",
                    "/acquisitions/operations/{id}/{id}",
                    "/acquisitions/results/{id}",
                    "/acquisitions/results/{id}/items",
                    "/acquire-batches",
                    "/acquire-batches/{id}/items",
                    "/prints",
                    "/prints/operations/{id}",
                    "/prints/{id}",
                    "/transfers",
                    "/transfers/candidates",
                    "/transfers/target-hint",
                    "/transfers/{id}/target-hint",
                    "/transfers/targets",
                    "/transfers/factories",
                    "/transfers/direct",
                    "/transfers/{id}/approve",
                    "/transfers/{id}/reject",
                    "/transfers/{id}/withdraw",
                    "/transfers/{id}/items",
                    "/transfers/operations/{id}/{id}",
                    "/transfers/{id}",
                    "/audits",
                    "/audits/count",
                    "/audits/meta",
                    "/audits/exports",
                    "/audits/exports/operations/{id}/{id}",
                    "/audits/{id}",
                    "/items",
                    "/items/count",
                    "/exports",
                    "/exports/operations/{id}/{id}",
                    "/dashboard");

    @Override
    public String template(String method, String path) {
        for (String route :
                List.of(
                        "/acquisitions",
                        "/print-confirmations",
                        "/operations/{id}/{id}",
                        "/results/{id}",
                        "/results/{id}/items",
                        "/acquire-batches/{id}/items",
                        "/items")) {
            String template = "/api/v1/integrations/sn" + route;
            if (path.matches(Pattern.quote(template).replace("{id}", "\\E[^/]+\\Q")))
                return template;
        }
        for (String route : PATHS) {
            String template = "/api/v1/sn" + route;
            if (path.matches(Pattern.quote(template).replace("{id}", "\\E[^/]+\\Q")))
                return template;
        }
        return "/api/**";
    }
}
