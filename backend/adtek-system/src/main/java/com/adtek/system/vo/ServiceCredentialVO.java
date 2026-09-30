package com.adtek.system.vo;
/** 创建/轮换时只返回一次新秘密；响应禁止缓存，审计和日志不记录本对象。 */
public record ServiceCredentialVO(ServiceClientVO client,String clientSecret) {
 @Override public String toString(){return "ServiceCredentialVO[redacted]";}
}
