package com.adtek.framework.security;

import com.adtek.common.core.Actor;

/** 跨模块写授权协调由身份模块实现；业务模块禁止自行读取账号表或信任旧JWT范围。 */
public interface AuthorizationMutationPort {
    /**
     * 调用方必须已开启READ_COMMITTED事务；先锁共同安全协调行再锁请求主体。 当前停用/版本变化/强制改密拒绝，返回重新装配的授权；调用方继续检查用例权限及工厂范围。
     * 锁保持至业务事务结束，所有业务锁须在此方法之后取得。
     */
    Actor lockCurrent(Actor requested);
}
