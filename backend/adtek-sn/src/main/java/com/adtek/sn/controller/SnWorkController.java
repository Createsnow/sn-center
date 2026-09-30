package com.adtek.sn.controller;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.SnWorkService;
import com.adtek.sn.vo.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP协议层；持久命令返回任务而非尚未提交的成功数量。 */
@RestController
@Tag(name = "SN初始化生成与分配")
@RequestMapping(value = "/api/v1/sn", produces = "application/json")
public class SnWorkController {
    private final SnWorkService service;

    public SnWorkController(SnWorkService service) {
        this.service = service;
    }

    @GetMapping("/pi-initializations")
    @Operation(summary = "全部工厂pi-init:view；PI状态与生效规则，不创建控制行")
    public Result<PiInitializationVO> initialization(
            @RequestParam String pi, HttpServletRequest r) {
        return Result.success(service.initialization(pi), TraceFilter.current(r));
    }

    @GetMapping("/pi-initializations/history")
    @Operation(summary = "全部工厂pi-init:view；初始化尝试分页，失败不锁定PI")
    public Result<PageResult<SnWorkVO>> imports(
            @RequestParam String pi, @Valid @ModelAttribute SnPageQuery q, HttpServletRequest r) {
        return Result.success(service.imports(pi, q), TraceFilter.current(r));
    }

    @PostMapping("/pi-initializations")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary = "原子历史初始化持久任务；operationId重放，起号/SN字符串，PRINTED，未解析流水空值")
    public Result<TaskVO> initialize(
            @Valid @RequestBody PiInitializationDTO c, HttpServletRequest r) {
        return Result.success(service.initialize(c), TraceFilter.current(r));
    }

    @GetMapping("/generation/context")
    @Operation(summary = "全部工厂generation:view；来源订单、规则、水位、PI汇总与最近20次生成/分配")
    public Result<GenerationContextVO> context(@RequestParam String billId, HttpServletRequest r) {
        return Result.success(service.context(billId), TraceFilter.current(r));
    }

    @PostMapping("/generation/previews")
    @Operation(summary = "纯预演并固定原主体/来源/规则摘要/水位/数量，不推进流水或额度")
    public Result<GenerationPreviewVO> preview(
            @Valid @RequestBody GenerationPreviewDTO c, HttpServletRequest r) {
        return Result.success(service.preview(c), TraceFilter.current(r));
    }

    @PostMapping("/generation")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary = "短事务绑定预演、操作、任务和PI活跃占用；持久任务整批提交，不跳号")
    public Result<TaskVO> generate(@Valid @RequestBody GenerationDTO c, HttpServletRequest r) {
        return Result.success(service.generate(c), TraceFilter.current(r));
    }

    @PostMapping("/allocations")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary = "固定全部当前待分配成员；expectedQuantity须等于全部数量，固定生成时组织工厂")
    public Result<TaskVO> allocate(@Valid @RequestBody AllocationDTO c, HttpServletRequest r) {
        return Result.success(service.allocate(c), TraceFilter.current(r));
    }

    @GetMapping("/generation/history")
    @Operation(summary = "全部工厂generation:view；按稳定单据分页全部GENERATE/ALLOCATE历史")
    public Result<PageResult<SnWorkVO>> history(
            @RequestParam String billId,
            @RequestParam String kind,
            @Valid @ModelAttribute SnPageQuery q,
            HttpServletRequest r) {
        return Result.success(service.history(billId, kind, q), TraceFilter.current(r));
    }

    @GetMapping("/operations/{kind}/{id}")
    @Operation(summary = "PI_INIT/GENERATE/ALLOCATE本人操作恢复；当前同项权限仍须有效")
    public Result<TaskVO> operation(
            @PathVariable String kind, @PathVariable String id, HttpServletRequest r) {
        return Result.success(service.operation(kind, id), TraceFilter.current(r));
    }
}
