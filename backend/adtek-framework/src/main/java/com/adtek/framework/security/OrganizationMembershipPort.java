package com.adtek.framework.security;

import java.util.Optional;

/** 跨模块只提供当前部门 ID，不暴露身份 Entity 或账号 Mapper。 */
public interface OrganizationMembershipPort {
    /** 参数须来自可信 Actor；写用例先持有 AuthorizationMutationPort 的协调锁。未配置/停用部门为空。 */
    Optional<Long> currentDepartment(long userId);
}
