package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.AlertHandleDTO;
import com.adtek.sn.dto.BillLineValueDTO;
import com.adtek.sn.dto.BillNoDTO;
import com.adtek.sn.service.OrderService;
import com.adtek.sn.vo.DepartmentVO;
import com.adtek.sn.vo.OrderAlertVO;
import com.adtek.sn.vo.OrderBillVO;
import com.adtek.sn.vo.OrderLineVO;
import com.adtek.sn.vo.OrderMetaVO;
import com.adtek.sn.vo.OrderTargetVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.SyncProgressVO;
import com.adtek.sn.vo.SyncResultVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 生产订单快照与订单变更提醒。 */
@RestController
@RequestMapping("/api/v1/sn/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public Result<PageVO<OrderBillVO>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(orders.list(q, customer, pi, factoryCode, page, pageSize));
    }

    @GetMapping("/meta")
    public Result<OrderMetaVO> meta() {
        return Result.ok(orders.meta());
    }

    @GetMapping("/sync-progress")
    public Result<SyncProgressVO> syncProgress() {
        return Result.ok(orders.syncProgress());
    }

    @GetMapping("/customers")
    public Result<List<String>> customers(@RequestParam(required = false) String q) {
        return Result.ok(orders.customers(q));
    }

    @GetMapping("/departments")
    public Result<List<DepartmentVO>> departments(
            @RequestParam(required = false) String factoryCode) {
        return Result.ok(orders.departments(factoryCode));
    }

    @GetMapping("/targets")
    public Result<List<OrderTargetVO>> targets(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) Integer limit) {
        return Result.ok(orders.targets(q, pi, limit));
    }

    @GetMapping("/{billNo}/lines")
    public Result<OrderBillVO> lines(@PathVariable String billNo) {
        return Result.ok(orders.billLines(billNo));
    }

    @PostMapping("/sync")
    public Result<SyncResultVO> sync(@RequestBody BillNoDTO body) {
        return Result.ok(orders.syncBill(body.billNo));
    }

    @PostMapping("/compare")
    public Result<SyncResultVO> compare() {
        return Result.ok(orders.compare());
    }

    @PostMapping("/page-sync")
    public Result<SyncResultVO> pageSync() {
        return Result.ok(orders.pageSync());
    }

    @PostMapping("/rebuild")
    public Result<SyncResultVO> rebuild() {
        return Result.ok(orders.rebuild());
    }

    @PostMapping("/attach")
    public Result<OrderLineVO> attach(@RequestBody BillLineValueDTO body) {
        return Result.ok(orders.fillAttach(body.billNo, body.seq, body.value));
    }

    @PostMapping("/length")
    public Result<OrderLineVO> length(@RequestBody BillLineValueDTO body) {
        return Result.ok(orders.fillLength(body.billNo, body.seq, body.value));
    }

    @PostMapping("/unit")
    public Result<OrderLineVO> unit(@RequestBody BillLineValueDTO body) {
        return Result.ok(orders.fillUnit(body.billNo, body.seq, body.value));
    }

    @GetMapping("/alerts")
    public Result<PageVO<OrderAlertVO>> alerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(orders.alerts(status, billNo, alertType, page, pageSize));
    }

    @PostMapping("/alerts/{id}/handle")
    public Result<Map<String, Object>> handle(
            @PathVariable long id, @RequestBody AlertHandleDTO body) {
        return Result.ok(orders.handleAlert(id, body.action, body.note, body.targetSeq));
    }
}
