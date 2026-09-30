package com.adtek.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "仅修改本人的密码；成功后所有旧会话失效，必须重新登录。")
public record PasswordChangeDTO(
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String oldPassword,
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
    @Override public String toString() { return "PasswordChangeDTO[redacted]"; }
}
