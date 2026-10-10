package com.adtek.sn.dto;

/** SN账户启停；版本用于防止覆盖并发管理操作。 */
public record SnAccountStatusDTO(Boolean enabled, String version) {}
