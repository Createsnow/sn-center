package com.adtek.system.service;

import com.adtek.system.dto.LanguageChangeDTO;
import com.adtek.system.dto.LoginCommandDTO;
import com.adtek.system.dto.PasswordChangeDTO;
import com.adtek.system.dto.RefreshCommandDTO;
import com.adtek.system.vo.AccessTokenVO;
import com.adtek.system.vo.UserProfileVO;

/** 人员认证用例；外部 MES 服务主体不复用本接口。 */
public interface AuthenticationService {
    /**
     * 核验工号/密码及当前账号状态，生成 Redis 会话和标准短时 JWT。 入口从可信 HTTP 上下文按账号与来源限流；非 HTTP 无来源上下文拒绝。 凭据错误统一 401，超限
     * 429，依赖故障 503；不区分匿名可枚举的停用/不存在原因。 登录成功审计提交后返回。Redis 与数据库不构成同一事务，失败残留的未返回会话由 TTL 回收。
     * 显式标记的旧PBKDF2哈希在成功验证后条件升级；不改变停用/首次改密标志或权限，失败回滚升级。
     */
    AuthenticationResult login(LoginCommandDTO command);

    /**
     * 一次性消费 Cookie 中的刷新凭据并轮换，复核当前账号/权限版本后签发新 JWT。 同一旧凭据并发使用最多一次成功，重放撤销该会话；响应丢失时要求重新登录，不自动重试。 失败为
     * 401/429/503；Controller 负责 CSRF 与 Secure/HttpOnly/SameSite Cookie。
     */
    AuthenticationResult refresh(RefreshCommandDTO command);

    /** 读取当前可信主体本人的资料/权限；空或失效身份 401，不接受客户端传入目标用户 ID。 */
    UserProfileVO me();

    /** 撤销当前会话并记录事件；Redis 故障返回 503，不声称已完成服务端注销。 */
    void logout();

    /**
     * 仅修改本人密码，包括首次强制改密；核验原密码、新强度及不同值。 锁定本人记录并比对权限版本，密码哈希/版本递增/改密标志/审计同事务提交。 所有旧
     * JWT/刷新凭据在下一次校验时失效，成功后客户端必须重新登录。
     */
    void changePassword(PasswordChangeDTO command);

    /** 仅更新本人语言偏好，限定中/英/越；记录实际前后值并与变更同事务提交。 */
    void changeLanguage(LanguageChangeDTO command);

    record AuthenticationResult(AccessTokenVO response, String refreshCredential) {
        @Override
        public String toString() {
            return "AuthenticationResult[redacted]";
        }
    }
}
