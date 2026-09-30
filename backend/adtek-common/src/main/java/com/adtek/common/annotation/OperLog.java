package com.adtek.common.annotation;

import java.lang.annotation.*;

/** 管理操作审计入口；用例显式提交白名单快照，注解不序列化参数或返回值。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {
    String title();
    String action();
    BusinessType businessType();
    String[] fields();
    enum BusinessType { INSERT, UPDATE }
}
