package com.adtek.system.dto;
import jakarta.validation.constraints.*;
public record UserStatusDTO(@NotNull Boolean enabled, @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
