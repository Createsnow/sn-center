package com.adtek.sn.controller;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.TransferQuery;
import com.adtek.sn.service.TransferService;
import com.adtek.sn.vo.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 仅协议适配，转厂权限、状态和事务由服务及共同任务处理器保证。 */
@RestController
@RequestMapping("/api/v1/sn/transfers")
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/target-hint")
    @Operation(summary = "只读转厂前后起号测算；校验来源，全厂view可查看目标PI，受限主体不返回目标水位")
    public Result<TransferImpactVO> hint(
            @Valid @RequestBody TransferHintDTO command, HttpServletRequest request) {
        return Result.success(service.hint(command), TraceFilter.current(request));
    }

    @GetMapping("/{id}/target-hint")
    @Operation(summary = "按待审批固定成员和当前目标规则重新测算起号；不保证审批时状态未变")
    public Result<TransferImpactVO> pendingHint(
            @PathVariable String id, HttpServletRequest request) {
        return Result.success(service.pendingHint(id), TraceFilter.current(request));
    }

    @GetMapping("/candidates")
    public Result<TransferCandidateVO> candidates(
            @RequestParam String factoryId,
            @RequestParam String pi,
            @RequestParam(defaultValue = "PI") String scope,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String sn,
            HttpServletRequest request) {
        return Result.success(
                service.candidates(factoryId, pi, scope, material, sn),
                TraceFilter.current(request));
    }

    @GetMapping("/targets")
    public Result<PageResult<TransferTargetVO>> targets(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(
                service.targets(false, query, page, size), TraceFilter.current(request));
    }

    @GetMapping("/factories")
    public Result<PageResult<TransferTargetVO>> factories(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(
                service.targets(true, query, page, size), TraceFilter.current(request));
    }

    @GetMapping
    public Result<PageResult<TransferVO>> list(
            @RequestParam(required = false) String factoryId,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(
                service.list(new TransferQuery(factoryId, pi, status, page, size)),
                TraceFilter.current(request));
    }

    @GetMapping("/{id}")
    public Result<TransferVO> get(@PathVariable String id, HttpServletRequest request) {
        return Result.success(service.get(id), TraceFilter.current(request));
    }

    @GetMapping("/{id}/items")
    public Result<CursorResult<TransferItemVO>> items(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int size,
            HttpServletRequest request) {
        return Result.success(service.items(id, cursor, size), TraceFilter.current(request));
    }

    @Operation(summary = "工厂申请转厂", description = "执行时固定全部可转成员并冻结；返回任务，业务生效须等待SUCCESS。")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> apply(
            @Valid @RequestBody TransferCreateDTO command, HttpServletRequest request) {
        return Result.success(service.create("APPLY", command), TraceFilter.current(request));
    }

    @Operation(summary = "总部直接转厂", description = "整批目标冲突拒绝，成功TO_ACQUIRE；原现场标签须人工处理。")
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> direct(
            @Valid @RequestBody TransferCreateDTO command, HttpServletRequest request) {
        return Result.success(service.create("DIRECT", command), TraceFilter.current(request));
    }

    @PostMapping("/{id}/{action:approve|reject|withdraw}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> decide(
            @PathVariable String id,
            @PathVariable String action,
            @Valid @RequestBody TransferDecisionDTO command,
            HttpServletRequest request) {
        return Result.success(
                service.decide(id, action.toUpperCase(java.util.Locale.ROOT), command),
                TraceFilter.current(request));
    }

    @GetMapping("/operations/{kind}/{operation}")
    public Result<TaskVO> operation(
            @PathVariable String kind, @PathVariable String operation, HttpServletRequest request) {
        return Result.success(service.operation(kind, operation), TraceFilter.current(request));
    }
}
