package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.security.SecurityProperties;
import com.adtek.framework.security.SessionCookies;
import com.adtek.framework.web.ClientAddress;
import com.adtek.sn.dto.LangDTO;
import com.adtek.sn.dto.LoginDTO;
import com.adtek.sn.dto.PasswordDTO;
import com.adtek.sn.service.AuthService;
import com.adtek.sn.vo.LoginVO;
import com.adtek.sn.vo.UserVO;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录、当前账户、改密、语言与退出。 */
@RestController
@RequestMapping("/api/v1/sn/auth")
public class AuthController {
    /** 页面登录时前端带的请求头；不带的（对接方）不参与「一个账户只在一个页面有效」。 */
    static final String PAGE_CLIENT_HEADER = "X-SN-Client";

    private final AuthService auth;
    private final SecurityProperties security;

    public AuthController(AuthService auth, SecurityProperties security) {
        this.auth = auth;
        this.security = security;
    }

    /** 取 CSRF 令牌（匿名可调）：响应写入 XSRF-TOKEN Cookie。 */
    @GetMapping("/csrf")
    public Result<Void> csrf() {
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(
            @RequestBody LoginDTO body, HttpServletRequest request, HttpServletResponse response) {
        // web：页面会话放 HttpOnly Cookie；web-token：页面会话令牌放应答体，由页面存在本标签页（sessionStorage）
        // 并以 Bearer 发送——与原系统相同，同一浏览器的不同标签页可登录不同账户。
        String client = String.valueOf(request.getHeader(PAGE_CLIENT_HEADER)).strip();
        boolean tabToken = "web-token".equalsIgnoreCase(client);
        boolean page = tabToken || "web".equalsIgnoreCase(client);
        AuthService.LoginResult r =
                auth.login(body.empNo, body.password, ClientAddress.of(request), page);
        LoginVO out = r.body();
        if (tabToken) {
            out.token = r.token();
        } else if (r.page()) {
            response.addHeader(
                    "Set-Cookie",
                    SessionCookies.issue(r.token(), r.ttlSeconds(), security.cookieSecure()));
        }
        return Result.ok(out);
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(auth.me());
    }

    /** 修改自己的密码；成功后该账户所有会话失效（含当前页面），须用新密码重新登录。 */
    @PostMapping("/password")
    public Result<Void> password(@RequestBody PasswordDTO body, HttpServletResponse response) {
        auth.changePassword(body.oldPassword, body.newPassword);
        response.addHeader("Set-Cookie", SessionCookies.clear(security.cookieSecure()));
        return Result.ok();
    }

    @PostMapping("/lang")
    public Result<UserVO> lang(@RequestBody LangDTO body) {
        return Result.ok(auth.setLang(body.lang));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        auth.logout();
        response.addHeader("Set-Cookie", SessionCookies.clear(security.cookieSecure()));
        return Result.ok();
    }
}
