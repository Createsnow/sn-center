package com.adtek.system.service.impl;

import com.adtek.framework.audit.RequestAuditRoutes;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** 只登记已发布的路径模板；动态段不入库，查询串永不读取，未知接口合并为固定模板。 */
@Component
public class SystemRequestAuditRoutes implements RequestAuditRoutes {
    private final List<Route> routes;

    public SystemRequestAuditRoutes() {
        List<String> paths = new ArrayList<>();
        for (String action :
                List.of("csrf", "login", "refresh", "me", "logout", "password", "language")) {
            paths.add("/auth/" + action);
        }
        paths.addAll(
                List.of(
                        "/users",
                        "/users/options",
                        "/menus/current",
                        "/audits",
                        "/audits/options",
                        "/audits/{id}",
                        "/audit-exports",
                        "/audit-exports/operations/{id}",
                        "/service-clients",
                        "/service-clients/{id}",
                        "/service-clients/{id}/update",
                        "/service-clients/{id}/rotate"));
        for (String action : List.of("update", "status", "password")) {
            paths.add("/users/{id}/" + action);
        }
        for (String resource :
                List.of("roles", "departments", "menus", "dictionary-types", "dictionary-items")) {
            paths.add("/" + resource);
            paths.add("/" + resource + "/{id}/update");
        }
        routes =
                paths.stream()
                        .map(
                                path ->
                                        new Route(
                                                "/api/v1/system" + path,
                                                Pattern.compile(
                                                        Pattern.quote("/api/v1/system" + path)
                                                                .replace("{id}", "\\E[^/]+\\Q"))))
                        .toList();
    }

    @Override
    public String template(String method, String servletPath) {
        if ("/api/v1/integrations/auth/token".equals(servletPath)) return servletPath;
        for (String path :
                List.of(
                        "/api/v1/tasks/{id}",
                        "/api/v1/tasks/{id}/cancel",
                        "/api/v1/tasks/{id}/recover",
                        "/api/v1/files/{id}")) {
            if (servletPath.matches(Pattern.quote(path).replace("{id}", "\\E[^/]+\\Q"))) {
                return path;
            }
        }
        return routes.stream()
                .filter(route -> route.pattern().matcher(servletPath).matches())
                .findFirst()
                .map(Route::template)
                .orElse("/api/**");
    }

    private record Route(String template, Pattern pattern) {}
}
