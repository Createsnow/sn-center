package com.adtek.framework.security;

import java.util.Optional;

/** 内部显示名称解析；调用方先授权用例，只传业务记录中的可信主体ID，不暴露任意ID查询接口。 */
public interface IdentityNamePort {
    Optional<String> username(long userId);
}
