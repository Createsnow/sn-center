package com.adtek.sn.support;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.constant.SnErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务错误：{@code throw Biz.err(SnErrorCode.X, "pi", pi, ...)}；参数值按 String.valueOf（null → 空串）转成字符串。 */
public final class Biz {
    private Biz() {}

    public static BusinessException err(SnErrorCode code, Object... kv) {
        return new BusinessException(code, args(kv));
    }

    public static Map<String, String> args(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String s =
                    v == null
                            ? ""
                            : v instanceof Boolean b ? (b ? "true" : "false") : String.valueOf(v);
            m.put(String.valueOf(kv[i]), s);
        }
        return m;
    }

    /** 业务错误的中文说明（审计、日志用）。 */
    public static String message(BusinessException e) {
        if (e.errorCode() instanceof SnErrorCode c) {
            return c.render(e.args());
        }
        if (e.errorCode() instanceof com.adtek.framework.security.AuthErrorCode a) {
            return a.message();
        }
        return e.errorCode().key();
    }

    /** 是否为该错误码。 */
    public static boolean is(BusinessException e, SnErrorCode code) {
        return e.errorCode() == code;
    }
}
