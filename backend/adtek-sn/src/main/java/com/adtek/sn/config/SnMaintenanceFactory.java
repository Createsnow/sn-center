package com.adtek.sn.config;
import javax.sql.DataSource;
import java.nio.file.Path;
import com.adtek.sn.service.support.ArchiveRestoreProof;
import com.adtek.sn.mapper.MaintenanceMapper;
import com.adtek.sn.service.SnMaintenanceService;
import com.adtek.sn.service.impl.SnMaintenanceServiceImpl;
/** 仅独立CLI装配；普通页面进程不持有维护DataSource或DDL权限。 */
public final class SnMaintenanceFactory {
    private SnMaintenanceFactory() {}
    /** 调用方提供独立账号与精确schema白名单，连接后再次核对实际catalog。 */
    public static SnMaintenanceService create(DataSource source, String allowedSchema) {
        return create(source,allowedSchema,null);
    }
    /** 归档终结额外要求只读挂载的备份根目录；缺少该目录时不能移除在线副本。 */
    public static SnMaintenanceService create(DataSource source,String allowedSchema,Path proofRoot) {
        return new SnMaintenanceServiceImpl(source,allowedSchema,new MaintenanceMapper(),new ArchiveRestoreProof(proofRoot));
    }
}
