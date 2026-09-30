package com.adtek.system.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.*;

public record UserPasswordResetDTO(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 256)
                String password,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {
    @Override
    public String toString() {
        return "UserPasswordResetDTO[redacted]";
    }
}
