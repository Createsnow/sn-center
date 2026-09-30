package com.adtek.common.annotation;

import java.lang.annotation.*;

/** 用例声明受控资源及操作权限；实际范围由可信主体解析，禁止传入 SQL。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
    String resource();
    String permission();
}
