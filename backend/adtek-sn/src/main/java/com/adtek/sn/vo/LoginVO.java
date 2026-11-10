package com.adtek.sn.vo;

/** 登录结果：页面登录的令牌只放在 HttpOnly Cookie，不在响应体；对接方登录（不带 X-SN-Client: web）返回 Bearer 令牌。 */
public class LoginVO {
    public UserVO user;
    public String token;
}
