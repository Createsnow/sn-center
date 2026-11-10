package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.service.SnImportService;
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

/**
 * SN 导入：上传的 xlsx 直接作请求体（Content-Type: application/octet-stream），文件名放在查询参数 fileName；先 /check
 * 检查（不落库），再确定导入。
 */
@RestController
@RequestMapping("/api/v1/sn/sn-import")
public class SnImportController {
    private final SnImportService service;

    public SnImportController(SnImportService service) {
        this.service = service;
    }

    @GetMapping("/template")
    public ResponseEntity<StreamingResponseBody> template() {
        return Xlsx.response(service.template());
    }

    @PostMapping("/check")
    public Result<Map<String, Object>> check(@RequestBody(required = false) byte[] body) {
        return Result.ok(service.check(body));
    }

    @PostMapping
    public Result<Map<String, Object>> run(
            @RequestBody(required = false) byte[] body,
            @RequestParam(required = false) String fileName) {
        return Result.ok(service.run(body, fileName));
    }

    @GetMapping("/imports/{importNo}")
    public Result<Map<String, Object>> summary(@PathVariable String importNo) {
        return Result.ok(service.summary(importNo));
    }

    @PostMapping("/imports/{importNo}/delete")
    public Result<Map<String, Object>> delete(@PathVariable String importNo) {
        return Result.ok(service.delete(importNo));
    }

    @GetMapping("/items")
    public Result<PageVO<Map<String, Object>>> items(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String importNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(service.items(pi, billNo, sn, importNo, PageReq.of(page, pageSize)));
    }
}
