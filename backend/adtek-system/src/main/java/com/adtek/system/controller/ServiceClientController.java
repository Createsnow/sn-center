package com.adtek.system.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.dto.*;
import com.adtek.system.service.ServiceClientService;
import com.adtek.system.vo.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
public class ServiceClientController {
    private final ServiceClientService service;

    public ServiceClientController(ServiceClientService service) {
        this.service = service;
    }

    @Operation(summary = "创建独立MES主体", description = "全局服务管理员及工厂委派授权；秘密仅返回一次，禁止缓存/日志保存。")
    @PostMapping("/api/v1/system/service-clients")
    public Result<ServiceCredentialVO> create(
            @Valid @RequestBody ServiceClientCreateDTO command,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(service.create(command), TraceFilter.current(request));
    }

    @GetMapping("/api/v1/system/service-clients/{id}")
    public Result<ServiceClientVO> get(@PathVariable String id, HttpServletRequest request) {
        return Result.success(service.get(id), TraceFilter.current(request));
    }

    @PostMapping("/api/v1/system/service-clients/{id}/update")
    public Result<ServiceClientVO> update(
            @PathVariable String id,
            @Valid @RequestBody ServiceClientUpdateDTO command,
            HttpServletRequest request) {
        return Result.success(service.update(id, command), TraceFilter.current(request));
    }

    @PostMapping("/api/v1/system/service-clients/{id}/rotate")
    public Result<ServiceCredentialVO> rotate(
            @PathVariable String id,
            @Valid @RequestBody ServiceClientRotationDTO command,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(service.rotate(id, command.version()), TraceFilter.current(request));
    }

    @Operation(
            summary = "MES独立凭据换取短时令牌",
            description = "不设置Cookie、不返回refresh凭据；每次请求复核当前范围、停用及授权版本。")
    @PostMapping("/api/v1/integrations/auth/token")
    public Result<ServiceTokenVO> token(
            @Valid @RequestBody ServiceTokenDTO command,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(service.token(command), TraceFilter.current(request));
    }
}
