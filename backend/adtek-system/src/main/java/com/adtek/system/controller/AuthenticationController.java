package com.adtek.system.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.security.SecuritySettings;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.dto.LanguageChangeDTO;
import com.adtek.system.dto.LoginCommandDTO;
import com.adtek.system.dto.PasswordChangeDTO;
import com.adtek.system.dto.RefreshCommandDTO;
import com.adtek.system.service.AuthenticationService;
import com.adtek.system.vo.AccessTokenVO;
import com.adtek.system.vo.CsrfTokenVO;
import com.adtek.system.vo.UserProfileVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "人员认证")
@RestController
@RequestMapping(value = "/api/v1/system/auth", produces = "application/json")
public class AuthenticationController {
    private static final String COOKIE = "ADTEK-REFRESH";
    private final AuthenticationService authentication;
    private final SecuritySettings settings;

    public AuthenticationController(
            AuthenticationService authentication, SecuritySettings settings) {
        this.authentication = authentication;
        this.settings = settings;
    }

    @Operation(
            summary = "取得同源 CSRF token",
            description = "先调用再提交写请求；按返回 headerName 发送 token。不会创建服务端 HttpSession。")
    @GetMapping("/csrf")
    public Result<CsrfTokenVO> csrf(
            @Parameter(hidden = true) CsrfToken token, HttpServletRequest request) {
        return Result.success(
                new CsrfTokenVO(token.getHeaderName(), token.getToken()),
                TraceFilter.current(request));
    }

    @Operation(
            summary = "工号登录",
            description = "要求 CSRF。401 凭据拒绝，429 超限，503 依赖故障；刷新秘密只写 HttpOnly Cookie。")
    @PostMapping("/login")
    public Result<AccessTokenVO> login(
            @Valid @RequestBody LoginCommandDTO command,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = authentication.login(command);
        writeCookie(
                response, result.refreshCredential(), Duration.ofHours(settings.refreshHours()));
        return Result.success(result.response(), TraceFilter.current(request));
    }

    @Operation(summary = "轮换刷新会话", description = "要求 CSRF 和刷新 Cookie；旧凭据重放撤销整个会话，不自动重试。")
    @PostMapping("/refresh")
    public Result<AccessTokenVO> refresh(
            @CookieValue(name = COOKIE, required = false) String credential,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var result = authentication.refresh(new RefreshCommandDTO(credential));
            writeCookie(
                    response,
                    result.refreshCredential(),
                    Duration.ofHours(settings.refreshHours()));
            return Result.success(result.response(), TraceFilter.current(request));
        } catch (com.adtek.framework.security.SessionSuspendedException error) {
            writeCookie(response, "", Duration.ZERO);
            throw new com.adtek.common.core.BusinessException(error.error());
        }
    }

    @Operation(summary = "读取本人资料与当前权限")
    @GetMapping("/me")
    public Result<UserProfileVO> me(HttpServletRequest request) {
        return Result.success(authentication.me(), TraceFilter.current(request));
    }

    @Operation(summary = "注销当前会话", description = "要求有效 Bearer；依赖故障不声明注销成功。浏览器统一在写入前取得 CSRF。")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authentication.logout();
        writeCookie(response, "", Duration.ZERO);
        return Result.success(null, TraceFilter.current(request));
    }

    @Operation(summary = "修改本人密码", description = "首次强制改密也走此入口；成功后全部旧会话失效，直接返回新会话。")
    @PostMapping("/password")
    public Result<AccessTokenVO> password(
            @Valid @RequestBody PasswordChangeDTO command,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = authentication.changePassword(command);
        writeCookie(
                response, result.refreshCredential(), Duration.ofHours(settings.refreshHours()));
        return Result.success(result.response(), TraceFilter.current(request));
    }

    @Operation(summary = "更新本人语言", description = "仅支持 zh-CN/en/vi；与实际前后值审计同事务提交。")
    @PostMapping("/language")
    public Result<Void> language(
            @Valid @RequestBody LanguageChangeDTO command, HttpServletRequest request) {
        authentication.changeLanguage(command);
        return Result.success(null, TraceFilter.current(request));
    }

    private void writeCookie(HttpServletResponse response, String credential, Duration age) {
        com.adtek.framework.security.RefreshCookie.write(response, settings, credential, age);
    }
}
