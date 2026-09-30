package com.adtek.framework.task;
import com.adtek.framework.task.mapper.LegacyTaskGuardMapper;
import java.sql.*;
/** 离线目标门禁，不改任务状态；导入账号保持停用且调用方持安全协调行期间检查。 */
public final class LegacyTaskGuard {
    private LegacyTaskGuard(){}
    public static void requireEmpty(Connection c)throws SQLException{new LegacyTaskGuardMapper().requireEmpty(c);}
}
