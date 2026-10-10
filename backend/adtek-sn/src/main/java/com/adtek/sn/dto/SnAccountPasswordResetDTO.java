package com.adtek.sn.dto;

/** SN初始密码重置；凭据不进入日志或异常。 */
public record SnAccountPasswordResetDTO(
        @com.fasterxml.jackson.annotation.JsonProperty(
                        access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                String password,
        String version) {
    @Override
    public String toString() {
        return "SnAccountPasswordResetDTO[redacted]";
    }
}
