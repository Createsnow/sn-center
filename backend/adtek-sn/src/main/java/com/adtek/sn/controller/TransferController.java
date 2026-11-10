package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.TransferDTO;
import com.adtek.sn.service.TransferService;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.PageVO;

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

/** 转单、转组织与转移记录。 */
@RestController
@RequestMapping("/api/v1/sn/transfers")
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageVO<Map<String, Object>>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(
                service.listAll(kind, factoryCode, pi, billNo, PageReq.of(page, pageSize)));
    }

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody TransferDTO body) {
        return Result.ok(service.previewAuto(body));
    }

    @PostMapping
    public Result<Map<String, Object>> transfer(@RequestBody TransferDTO body) {
        return Result.ok(service.transfer(body));
    }

    @GetMapping("/locate")
    public Result<Map<String, Object>> locate(@RequestParam(required = false) String q) {
        return Result.ok(service.locate(q));
    }

    @GetMapping("/sources")
    public Result<Map<String, Object>> sources(
            @RequestParam(required = false, defaultValue = "ORG") String kind,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String factoryCode) {
        return Result.ok(service.sources(kind, factoryCode, pi));
    }

    @PostMapping("/bill/preview")
    public Result<Map<String, Object>> billPreview(@RequestBody TransferDTO body) {
        return Result.ok(service.preview("BILL", body));
    }

    @PostMapping("/bill")
    public Result<Map<String, Object>> bill(@RequestBody TransferDTO body) {
        return Result.ok(service.transferBill(body));
    }

    @GetMapping("/bill")
    public Result<PageVO<Map<String, Object>>> billList(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.listBill(factoryCode, pi, billNo, PageReq.of(page, pageSize)));
    }

    @GetMapping("/bill/{id}")
    public Result<Map<String, Object>> billGet(@PathVariable long id) {
        return Result.ok(service.billView(id));
    }

    @GetMapping("/bill/{id}/items")
    public Result<PageVO<Map<String, Object>>> billItems(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.items("BILL", id, PageReq.of(page, pageSize)));
    }

    @PostMapping("/org/candidates")
    public Result<Map<String, Object>> orgCandidates(@RequestBody TransferDTO body) {
        return Result.ok(service.preview("ORG", body));
    }

    @PostMapping("/org/direct")
    public Result<Map<String, Object>> orgDirect(@RequestBody TransferDTO body) {
        return Result.ok(service.direct(body));
    }

    @GetMapping("/org")
    public Result<PageVO<Map<String, Object>>> orgList(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.listOrg(factoryCode, pi, billNo, PageReq.of(page, pageSize)));
    }

    @GetMapping("/org/{id}")
    public Result<Map<String, Object>> orgGet(@PathVariable long id) {
        return Result.ok(service.orgView(id));
    }

    @GetMapping("/org/{id}/items")
    public Result<PageVO<Map<String, Object>>> orgItems(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.items("ORG", id, PageReq.of(page, pageSize)));
    }

    @GetMapping("/org/{id}/out-list")
    public ResponseEntity<StreamingResponseBody> outList(@PathVariable long id) {
        return Xlsx.response(service.outList(id));
    }
}
