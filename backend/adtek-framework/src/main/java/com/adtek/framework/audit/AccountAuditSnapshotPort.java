package com.adtek.framework.audit;

import java.util.List;
import java.util.Optional;

/** 审计事务内读取已验证主体/管理对象的非秘密资料，不提供HTTP任意账号查询入口。 */
public interface AccountAuditSnapshotPort {
    Optional<Snapshot> read(long userId);

    List<String> roleCodes(List<String> roleIds);

    record Snapshot(
            String username,
            String displayName,
            String principalType,
            List<String> roleCodes,
            List<String> factoryIds) {}
}
