package com.adtek.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LanguageChangeDTO(@NotBlank @Pattern(regexp = "zh-CN|en|vi") String language) {}
