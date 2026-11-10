package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.MesConfigDTO;
import com.adtek.sn.dto.MesToggleDTO;
import com.adtek.sn.service.FactoryService;
import com.adtek.sn.vo.FactorySyncVO;
import com.adtek.sn.vo.FactoryToggleVO;
import com.adtek.sn.vo.FactoryVO;
import com.adtek.sn.vo.MesTestVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 生产组织（只读同步）与 MES 对接配置。 */
@RestController
@RequestMapping("/api/v1/sn/factories")
public class FactoryController {
    private final FactoryService factories;

    public FactoryController(FactoryService factories) {
        this.factories = factories;
    }

    @GetMapping
    public Result<List<FactoryVO>> list() {
        return Result.ok(factories.list());
    }

    @PostMapping("/sync")
    public Result<FactorySyncVO> sync() {
        return Result.ok(factories.sync());
    }

    @PostMapping("/{code}/mes-config")
    public Result<FactoryVO> config(@PathVariable String code, @RequestBody MesConfigDTO body) {
        return Result.ok(factories.saveConfig(code, body));
    }

    @PostMapping("/{code}/mes-test")
    public Result<MesTestVO> test(@PathVariable String code, @RequestBody MesConfigDTO body) {
        return Result.ok(factories.test(code, body));
    }

    @GetMapping("/{code}/mes-toggle")
    public Result<FactoryToggleVO> togglePreview(@PathVariable String code) {
        return Result.ok(factories.togglePreview(code));
    }

    @PostMapping("/{code}/mes-toggle")
    public Result<FactoryVO> toggle(@PathVariable String code, @RequestBody MesToggleDTO body) {
        return Result.ok(factories.setMesEnabled(code, body.enabled));
    }
}
