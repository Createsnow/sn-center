package com.adtek.sn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** SN新建表单；按业务顺序校验，密码不序列化或进入诊断文本。 */
public record SnAccountCreateDTO(
        String username,
        String displayName,
        String role,
        String factoryId,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    @Override
    public String toString() {
        return "SnAccountCreateDTO[redacted]";
    }
}
