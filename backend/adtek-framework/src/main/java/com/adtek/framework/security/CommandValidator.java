package com.adtek.framework.security;

import com.adtek.common.constant.ErrorCodeConstants;
import com.adtek.common.core.BusinessException;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

/** 在 Service 边界重复结构校验，覆盖绕过 HTTP Controller 的内部调用；不返回秘密值。 */
@Component
public class CommandValidator {
    private final Validator validator;

    public CommandValidator(Validator validator) {
        this.validator = validator;
    }

    public void requireValid(Object command) {
        if (command == null || !validator.validate(command).isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.INVALID_ARGUMENT);
        }
    }
}
