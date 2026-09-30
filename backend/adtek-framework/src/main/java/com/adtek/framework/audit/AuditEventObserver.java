package com.adtek.framework.audit;
/** 业务模块订阅已白名单化的管理事实。与调用者使用同一事务；成功投影失败必须回滚业务，失败投影不得替换原错误。 */
public interface AuditEventObserver {
    /** actor为当时非秘密身份快照；实现不得再次调用ManagementAuditPort或取得外层业务写锁。 */
    void recorded(ManagementAuditPort.Event event,boolean success,ActorSnapshot actor);
    record ActorSnapshot(String username,String displayName,String roles,String principalType) {}
}
