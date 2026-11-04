package com.adtek.system.service;

import com.adtek.system.dto.BootstrapAdminDTO;

/** 仅显式 bootstrap-admin CLI 或隔离集成测试装配，不提供初始化 HTTP 接口。 */
public interface BootstrapService {
    /**
     * 初始化空库管理员，使用外部提供的强密码，首次登录仍强制改密。 控制行、账号、角色和成功审计在同一事务内；并发/重复调用返回 ALREADY_INITIALIZED，绝不重置现有账号。
     * 不支持幂等重放密码；调用方须持有部署命令执行权限，失败整体回滚。
     *
     * @param command 受控命令参数，禁止写日志
     * @return 数据库分配的管理员 ID，以字符串交付
     */
    String initialize(BootstrapAdminDTO command);
}
