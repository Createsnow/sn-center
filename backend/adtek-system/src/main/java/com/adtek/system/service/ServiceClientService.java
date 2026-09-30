package com.adtek.system.service;

import com.adtek.system.dto.*;
import com.adtek.system.vo.*;

/** 独立MES主体生命周期；业务数据写始终进入SN用例，不在身份模块实现。 */
public interface ServiceClientService {
    /** 当前全局安全管理员及服务管理权限、可委派工厂才能创建；随机秘密只返回一次，主体/范围/审计同事务。 */
    ServiceCredentialVO create(ServiceClientCreateDTO command);

    /** 全局管理员读取非秘密元数据；当前范围不得超过管理员可委派范围。 */
    ServiceClientVO get(String id);

    /** 持协调/账号锁，核对版本并重新委派范围；停用/变更撤销旧令牌，审计失败回滚。 */
    ServiceClientVO update(String id, ServiceClientUpdateDTO command);

    /** 核对版本后轮换秘密并推进授权版本；稳定主体ID和历史操作保留，不返回旧秘密。 */
    ServiceCredentialVO rotate(String id, String version);

    /** 独立服务凭据与速率预算；只签发短时JWT，当前停用/范围仍在每次请求和工作器复核。 */
    ServiceTokenVO token(ServiceTokenDTO command);
}
