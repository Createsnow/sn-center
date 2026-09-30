package com.adtek.sn.config;

import com.adtek.framework.security.LegacyIdentityPort;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.LegacyMasterService;
import com.adtek.sn.service.impl.LegacyMasterServiceImpl;

import java.nio.file.Path;

import javax.sql.DataSource;

/** 显式离线装配，身份实现由server注入system端口，SN不引用system的Mapper或实现。 */
public final class LegacyMasterFactory {
    private LegacyMasterFactory() {}

    public static LegacyMasterService create(
            DataSource target, String schema, Path snapshotRoot, LegacyIdentityPort identities) {
        return new LegacyMasterServiceImpl(
                target,
                schema,
                snapshotRoot,
                identities,
                new LegacyStageMapper(),
                new LegacyMasterMapper());
    }
}
