package com.adtek.framework.security;

import java.time.Duration;

/** 可撤销会话存储；与 JWT 过期和 MySQL 凭据版本分别校验，不保存密码或明文刷新秘密。 */
public interface SessionStore {
    /** 为已核验的账号创建独立会话并设置固定生存期；Redis 故障返回可识别的 503。 返回的刷新凭据仅交给 HttpOnly Cookie，不能写入日志或业务响应。 */
    CreatedSession create(long userId, long credentialVersion, Duration lifetime);

    /** 校验服务端会话有效且与已验签 JWT 的主体/版本相同；失效 401，依赖故障 503。 */
    void requireActive(String sessionId, long userId, long credentialVersion);

    /** 不消费有效刷新凭据；重放旧秘密仍撤销会话。用于先核对临时停用状态。 */
    RefreshIdentity inspectRefresh(String credential);

    record RefreshIdentity(String sessionId, long userId, long credentialVersion) {}

    /** 原子消费并轮换刷新凭据，返回绑定主体和新秘密；旧秘密重用撤销该会话。 调用方必须再核验当前 MySQL 凭据版本，失效时撤销会话，不签发新 JWT。 */
    RotatedSession rotate(String credential, Duration lifetime);

    /** 撤销指定已认证会话；不存在视为已撤销，Redis 故障不得谎称注销成功。 */
    void revoke(String sessionId);

    record CreatedSession(String sessionId, String credential) {
        @Override
        public String toString() {
            return "CreatedSession[redacted]";
        }
    }

    record RotatedSession(
            String sessionId, long userId, long credentialVersion, String credential) {
        @Override
        public String toString() {
            return "RotatedSession[redacted]";
        }
    }
}
