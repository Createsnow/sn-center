package com.adtek.sn.controller;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.service.AcquireService;
import com.adtek.sn.vo.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 独立服务主体的入站MES协议；不提供生成、分配、转厂或管理接口。 */
@RestController
@RequestMapping("/api/v1/integrations/sn")
public class MesSnController {
    private final AcquireService service;

    public MesSnController(AcquireService service) {
        this.service = service;
    }

    @Operation(summary = "MES整厂+PI领取", description = "独立服务令牌及当前工厂资格；同页面用例、持久operationId，不接受人员凭据。")
    @PostMapping("/acquisitions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> take(
            @Valid @RequestBody AcquisitionDTO command, HttpServletRequest request) {
        return Result.success(service.take(command), TraceFilter.current(request));
    }

    @GetMapping("/operations/{kind}/{id}")
    public Result<TaskVO> operation(
            @PathVariable String kind, @PathVariable String id, HttpServletRequest request) {
        return Result.success(service.operation(kind, id), TraceFilter.current(request));
    }

    @GetMapping("/results/{id}")
    public Result<AcquireWorkVO> result(@PathVariable String id, HttpServletRequest request) {
        return Result.success(service.result(id), TraceFilter.current(request));
    }

    @GetMapping("/results/{id}/items")
    public Result<CursorResult<AcquireResultVO>> results(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1000") int size,
            HttpServletRequest request) {
        return Result.success(service.results(id, cursor, size), TraceFilter.current(request));
    }

    @GetMapping("/acquire-batches/{id}/items")
    public Result<CursorResult<AcquireItemVO>> items(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1000") int size,
            HttpServletRequest request) {
        return Result.success(service.items(id, cursor, size), TraceFilter.current(request));
    }

    @Operation(
            summary = "固定批次打印回调",
            description = "sns/ranges二选一，targetStatus仅PRINTED；重复清单去重，范围只能来自原批同流水归属连续成员，逐项结果完整分页。")
    @PostMapping("/print-confirmations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TaskVO> confirm(
            @Valid @RequestBody PrintConfirmationDTO command, HttpServletRequest request) {
        return Result.success(service.confirm(command), TraceFilter.current(request));
    }

    @GetMapping("/items")
    public Result<CursorResult<SnReadItemVO>> read(
            @RequestParam String factoryId,
            @RequestParam String pi,
            @RequestParam(required = false) String customer,
            @RequestParam(defaultValue = "ALL") String materialMode,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1000") int size,
            HttpServletRequest request) {
        return Result.success(
                service.readItems(factoryId, pi, customer, materialMode, material, cursor, size),
                TraceFilter.current(request));
    }
}
