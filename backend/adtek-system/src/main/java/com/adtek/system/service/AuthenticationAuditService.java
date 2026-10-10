package com.adtek.system.service;

/** 认证事件审计；不接收请求体、密码、哈希、token 或 Cookie。 */
public interface AuthenticationAuditService {
    /** 记录已认证用户的固定事件名；加入调用方事务，提交失败不得产生成功审计。 */
    void recordSuccess(long userId, Action action);

    /** 独立事务记录拒绝；工号必须经过格式校验，主体ID只来自数据库，不接收原始报文。 */
    void recordLoginFailure(
            String username,
            Long userId,
            com.adtek.framework.audit.AuthenticationEventObserver.Outcome outcome,
            boolean lockStarted,
            Integer remainingMinutes);

    /** 仅接收已认证主体和明确的密码规则错误，不传递密码、异常文本或请求体。 */
    void recordPasswordFailure(
            long userId, com.adtek.framework.audit.AuthenticationEventObserver.Outcome outcome);

    /** 记录已读取的语言前后值；仅白名单字段，与本人语言修改加入同一事务。 */
    void recordLanguageChange(long userId, String previous, String current);

    enum Action {
        LOGIN,
        REFRESH,
        LOGOUT,
        PASSWORD_CHANGE,
        LANGUAGE_CHANGE,
        BOOTSTRAP
    }
}
