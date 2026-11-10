package com.adtek.framework.audit;

import com.adtek.common.core.ErrorCode;
import com.adtek.framework.security.AuthPrincipal;

import java.util.Map;

/**
 * 请求拒绝审计端口：已登录账户的 {@code /api} 写请求被拒绝或失败（业务拒绝、参数不合法、冲突、系统错误）时，由 framework 全局异常处理调用，
 * 提供方在独立事务中记一条失败痕迹。
 *
 * <p>权限：只由 framework 调用，不做授权检查。范围：主体为服务端会话解析的当前账户。事务：提供方自行开启独立事务（REQUIRES_NEW），
 * 写失败只记日志、不得抛出（不能盖掉原始错误响应）。输入：{@code pathTemplate} 为路由模板（不含动态 ID 与查询串）， {@code body} 为不超过 64KB
 * 的原始请求体（提供方只抽取白名单字段，不整体落库）；{@code errorCode/args} 为返回给客户端的错误。
 */
public interface FailureAuditPort {
    void recordFailure(
            AuthPrincipal principal,
            String method,
            String path,
            String pathTemplate,
            byte[] body,
            ErrorCode errorCode,
            Map<String, String> args);
}
