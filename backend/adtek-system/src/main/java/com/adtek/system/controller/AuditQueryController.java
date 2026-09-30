package com.adtek.system.controller;

import com.adtek.common.core.*;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.query.AuditQuery;
import com.adtek.system.service.AuditQueryService;
import com.adtek.system.vo.*;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "操作日志")
@RequestMapping(value = "/api/v1/system/audits", produces = "application/json")
@PreAuthorize("hasAuthority('system:audit:list')")
public class AuditQueryController {
    private final AuditQueryService service;

    public AuditQueryController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "按当前审计权限查询操作日志", description = "默认近7天，显式UTC起止为半开区间，最多31天；按事件部门快照/本人过滤。")
    public Result<PageResult<AuditLogVO>> list(
            @Valid @ModelAttribute AuditQuery query, HttpServletRequest request) {
        return Result.success(service.list(query), TraceFilter.current(request));
    }

    @GetMapping("/options")
    @Operation(summary = "同时间窗、同权限范围的动作与模块候选")
    public Result<AuditOptionsVO> options(
            @Valid @ModelAttribute AuditQuery query, HttpServletRequest request) {
        return Result.success(service.options(query), TraceFilter.current(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取脱敏审计详情", description = "越范围/不存在均404；不返回原始JSON，未知字段标记redacted。")
    public Result<AuditDetailVO> detail(@PathVariable String id, HttpServletRequest request) {
        return Result.success(service.detail(id), TraceFilter.current(request));
    }
}
