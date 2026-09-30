package com.adtek.framework.audit;
/** 入口失败持久化端口；实现须独立事务，仅接收已脱敏元数据，故障不改变原拒绝结果。 */
public interface RequestFailureAuditPort {
    /** actorId 仅来自已验证的 ActorAuthenticationToken；匿名/认证失败为空，不解析未验证凭据。 */
    void record(Event event);
    record Event(Long actorId,String method,String route,int status,Integer errorCode,String traceId) {}
}
