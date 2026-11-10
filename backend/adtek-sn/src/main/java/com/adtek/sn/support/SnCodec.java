package com.adtek.sn.support;

import com.adtek.sn.constant.SnErrorCode;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SN 编码（原系统 app/core/encoding.py）：完整 SN = 前缀 + 流水 + 米数前分隔符 + 米数 + 米数后分隔符 + 单位 + 后缀。
 *
 * <p>流水定长：左侧用字符表首字符补到流水位数；10 / 16 / 32 / 36 进制。米数是字符串，原样写入（只去首尾空格），不足补 0 位数时左侧补 0；
 * 规则不带米数时米数和前后分隔符都不写；单位取物料行的产品单位原样写入。流水紧跟前缀、定长，因此按版本可以把 SN 反解出十进制流水。
 */
public final class SnCodec {
    private SnCodec() {}

    public static final Map<Integer, String> DEFAULT_CHARSETS =
            Map.of(
                    10, "0123456789",
                    16, "0123456789ABCDEF",
                    32, "0123456789ABCDEFGHJKMNPQRSTVWXYZ",
                    36, "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    public static final int MAX_SEQ_LEN = 12;
    public static final int MAX_LENGTH_PAD = 32;
    public static final int MAX_SEP = 16;
    public static final int SN_MAX = 64;
    public static final long LONG_MAX = Long.MAX_VALUE;
    private static final Pattern CHARSET_RE = Pattern.compile("^\\S+$");
    private static final Pattern CHINESE_RE =
            Pattern.compile(
                    "[\\u3000-\\u303f\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uff00-\\uffef]");

    /** 流水超出规则位数。 */
    public static final class SeqOverflow extends ArithmeticException {
        private static final long serialVersionUID = 1L;

        public SeqOverflow(String message) {
            super(message);
        }
    }

    /** 规则版本的编码参数；charset 为完整字符表，长度 = 进制。 */
    public record Spec(
            String prefix,
            String suffix,
            int base,
            int seqLen,
            String charset,
            boolean withLength,
            int lengthPad,
            String sepBefore,
            String sepAfter,
            boolean withUnit) {
        public Spec {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            sepBefore = sepBefore == null ? "" : sepBefore;
            sepAfter = sepAfter == null ? "" : sepAfter;
        }
    }

    /** 校验参数；返回 null 表示合法，否则返回错误码。 */
    public static SnErrorCode validate(Spec s) {
        if (!DEFAULT_CHARSETS.containsKey(s.base())) {
            return SnErrorCode.RULE_BASE_INVALID;
        }
        if (s.seqLen() < 1 || s.seqLen() > MAX_SEQ_LEN) {
            return SnErrorCode.RULE_SEQ_LEN_INVALID;
        }
        if (s.prefix().isEmpty()) {
            return SnErrorCode.RULE_PREFIX_REQUIRED;
        }
        if (s.lengthPad() < 0 || s.lengthPad() > MAX_LENGTH_PAD) {
            return SnErrorCode.RULE_LENGTH_PAD_INVALID;
        }
        if (len(s.sepBefore()) > MAX_SEP || len(s.sepAfter()) > MAX_SEP) {
            return SnErrorCode.RULE_AFFIX_INVALID;
        }
        if (s.charset() == null || len(s.charset()) != s.base()) {
            return SnErrorCode.RULE_CHARSET_LENGTH;
        }
        if (!CHARSET_RE.matcher(s.charset()).matches()) {
            return SnErrorCode.RULE_CHARSET_INVALID;
        }
        Set<Integer> seen = new HashSet<>();
        if (!s.charset().codePoints().allMatch(seen::add)) {
            return SnErrorCode.RULE_CHARSET_DUPLICATE;
        }
        if (len(s.prefix()) + len(s.suffix()) + s.seqLen() + fixedLengthPart(s) > SN_MAX) {
            return SnErrorCode.RULE_SN_TOO_LONG;
        }
        return null;
    }

    /** 字符数（按 Unicode 码点，与原系统 len() 一致）。 */
    public static int len(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    static int fixedLengthPart(Spec s) {
        return s.withLength() ? len(s.sepBefore()) + s.lengthPad() + len(s.sepAfter()) : 0;
    }

    /** 该规则能表示的最大十进制流水（封顶在 long 上限）。 */
    public static long maxSeq(Spec s) {
        BigInteger v = BigInteger.valueOf(s.base()).pow(s.seqLen()).subtract(BigInteger.ONE);
        return v.compareTo(BigInteger.valueOf(LONG_MAX)) > 0 ? LONG_MAX : v.longValue();
    }

    /** 定长进制流水；超出位数抛 SeqOverflow。 */
    public static String encodeSeq(long seq, Spec s) {
        if (seq < 0) {
            throw new IllegalArgumentException("negative seq");
        }
        int[] cps = s.charset().codePoints().toArray();
        int[] digits = new int[s.seqLen()];
        long n = seq;
        for (int i = s.seqLen() - 1; i >= 0; i--) {
            digits[i] = cps[(int) (n % s.base())];
            n /= s.base();
        }
        if (n != 0) {
            throw new SeqOverflow(
                    "seq " + seq + " exceeds " + s.seqLen() + " digits of base " + s.base());
        }
        return new String(digits, 0, digits.length);
    }

    public static Long decodeSeq(String text, Spec s) {
        Map<Integer, Integer> index = new HashMap<>();
        int[] cps = s.charset().codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            index.put(cps[i], i);
        }
        BigInteger n = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(s.base());
        for (int c : text.codePoints().toArray()) {
            Integer d = index.get(c);
            if (d == null) {
                return null;
            }
            n = n.multiply(base).add(BigInteger.valueOf(d));
        }
        return n.bitLength() > 63 ? null : n.longValue();
    }

    /** 米数按规则左侧补 0 到补 0 位数（超过不截断）。 */
    public static String padLength(String length, Spec s) {
        String v = length == null ? "" : length;
        if (s.lengthPad() <= 0) {
            return v;
        }
        int missing = s.lengthPad() - len(v);
        return missing > 0 ? "0".repeat(missing) + v : v;
    }

    /** 完整 SN；规则带米数时写入（补 0）米数及前后分隔符，带单位时写入单位。 */
    public static String formatSn(long seq, Spec s, String length, String unit) {
        String mid = s.withLength() ? s.sepBefore() + padLength(length, s) + s.sepAfter() : "";
        String tail = s.withUnit() ? (unit == null ? "" : unit) : "";
        return s.prefix() + encodeSeq(seq, s) + mid + tail + s.suffix();
    }

    /** 按规则反解完整 SN → 十进制流水；对不上返回 null。 */
    public static Long parse(String sn, Spec s) {
        if (sn == null || !sn.startsWith(s.prefix()) || !sn.endsWith(s.suffix())) {
            return null;
        }
        if (sn.length() < s.prefix().length() + s.suffix().length()) {
            return null;
        }
        String body = sn.substring(s.prefix().length(), sn.length() - s.suffix().length());
        if (len(body) < s.seqLen()) {
            return null;
        }
        int cut = body.offsetByCodePoints(0, s.seqLen());
        String seqText = body.substring(0, cut);
        String rest = body.substring(cut);
        if (s.withLength()) {
            if (len(rest) < fixedLengthPart(s) || !rest.startsWith(s.sepBefore())) {
                return null;
            }
        } else if (!s.withUnit() && !rest.isEmpty()) {
            return null;
        }
        return decodeSeq(seqText, s);
    }

    /** 从完整 SN 截出写入的产品单位（米数传号上记的未补 0 原值）；对不上返回 null。 */
    public static String unitOf(String sn, Spec s, String length) {
        if (!s.withUnit() || sn == null || !sn.startsWith(s.prefix()) || !sn.endsWith(s.suffix())) {
            return null;
        }
        String mid = s.withLength() ? s.sepBefore() + padLength(length, s) + s.sepAfter() : "";
        int seqStart = s.prefix().length();
        if (sn.length() < seqStart) {
            return null;
        }
        int seqEnd;
        try {
            seqEnd = sn.offsetByCodePoints(seqStart, s.seqLen());
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        int head = seqEnd + mid.length();
        int end = sn.length() - s.suffix().length();
        if (end < head || !sn.substring(seqEnd, head).equals(mid)) {
            return null;
        }
        return sn.substring(head, end);
    }

    /** 米数字符串：去首尾空格后原样使用；为空返回 null。 */
    public static String cleanLength(String raw) {
        String v = raw == null ? "" : raw.strip();
        return v.isEmpty() ? null : v;
    }

    /** 是否含中文（含中文标点、全角字符）。 */
    public static boolean hasChinese(String text) {
        return text != null && !text.isEmpty() && CHINESE_RE.matcher(text).find();
    }
}
