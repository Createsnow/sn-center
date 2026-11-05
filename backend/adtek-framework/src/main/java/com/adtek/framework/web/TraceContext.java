package com.adtek.framework.web;

import org.slf4j.MDC;

import java.util.UUID;

/** 当前请求追踪号：由 {@link TraceIdFilter} 写入日志 MDC，响应信封与 {@value #HEADER} 头使用同一值。 */
public final class TraceContext {
    /** 日志 MDC 键，与 application.yml 的 logging.pattern.level 一致。 */
    public static final String MDC_KEY = "traceId";

    /** 返回给客户端的响应头。 */
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {}

    /** 当前线程的追踪号；不在请求内时为 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 服务端生成的 32 位十六进制追踪号；不采用客户端提交的值，避免日志注入与关联伪造。 */
    static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
