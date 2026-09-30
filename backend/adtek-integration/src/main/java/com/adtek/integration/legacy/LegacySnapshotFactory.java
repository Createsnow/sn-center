package com.adtek.integration.legacy;

import com.adtek.sn.service.port.LegacySnapshotPort;

import javax.sql.DataSource;

/** 仅离线CLI装配显式只读来源账号，页面进程无需或无法自动发现旧库凭据。 */
public final class LegacySnapshotFactory {
    private LegacySnapshotFactory() {}

    public static LegacySnapshotPort create(DataSource source) {
        return new JdbcLegacySnapshot(source);
    }
}
