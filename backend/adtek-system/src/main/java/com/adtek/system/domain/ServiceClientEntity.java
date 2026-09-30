package com.adtek.system.domain;
/** 与人员共用不可复用主体ID空间，但凭据/授权独立；secretHash不进入一般VO。 */
public record ServiceClientEntity(long userId,String clientId,String secretHash) {}
