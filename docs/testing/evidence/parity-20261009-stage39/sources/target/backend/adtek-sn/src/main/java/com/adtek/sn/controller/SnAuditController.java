package com.adtek.sn.controller;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.SnAuditExportDTO;
import com.adtek.sn.query.SnAuditQuery;
import com.adtek.sn.service.SnAuditService;
import com.adtek.sn.vo.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** SN业务痕迹入口；只解析协议，工厂范围、快照和当前授权由Service负责。 */
@RestController
@RequestMapping("/api/v1/sn/audits")
public class SnAuditController {
    private final SnAuditService service;

    public SnAuditController(SnAuditService service) {
        this.service = service;
    }

    @GetMapping
    public Result<CursorResult<SnAuditVO>> items(
            @ParameterObject @ModelAttribute SnAuditQuery filters,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return Result.success(service.items(filters, cursor, size), TraceFilter.current(request));
    }

    @GetMapping("/page")
    public Result<PageResult<SnAuditVO>> page(
            @ParameterObject @ModelAttribute SnAuditQuery filters,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(service.page(filters, page, size), TraceFilter.current(request));
    }

    @GetMapping("/count")
    public Result<SnCountVO> count(
            @ParameterObject @ModelAttribute SnAuditQuery filters, HttpServletRequest request) {
        return Result.success(service.count(filters), TraceFilter.current(request));
    }

    @GetMapping("/meta")
    public Result<SnAuditMetaVO> meta(HttpServletRequest request) {
        return Result.success(service.meta(), TraceFilter.current(request));
    }

    @GetMapping("/{id}")
    public Result<SnAuditVO> get(
            @PathVariable String id, @RequestParam Instant createdAt, HttpServletRequest request) {
        return Result.success(service.get(id, createdAt), TraceFilter.current(request));
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> export(
            @Valid @RequestBody SnAuditExportDTO command, HttpServletRequest request) {
        return Result.success(service.export(command), TraceFilter.current(request));
    }

    @GetMapping("/exports")
    public Result<PageResult<SnExportVO>> exports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(service.exports(page, size), TraceFilter.current(request));
    }

    @GetMapping("/exports/operations/{format}/{id}")
    public Result<TaskVO> operation(
            @PathVariable String format, @PathVariable String id, HttpServletRequest request) {
        return Result.success(service.operation(format, id), TraceFilter.current(request));
    }
}
