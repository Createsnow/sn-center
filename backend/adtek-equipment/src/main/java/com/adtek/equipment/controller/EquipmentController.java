package com.adtek.equipment.controller;

import com.adtek.common.core.*;
import com.adtek.equipment.dto.*;
import com.adtek.equipment.query.EquipmentQuery;
import com.adtek.equipment.service.EquipmentService;
import com.adtek.equipment.vo.EquipmentVO;
import com.adtek.framework.web.TraceFilter;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP 仅解码/校验/组装统一结果；权限、范围、并发与审计均在服务边界实施。 */
@Tag(name = "设备台账")
@RestController
@RequestMapping(value = "/api/v1/equipment/assets", produces = "application/json")
public class EquipmentController {
    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<EquipmentVO>> list(
            @Valid @ModelAttribute EquipmentQuery query, HttpServletRequest request) {
        return Result.success(service.list(query), TraceFilter.current(request));
    }

    @GetMapping("/{id}")
    public Result<EquipmentVO> detail(@PathVariable String id, HttpServletRequest request) {
        return Result.success(service.detail(id), TraceFilter.current(request));
    }

    @PostMapping
    public Result<EquipmentVO> create(
            @Valid @RequestBody EquipmentCreateDTO command, HttpServletRequest request) {
        return Result.success(service.create(command), TraceFilter.current(request));
    }

    @PostMapping("/{id}/update")
    public Result<EquipmentVO> update(
            @PathVariable String id,
            @Valid @RequestBody EquipmentUpdateDTO command,
            HttpServletRequest request) {
        return Result.success(service.update(id, command), TraceFilter.current(request));
    }
}
