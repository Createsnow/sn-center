package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.BatchManualDTO;
import com.adtek.sn.dto.NoteDTO;
import com.adtek.sn.dto.PushResumeDTO;
import com.adtek.sn.service.DeliveryService;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.BatchVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.PushRemainingVO;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/** 交付批次（F-11、F-18）：查询、续推、人工确认、作废、交付告警。 */
@RestController
@RequestMapping("/api/v1/sn/delivery")
public class DeliveryController {
    private final DeliveryService service;

    public DeliveryController(DeliveryService service) {
        this.service = service;
    }

    @GetMapping("/batches")
    public Result<PageVO<BatchVO>> batches(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String batchType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String transferNo,
            @RequestParam(required = false) Boolean asc,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(
                service.listBatches(
                        factoryCode,
                        pi,
                        batchNo,
                        source,
                        batchType,
                        result,
                        transferNo,
                        Boolean.TRUE.equals(asc),
                        PageReq.of(page, pageSize)));
    }

    @GetMapping("/batches/{batchNo}")
    public Result<BatchVO> batch(@PathVariable String batchNo) {
        return Result.ok(service.batchDetail(batchNo));
    }

    @GetMapping("/batches/{batchNo}/items")
    public Result<PageVO<Map<String, Object>>> items(
            @PathVariable String batchNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.batchItems(batchNo, PageReq.of(page, pageSize)));
    }

    @GetMapping("/batches/{batchNo}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String batchNo) {
        return Xlsx.response(service.exportBatch(batchNo));
    }

    @PostMapping("/batches/{batchNo}/confirm")
    public Result<BatchVO> confirm(@PathVariable String batchNo, @RequestBody BatchManualDTO body) {
        return Result.ok(service.manualConfirm(batchNo, body.reason, body.basis));
    }

    @PostMapping("/batches/{batchNo}/void")
    public Result<BatchVO> voidBatch(
            @PathVariable String batchNo, @RequestBody BatchManualDTO body) {
        return Result.ok(service.voidBatch(batchNo, body.reason, body.basis));
    }

    @PostMapping("/resume")
    public Result<PushRemainingVO> resume(@RequestBody PushResumeDTO body) {
        return Result.ok(service.resume(body.factoryCode, body.key));
    }

    @GetMapping("/remaining")
    public Result<PushRemainingVO> remaining(
            @RequestParam String factoryCode, @RequestParam String key) {
        return Result.ok(service.remaining(factoryCode, key));
    }

    @GetMapping("/alerts")
    public Result<PageVO<Map<String, Object>>> alerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.listAlerts(status, PageReq.of(page, pageSize)));
    }

    @PostMapping("/alerts/{id}/handle")
    public Result<Map<String, Object>> handle(@PathVariable long id, @RequestBody NoteDTO body) {
        return Result.ok(service.handleAlert(id, body.note));
    }
}
