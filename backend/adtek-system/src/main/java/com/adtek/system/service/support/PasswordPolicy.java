package com.adtek.system.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.system.constant.ErrorCodeConstants;

/** 保留旧账号的长度/字母数字要求；哈希升级由标准 PasswordEncoder 完成。 */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void requireStrong(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 8
                || value.codePointCount(0, value.length()) > 64
                || !value.matches("(?s).*[A-Za-z].*") || !value.matches("(?s).*[0-9].*")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCodeConstants.PASSWORD_WEAK);
        }
    }
}
