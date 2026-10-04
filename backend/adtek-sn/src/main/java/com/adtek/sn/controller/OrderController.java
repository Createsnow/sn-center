package com.adtek.sn.controller;

import com.adtek.common.core.*;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.OrderService;
import com.adtek.sn.vo.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 协议入口；授权和事务由Service执行，页面按钮不能代替用例权限。 */
@RestController
@Tag(name = "生产订单")
@RequestMapping(value = "/api/v1/sn/orders", produces = "application/json")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "当前order:list工厂范围内有效订单；精确累计和剩余额度，客户空不显示")
    public Result<PageResult<OrderBillVO>> list(
            @Valid @ModelAttribute SnPageQuery query, HttpServletRequest request) {
        return Result.success(service.list(query), TraceFilter.current(request));
    }

    @GetMapping("/{id}/lines")
    @Operation(summary = "同工厂范围分录完整分页；跨厂/不存在404，保留空物料和同物料多行")
    public Result<PageResult<OrderLineVO>> lines(
            @PathVariable String id,
            @Valid @ModelAttribute SnPageQuery query,
            HttpServletRequest request) {
        return Result.success(service.lines(id, query), TraceFilter.current(request));
    }

    @GetMapping("/meta")
    @Operation(summary = "同order:list工厂范围同步信息/数量/未映射组织")
    public Result<OrderMetaVO> meta(HttpServletRequest request) {
        return Result.success(service.meta(), TraceFilter.current(request));
    }

    @GetMapping("/customers")
    @Operation(summary = "同order:list工厂范围完整分页客户候选")
    public Result<PageResult<String>> customers(
            @Valid @ModelAttribute SnPageQuery query, HttpServletRequest request) {
        return Result.success(service.customers(query), TraceFilter.current(request));
    }

    @GetMapping("/pis")
    @Operation(summary = "同order:list范围分页PI汇总候选；q匹配PI，pending保留待生成或待分配PI")
    public Result<PageResult<OrderPiVO>> pis(
            @Valid @ModelAttribute SnPageQuery query, HttpServletRequest request) {
        return Result.success(service.pis(query), TraceFilter.current(request));
    }

    @PostMapping("/sync")
    @Operation(summary = "全部工厂order:sync权限；空billNo全量，否则单据；失败保留旧快照，并发版本变化409")
    public Result<OrderSyncVO> synchronize(
            @Valid @RequestBody OrderSyncDTO command, HttpServletRequest request) {
        return Result.success(service.synchronize(command), TraceFilter.current(request));
    }

    @GetMapping("/syncs")
    @Operation(summary = "全部工厂order:list权限查看全局同步历史；FETCHING不表示已发布")
    public Result<PageResult<OrderSyncVO>> syncs(
            @Valid @ModelAttribute SnPageQuery query, HttpServletRequest request) {
        return Result.success(service.syncs(query), TraceFilter.current(request));
    }
}
