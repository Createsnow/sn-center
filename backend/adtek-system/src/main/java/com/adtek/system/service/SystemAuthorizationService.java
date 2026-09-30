package com.adtek.system.service;
import com.adtek.common.core.Actor;
/** 管理事务内读取账号授予权限（包括停用账号），供反提权比较；不存在时拒绝。 */
public interface SystemAuthorizationService {
    Actor describe(long userId);
}
