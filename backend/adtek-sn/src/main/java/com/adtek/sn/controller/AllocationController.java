package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.AllocateDTO;
import com.adtek.sn.service.AllocationService;
import com.adtek.sn.support.PageReq;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** SN 分配（F-11 分配）：左侧按单据列出，右侧为所选单据所在 PI 的详情。 */
@RestController
@RequestMapping("/api/v1/sn/allocation")
public class AllocationController {
    private final AllocationService service;

    public AllocationController(AllocationService service) {
        this.service = service;
    }

    @GetMapping("/bills")
    public Result<Map<String, Object>> bills(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.bills(q, tab, PageReq.of(page, pageSize)));
    }

    @GetMapping("/context")
    public Result<Map<String, Object>> context(@RequestParam(required = false) String pi) {
        return Result.ok(service.context(pi));
    }

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody AllocateDTO body) {
        return Result.ok(service.preview(body));
    }

    @PostMapping("/allocate")
    public Result<Map<String, Object>> allocate(@RequestBody AllocateDTO body) {
        return Result.ok(service.allocate(body));
    }
}
