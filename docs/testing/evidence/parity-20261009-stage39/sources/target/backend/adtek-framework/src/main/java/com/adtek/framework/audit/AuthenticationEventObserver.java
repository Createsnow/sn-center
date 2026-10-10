package com.adtek.framework.audit;

/** 认证用例发布的非秘密事实；成功同事务投影，拒绝在独立审计事务中投影。 */
public interface AuthenticationEventObserver {
    void recorded(Event event);

    /** 工号仅为已校验格式的登录标识；actorId 只来自数据库，客户端地址只来自可信连接。 */
    record Event(
            Long actorId,
            AuditEventObserver.ActorSnapshot actor,
            Action action,
            Outcome outcome,
            String clientAddress,
            Integer lockMinutes,
            boolean lockStarted,
            String traceId) {}

    enum Action {
        LOGIN,
        PASSWORD_CHANGE
    }

    enum Outcome {
        SUCCESS,
        CREDENTIALS_REJECTED,
        DISABLED,
        LOCKED,
        PASSWORD_WRONG,
        PASSWORD_SAME,
        PASSWORD_WEAK
    }
}
