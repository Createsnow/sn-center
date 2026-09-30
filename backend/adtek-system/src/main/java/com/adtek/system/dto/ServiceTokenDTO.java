package com.adtek.system.dto;
import jakarta.validation.constraints.*;
import com.adtek.common.core.RateLimitSubject;
/** 独立client凭据交换短时access token，无浏览器Cookie或refresh凭据。 */
public record ServiceTokenDTO(@NotBlank @Size(max=64) String clientId,@NotBlank @Size(max=128) String clientSecret) implements RateLimitSubject {
 public String rateLimitSubject(){return clientId;}
 @Override public String toString(){return "ServiceTokenDTO[redacted]";}
}
