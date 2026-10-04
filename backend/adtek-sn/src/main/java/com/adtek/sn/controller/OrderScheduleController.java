package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.OrderScheduleDTO;
import com.adtek.sn.service.OrderScheduleService;
import com.adtek.sn.vo.OrderScheduleVO;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 全局同步设置入口；实际读写范围及责任主体由服务复核。 */
@RestController
@RequestMapping(value = "/api/v1/sn/orders/sync-schedule", produces = "application/json")
public class OrderScheduleController {
    private final OrderScheduleService service;

    public OrderScheduleController(OrderScheduleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "全部工厂order:list读取定时同步设置、执行条件与最近结果")
    public Result<OrderScheduleVO> status(HttpServletRequest request) {
        return Result.success(service.status(), TraceFilter.current(request));
    }

    @PutMapping
    @Operation(summary = "全部工厂order:sync设置开关和10—10080分钟间隔，责任主体为当前人员；设置变化使在途运行失效")
    public Result<OrderScheduleVO> save(
            @Valid @RequestBody OrderScheduleDTO command, HttpServletRequest request) {
        return Result.success(service.save(command), TraceFilter.current(request));
    }
}
