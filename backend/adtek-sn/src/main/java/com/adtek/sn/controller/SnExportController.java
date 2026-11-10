package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.service.SnExportService;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.PageVO;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/** SN 导出（分配页「已分配」页签）：已分配的号按「生产组织 + PI + 单据」查看与下载。 */
@RestController
@RequestMapping("/api/v1/sn/export")
public class SnExportController {
    private final SnExportService service;

    public SnExportController(SnExportService service) {
        this.service = service;
    }

    @GetMapping("/bills")
    public Result<PageVO<Map<String, Object>>> bills(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) Boolean mes,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.bills(factoryCode, q, mes, pi, PageReq.of(page, pageSize)));
    }

    @GetMapping("/lines")
    public Result<List<Map<String, Object>>> lines(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String factoryCode) {
        return Result.ok(service.lines(factoryCode, pi, billNo));
    }

    @GetMapping("/segments")
    public Result<PageVO<Map<String, Object>>> segments(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.segments(factoryCode, pi, billNo, PageReq.of(page, pageSize)));
    }

    @GetMapping("/items")
    public Result<PageVO<Map<String, Object>>> items(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer billSeq,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String snFrom,
            @RequestParam(required = false) String snTo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(
                service.items(
                        factoryCode,
                        pi,
                        billNo,
                        PageReq.of(page, pageSize),
                        billSeq,
                        material,
                        q,
                        snFrom,
                        snTo));
    }

    @GetMapping("/file")
    public ResponseEntity<StreamingResponseBody> file(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String snFrom,
            @RequestParam(required = false) String snTo) {
        return Xlsx.response(service.file(factoryCode, pi, billNo, material, snFrom, snTo));
    }
}
