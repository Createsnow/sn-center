package com.adtek.sn.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mapper 返回的行（{@code resultType="map"}，键为 SQL 列标签）取值工具：数值统一按 Number 转换（COUNT 为 Long、SUM 为
 * BigDecimal），TINYINT(1) 可能为 Boolean 或数值。
 */
public final class R {
    private R() {}

    public static Object v(Map<String, Object> m, String k) {
        return m == null ? null : m.get(k);
    }

    public static String s(Map<String, Object> m, String k) {
        Object v = v(m, k);
        return v == null ? null : v.toString();
    }

    /** 字符串，null → 空串。 */
    public static String sz(Map<String, Object> m, String k) {
        String v = s(m, k);
        return v == null ? "" : v;
    }

    public static Long lo(Map<String, Object> m, String k) {
        Object v = v(m, k);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        return Long.parseLong(v.toString());
    }

    /** long，null → 0。 */
    public static long l(Map<String, Object> m, String k) {
        Long v = lo(m, k);
        return v == null ? 0 : v;
    }

    public static Integer io(Map<String, Object> m, String k) {
        Long v = lo(m, k);
        return v == null ? null : v.intValue();
    }

    public static int i(Map<String, Object> m, String k) {
        return (int) l(m, k);
    }

    public static boolean b(Map<String, Object> m, String k) {
        Object v = v(m, k);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean bb) {
            return bb;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0;
        }
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }

    public static Boolean bo(Map<String, Object> m, String k) {
        return v(m, k) == null ? null : b(m, k);
    }

    public static BigDecimal d(Map<String, Object> m, String k) {
        Object v = v(m, k);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(v.toString());
    }

    public static LocalDateTime t(Map<String, Object> m, String k) {
        Object v = v(m, k);
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime t) {
            return t;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }

    public static boolean eq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return Objects.equals(a, b);
    }

    /** 新建一行（链式构造参数 map）。 */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 物料行键（单据 + 序号）。 */
    public static String key(String bill, int seq) {
        return bill + "\u0000" + seq;
    }

    public static String key(Map<String, Object> m) {
        return key(s(m, "bill_no"), i(m, "seq"));
    }

    public static <T> List<List<T>> chunks(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }
}
