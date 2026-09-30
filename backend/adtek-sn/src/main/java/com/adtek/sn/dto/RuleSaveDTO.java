package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/**
 * 规则整表单保存；编辑时code/bindScope/bindValue不可变化；空字符表采用进制默认。
 *
 * @param revision 编辑乐观版本的精确字符串，创建时为空
 * @param bindValue GENERAL归一为空；CUSTOMER/PI必须非空且保持大小写
 * @param length 进制流水位数，按Unicode码点计数
 */
public record RuleSaveDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String code,
        @NotBlank @Size(max = 128) String name,
        @NotBlank String bindScope,
        @Size(max = 128) String bindValue,
        @Size(max = 128) String prefix,
        @Size(max = 128) String suffix,
        @NotNull Integer base,
        @NotNull Integer length,
        @Size(max = 144) String alphabet,
        String revision) {}
