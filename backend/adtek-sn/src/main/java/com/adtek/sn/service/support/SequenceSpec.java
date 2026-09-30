package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.constant.ErrorCodeConstants;
import java.util.Set;

/**
 * 规则版本的不可变编码参数，不是持久 Entity。
 * 字符表按 Unicode 码点计长，与旧 Python 的字符计数保持一致；不做大小写或空白折叠。
 */
public record SequenceSpec(String prefix, String suffix, int base, int length, String alphabet) {
    public SequenceSpec {
        if (prefix == null || suffix == null || alphabet == null
                || !Set.of(10, 16, 32, 36).contains(base) || length < 1 || length > 12
                || alphabet.codePointCount(0, alphabet.length()) != base
                || alphabet.codePoints().distinct().count() != base
                || prefix.codePointCount(0, prefix.length()) + suffix.codePointCount(0, suffix.length()) + length > 64
                || containsInvalidCodePoint(prefix + suffix + alphabet)) {
            throw new BusinessException(ErrorCodeConstants.RULE_INVALID);
        }
    }

    private static boolean containsInvalidCodePoint(String value) {
        return value.codePoints().anyMatch(c -> Character.isISOControl(c)
                || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE));
    }

    public static SequenceSpec standard(String prefix, String suffix, int base, int length) {
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        if (!Set.of(10, 16, 32, 36).contains(base)) {
            throw new BusinessException(ErrorCodeConstants.RULE_INVALID);
        }
        return new SequenceSpec(prefix, suffix, base, length, digits.substring(0, base));
    }
}
