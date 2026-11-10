package com.adtek.framework.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

/**
 * 当前主体：由 {@link SessionAuthenticationFilter} 按服务端会话与 {@link PrincipalPort} 的当前授权解析，业务只从这里读取操作人，
 * 不接受客户端提交的操作者或权限。
 *
 * @param userId 账户 ID；系统任务为 null
 * @param empNo 工号
 * @param name 姓名
 * @param role 角色（业务模块定义取值）
 * @param factoryCode 账户直接绑定的组织（MES 接口账户），可为 null
 * @param factories 当前授权范围（业务模块解释；空列表的含义由角色决定）
 * @param source 来源：PAGE 页面会话 / API 对接方 / SYSTEM 定时任务
 * @param sessionId 服务端会话 ID；非会话主体为 null
 */
public record AuthPrincipal(
        Long userId,
        String empNo,
        String name,
        String role,
        String factoryCode,
        List<String> factories,
        String source,
        String sessionId) {
    public static final String PAGE = "PAGE";
    public static final String API = "API";
    public static final String SYSTEM = "SYSTEM";

    public AuthPrincipal {
        factories = factories == null ? List.of() : List.copyOf(factories);
    }

    /** 当前请求的主体；匿名请求为空。 */
    public static Optional<AuthPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
