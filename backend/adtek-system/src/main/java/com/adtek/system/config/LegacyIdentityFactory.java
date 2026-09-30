package com.adtek.system.config;

import com.adtek.framework.security.LegacyIdentityPort;
import com.adtek.system.mapper.LegacyIdentityMapper;
import com.adtek.system.service.impl.LegacyIdentityServiceImpl;

/** 离线迁移装配点；普通应用不注册该端口为自动导入服务。 */
public final class LegacyIdentityFactory {
    private LegacyIdentityFactory() {}

    public static LegacyIdentityPort create() {
        return new LegacyIdentityServiceImpl(new LegacyIdentityMapper());
    }
}
