package com.adtek.system.vo;

import java.util.Set;

public record ServiceClientVO(
        String id,
        String clientId,
        String displayName,
        String version,
        boolean enabled,
        Set<String> factoryIds,
        Set<String> permissions) {}
