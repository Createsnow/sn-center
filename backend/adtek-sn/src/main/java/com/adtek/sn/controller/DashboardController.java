package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.service.DashboardService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 首页看板。 */
@RestController
@RequestMapping("/api/v1/sn/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> load() {
        return Result.ok(service.load());
    }
}
