package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.constant.ErrorCodeConstants;
import java.math.BigInteger;
import java.util.OptionalLong;

/**
 * 精确流水编解码策略；纯计算不分配流水、不推进水位、不占来源额度。
 * 数据库 long 上限与字符容量取较小值，中间计算采用 BigInteger 防溢出。
 */
public final class SequenceCodec {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private SequenceCodec() {}

    public static long capacity(SequenceSpec spec) {
        return BigInteger.valueOf(spec.base()).pow(spec.length()).subtract(BigInteger.ONE).min(LONG_MAX).longValueExact();
    }

    /** 编码含前后缀的完整 SN；允许零流水，负数或超容量拒绝，不截断。 */
    public static String format(long sequence, SequenceSpec spec) {
        if (sequence < 0 || sequence > capacity(spec)) {
            throw new BusinessException(ErrorCodeConstants.SEQUENCE_OVERFLOW);
        }
        int[] alphabet = spec.alphabet().codePoints().toArray();
        int[] digits = new int[spec.length()];
        long remainder = sequence;
        for (int position = digits.length - 1; position >= 0; position--) {
            digits[position] = alphabet[(int) (remainder % spec.base())];
            remainder /= spec.base();
        }
        return spec.prefix() + new String(digits, 0, digits.length) + spec.suffix();
    }

    /**
     * 严格反解完整 SN；格式不匹配或超过 long 返回空，供历史初始化保留未知流水。
     * 不忽略大小写、不 trim，避免把不同 SN 合并。
     */
    public static OptionalLong parse(String sn, SequenceSpec spec) {
        if (sn == null || !sn.startsWith(spec.prefix()) || !sn.endsWith(spec.suffix())
                || sn.length() < spec.prefix().length() + spec.suffix().length()) {
            return OptionalLong.empty();
        }
        String body = sn.substring(spec.prefix().length(), sn.length() - spec.suffix().length());
        int[] digits = body.codePoints().toArray();
        int[] alphabet = spec.alphabet().codePoints().toArray();
        if (digits.length != spec.length()) { return OptionalLong.empty(); }
        BigInteger number = BigInteger.ZERO;
        for (int codePoint : digits) {
            int value = -1;
            for (int index = 0; index < alphabet.length; index++) {
                if (alphabet[index] == codePoint) { value = index; break; }
            }
            if (value < 0) { return OptionalLong.empty(); }
            number = number.multiply(BigInteger.valueOf(spec.base())).add(BigInteger.valueOf(value));
        }
        return number.compareTo(LONG_MAX) > 0 ? OptionalLong.empty() : OptionalLong.of(number.longValueExact());
    }

    /** 核验整批末号；禁止循环逐号尝试而在中途才发现溢出，quantity 必须为正。 */
    public static long lastSequence(long watermark, long quantity, SequenceSpec spec) {
        if (watermark < 0 || quantity <= 0) {
            throw new BusinessException(ErrorCodeConstants.RULE_INVALID);
        }
        BigInteger last = BigInteger.valueOf(watermark).add(BigInteger.valueOf(quantity));
        if (last.compareTo(BigInteger.valueOf(capacity(spec))) > 0) {
            throw new BusinessException(ErrorCodeConstants.SEQUENCE_OVERFLOW);
        }
        return last.longValueExact();
    }
}
