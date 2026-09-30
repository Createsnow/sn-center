package com.adtek.sn.config;

import com.adtek.sn.mapper.LegacyStageMapper;
import com.adtek.sn.service.LegacyStageService;
import com.adtek.sn.service.impl.LegacyStageServiceImpl;

import java.nio.file.Path;

import javax.sql.DataSource;

/** 单独迁移进程装配；明确目标账号/schema和只读来源快照根目录。 */
public final class LegacyStageFactory {
    private LegacyStageFactory() {}

    public static LegacyStageService create(
            DataSource target, String allowedSchema, Path snapshotRoot) {
        return new LegacyStageServiceImpl(
                target, allowedSchema, snapshotRoot, new LegacyStageMapper());
    }
}
