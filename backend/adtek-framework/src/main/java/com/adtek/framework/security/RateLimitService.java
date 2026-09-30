package com.adtek.framework.security;

import java.time.Duration;

/** 多实例共享的入口预算；与 SN 业务额度无关，不能作为业务数量上限。 */
public interface RateLimitService {
    /**
     * 原子检查主体/动作滑动窗口；接受后消耗一次预算，超限 429 并提供 Retry-After 秒数。
     * action 来自代码白名单，subject 只能是账号、可信来源地址或已认证主体标识，不得传入密码/token。
     * Redis 不可用返回 503，不静默放行；不自动重试业务写入。
     */
    void require(String action, String subject, int maximum, Duration window);
}
