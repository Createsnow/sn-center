package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.service.SnContextService;
import com.adtek.sn.vo.SnContextVO;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnContextController {
    private final SnContextService service;

    public SnContextController(SnContextService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/sn/context")
    public Result<SnContextVO> current(HttpServletRequest request) {
        return Result.success(service.current(), TraceFilter.current(request));
    }
}
