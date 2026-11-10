package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.K3ConfigDTO;
import com.adtek.sn.service.K3ConfigService;
import com.adtek.sn.vo.K3ConfigVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 金蝶连接参数（只有系统管理员）。 */
@RestController
@RequestMapping("/api/v1/sn/k3-config")
public class K3ConfigController {
    private final K3ConfigService service;

    public K3ConfigController(K3ConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<K3ConfigVO> view() {
        return Result.ok(service.view());
    }

    @PostMapping("/test")
    public Result<K3ConfigVO.TestResult> test(@RequestBody K3ConfigDTO body) {
        return Result.ok(service.test(body));
    }

    @PostMapping("/reset")
    public Result<K3ConfigVO> reset() {
        return Result.ok(service.reset());
    }

    @PostMapping
    public Result<K3ConfigVO> save(@RequestBody K3ConfigDTO body) {
        return Result.ok(service.save(body));
    }
}
