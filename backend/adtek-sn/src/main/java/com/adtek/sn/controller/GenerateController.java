package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.GenDeleteDTO;
import com.adtek.sn.dto.GenPreviewDTO;
import com.adtek.sn.dto.GenRunDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.service.GenerateService;
import com.adtek.sn.vo.RevokeConfigVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** SN 生成与删号。 */
@RestController
@RequestMapping("/api/v1/sn/generate")
public class GenerateController {
    private final GenerateService service;

    public GenerateController(GenerateService service) {
        this.service = service;
    }

    @GetMapping("/pis")
    public Result<Map<String, Object>> pis(
            @RequestParam(required = false) String q, @RequestParam(required = false) String tab) {
        return Result.ok(service.pis(q, tab));
    }

    @GetMapping("/context")
    public Result<Map<String, Object>> context(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo) {
        return Result.ok(service.context(pi, billNo));
    }

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody GenPreviewDTO body) {
        return Result.ok(service.preview(body));
    }

    @GetMapping("/gaps")
    public Result<Map<String, Object>> gaps(@RequestParam(required = false) String pi) {
        return Result.ok(service.gaps(pi));
    }

    @GetMapping("/segments")
    public Result<Map<String, Object>> segments(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo) {
        return Result.ok(service.segments(pi, billNo));
    }

    @GetMapping("/segments/sns")
    public Result<Map<String, Object>> segmentSns(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String seqPi,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer billSeq) {
        return Result.ok(service.segmentSns(pi, billNo, seqPi, start, end, page, size, billSeq));
    }

    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody GenRunDTO body) {
        return Result.ok(service.run(body.token));
    }

    @GetMapping("/jobs")
    public Result<List<Map<String, Object>>> jobs(
            @RequestParam String pi, @RequestParam(required = false) Integer limit) {
        return Result.ok(service.jobs(pi, limit));
    }

    @GetMapping("/batches/{batch}")
    public Result<Map<String, Object>> progress(@PathVariable String batch) {
        return Result.ok(service.progress(batch));
    }

    @PostMapping("/batches/{batch}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String batch) {
        return Result.ok(service.cancel(batch));
    }

    @PostMapping("/delete/preview")
    public Result<Map<String, Object>> deletePreview(@RequestBody GenDeleteDTO body) {
        return Result.ok(service.deletePreview(body));
    }

    @PostMapping("/delete")
    public Result<Map<String, Object>> delete(@RequestBody GenDeleteDTO body) {
        return Result.ok(service.delete(body));
    }

    @GetMapping("/deleted")
    public Result<List<Map<String, Object>>> deleted(@RequestParam(required = false) String pi) {
        return Result.ok(service.deleted(pi));
    }

    @PostMapping("/revoke-config")
    public Result<RevokeConfigVO> revokeConfig(@RequestBody PiLinesDTO body) {
        return Result.ok(service.revokeConfig(body));
    }
}
