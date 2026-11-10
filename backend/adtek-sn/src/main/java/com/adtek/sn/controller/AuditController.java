package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.query.AuditQuery;
import com.adtek.sn.service.AuditQueryService;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.PageVO;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.Map;

/** 操作痕迹（审计报表）。 */
@RestController
@RequestMapping("/api/v1/sn/audits")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    private static AuditQuery q(
            LocalDate from,
            LocalDate to,
            String action,
            String operator,
            String factoryCode,
            String pi,
            String source,
            String result,
            String batchNo,
            String keyword,
            String billNo) {
        AuditQuery f = new AuditQuery();
        f.dateFrom = from;
        f.dateTo = to;
        f.action = action;
        f.operator = operator;
        f.factoryCode = factoryCode;
        f.pi = pi;
        f.source = source;
        f.result = result;
        f.batchNo = batchNo;
        f.keyword = keyword;
        f.billNo = billNo;
        return f;
    }

    @GetMapping
    public Result<PageVO<Map<String, Object>>> list(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(
                service.list(
                        q(
                                from,
                                to,
                                action,
                                operator,
                                factoryCode,
                                pi,
                                source,
                                result,
                                batchNo,
                                keyword,
                                billNo),
                        PageReq.of(page, pageSize)));
    }

    @GetMapping("/export-check")
    public Result<Map<String, Object>> exportCheck(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String billNo) {
        return Result.ok(
                service.exportCheck(
                        q(
                                from,
                                to,
                                action,
                                operator,
                                factoryCode,
                                pi,
                                source,
                                result,
                                batchNo,
                                keyword,
                                billNo)));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String billNo) {
        return Xlsx.response(
                service.export(
                        q(
                                from,
                                to,
                                action,
                                operator,
                                factoryCode,
                                pi,
                                source,
                                result,
                                batchNo,
                                keyword,
                                billNo)));
    }

    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        return Result.ok(service.meta());
    }
}
