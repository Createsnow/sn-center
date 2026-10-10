package com.adtek.sn.domain;

/** AUTO记录同步来源；EXPLICIT保留用户绑定，订单自动归属仍按快照组织名称匹配目录。 */
public record OrganizationMappingEntity(
        String sourceSystem,
        String sourceAccount,
        String orgId,
        String orgCode,
        String orgName,
        long factoryId,
        String bindingKind) {
    public boolean explicit() {
        return "EXPLICIT".equals(bindingKind);
    }
}
