package com.adtek.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "仅修改本人的密码；成功后所有旧会话失效，返回新会话。")
public record PasswordChangeDTO(
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
                String oldPassword,
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
                String newPassword) {
    @Override
    public String toString() {
        return "PasswordChangeDTO[redacted]";
    }
}
