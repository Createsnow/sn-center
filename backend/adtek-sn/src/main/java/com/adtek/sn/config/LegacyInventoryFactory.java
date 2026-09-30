package com.adtek.sn.config;

import com.adtek.framework.security.LegacyIdentityPort;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.LegacyInventoryService;
import com.adtek.sn.service.impl.*;

import java.nio.file.Path;

import javax.sql.DataSource;

/** 隔离库存导入装配，复用完整主数据证明检查，不注册Web入口。 */
public final class LegacyInventoryFactory {
    private LegacyInventoryFactory() {}

    public static LegacyInventoryService create(
            DataSource target, String schema, Path root, LegacyIdentityPort identities) {
        var stage = new LegacyStageMapper();
        var master =
                new LegacyMasterServiceImpl(
                        target, schema, root, identities, stage, new LegacyMasterMapper());
        return new LegacyInventoryServiceImpl(
                target, schema, root, master, stage, LegacyMasterMapper.inventory());
    }
}
