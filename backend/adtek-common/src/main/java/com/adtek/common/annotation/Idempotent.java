package com.adtek.common.annotation;

import java.lang.annotation.*;

/** 声明持久操作入口；仅用于DurableTaskService的数据库实现，不创建第二套Redis/AOP状态。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}
