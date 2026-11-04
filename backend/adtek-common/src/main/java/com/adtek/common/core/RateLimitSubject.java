package com.adtek.common.core;

/** 只暴露限流所需的账号或公开会话标识，不能返回密码、原始 token 或整个请求体。 */
public interface RateLimitSubject {
    String rateLimitSubject();
}
