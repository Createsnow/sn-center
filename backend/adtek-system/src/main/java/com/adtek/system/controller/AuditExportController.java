package com.adtek.system.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.dto.AuditExportCommandDTO;
import com.adtek.system.service.AuditExportService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system/audit-exports")
public class AuditExportController {
    private final AuditExportService exports;

    public AuditExportController(AuditExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    public ResponseEntity<Result<TaskVO>> submit(
            @Valid @RequestBody AuditExportCommandDTO command, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(exports.submit(command), TraceFilter.current(request)));
    }

    @GetMapping("/operations/{operationId}")
    public Result<TaskVO> operation(@PathVariable String operationId, HttpServletRequest request) {
        return Result.success(exports.operation(operationId), TraceFilter.current(request));
    }
}
