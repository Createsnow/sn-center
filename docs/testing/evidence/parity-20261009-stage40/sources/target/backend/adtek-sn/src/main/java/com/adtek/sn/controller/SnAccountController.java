package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.SnAccountCreateDTO;
import com.adtek.sn.dto.SnAccountUpdateDTO;
import com.adtek.sn.service.SnAccountService;
import com.adtek.sn.vo.SnAccountSavedVO;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

/** SN账号表单入口；服务复核当前账号管理与安全委派权限。 */
@RestController
@RequestMapping("/api/v1/sn/users")
public class SnAccountController {
    private final SnAccountService service;

    public SnAccountController(SnAccountService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "按SN三角色及单厂语义新建人员，工号唯一且首次强制改密")
    public Result<SnAccountSavedVO> create(
            @RequestBody SnAccountCreateDTO command, HttpServletRequest request) {
        return Result.success(service.create(command), TraceFilter.current(request));
    }

    @PostMapping("/{id}/update")
    @Operation(summary = "修改标准SN账号；拒绝用简化表单覆盖扩展角色或多厂账号")
    public Result<Void> update(
            @PathVariable String id,
            @RequestBody SnAccountUpdateDTO command,
            HttpServletRequest request) {
        service.update(id, command);
        return Result.success(null, TraceFilter.current(request));
    }
}
