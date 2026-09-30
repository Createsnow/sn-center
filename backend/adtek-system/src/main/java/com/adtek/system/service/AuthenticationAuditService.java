package com.adtek.system.service;

/** 认证事件审计；不接收请求体、密码、哈希、token 或 Cookie。 */
public interface AuthenticationAuditService {
    /** 记录已认证用户的固定事件名；加入调用方事务，提交失败不得产生成功审计。 */
    void recordSuccess(long userId, Action action);

    /** 在独立事务记录匿名登录拒绝；不保存未验证的用户标识和原始报文。 */
    void recordLoginFailure();

    /** 记录已读取的语言前后值；仅白名单字段，与本人语言修改加入同一事务。 */
    void recordLanguageChange(long userId, String previous, String current);

    enum Action { LOGIN, REFRESH, LOGOUT, PASSWORD_CHANGE, LANGUAGE_CHANGE, BOOTSTRAP }
}
