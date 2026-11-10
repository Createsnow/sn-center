package com.adtek.sn.support;

import com.adtek.framework.web.TraceContext;

/** 当前追踪号（写入痕迹 trace_id）。 */
public final class Trace {
    private Trace() {}

    public static String id() {
        return TraceContext.current();
    }
}
