package com.adtek.system.controller;
import com.adtek.common.core.Result;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.dto.*;
import com.adtek.system.vo.*;
import com.adtek.system.service.ConfigurationManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Tag(name="系统配置")
@RestController
@RequestMapping(value="/api/v1/system",produces="application/json")
public class ConfigurationManagementController {
    private final ConfigurationManagementService service;
    public ConfigurationManagementController(ConfigurationManagementService service) { this.service=service; }
    @GetMapping("/roles") @PreAuthorize("hasAuthority('system:role:list')")
    @Operation(summary="Role 配置列表", description="要求全部部门范围及显式安全管理权限；最大 1000 项，超限拒绝。")
    public Result<List<RoleVO>> roles(HttpServletRequest request) {
        return Result.success(service.roles(),TraceFilter.current(request));
    }
    @PostMapping("/roles") @PreAuthorize("hasAuthority('system:role:update')")
    @Operation(summary="新建 Role 配置")
    public Result<ConfigurationChangeVO> addRole(@Valid @RequestBody RoleSaveDTO command,HttpServletRequest request) {
        return Result.success(service.createRole(command),TraceFilter.current(request));
    }
    @PostMapping("/roles/{id}/update") @PreAuthorize("hasAuthority('system:role:update')")
    @Operation(summary="修改/启停 Role 配置",description="必须提供 version；冲突返回 409；reauthenticate=true 表示当前会话已撤销。")
    public Result<ConfigurationChangeVO> updateRole(@PathVariable String id,@Valid @RequestBody RoleSaveDTO command,HttpServletRequest request) {
        return Result.success(service.saveRole(id,command),TraceFilter.current(request));
    }
    @GetMapping("/departments") @PreAuthorize("hasAuthority('system:dept:list')")
    @Operation(summary="Department 配置列表", description="要求全部部门范围及显式安全管理权限；最大 1000 项，超限拒绝。")
    public Result<List<DepartmentVO>> departments(HttpServletRequest request) {
        return Result.success(service.departments(),TraceFilter.current(request));
    }
    @PostMapping("/departments") @PreAuthorize("hasAuthority('system:dept:update')")
    @Operation(summary="新建 Department 配置")
    public Result<ConfigurationChangeVO> addDepartment(@Valid @RequestBody DepartmentSaveDTO command,HttpServletRequest request) {
        return Result.success(service.createDepartment(command),TraceFilter.current(request));
    }
    @PostMapping("/departments/{id}/update") @PreAuthorize("hasAuthority('system:dept:update')")
    @Operation(summary="修改/启停 Department 配置",description="必须提供 version；冲突返回 409；reauthenticate=true 表示当前会话已撤销。")
    public Result<ConfigurationChangeVO> updateDepartment(@PathVariable String id,@Valid @RequestBody DepartmentSaveDTO command,HttpServletRequest request) {
        return Result.success(service.saveDepartment(id,command),TraceFilter.current(request));
    }
    @GetMapping("/menus") @PreAuthorize("hasAuthority('system:menu:list')")
    @Operation(summary="ManagedMenu 配置列表", description="要求全部部门范围及显式安全管理权限；最大 1000 项，超限拒绝。")
    public Result<List<ManagedMenuVO>> menus(HttpServletRequest request) {
        return Result.success(service.menus(),TraceFilter.current(request));
    }
    @PostMapping("/menus") @PreAuthorize("hasAuthority('system:menu:update')")
    @Operation(summary="新建 ManagedMenu 配置")
    public Result<ConfigurationChangeVO> addManagedMenu(@Valid @RequestBody ManagedMenuSaveDTO command,HttpServletRequest request) {
        return Result.success(service.createManagedMenu(command),TraceFilter.current(request));
    }
    @PostMapping("/menus/{id}/update") @PreAuthorize("hasAuthority('system:menu:update')")
    @Operation(summary="修改/启停 ManagedMenu 配置",description="必须提供 version；冲突返回 409；reauthenticate=true 表示当前会话已撤销。")
    public Result<ConfigurationChangeVO> updateManagedMenu(@PathVariable String id,@Valid @RequestBody ManagedMenuSaveDTO command,HttpServletRequest request) {
        return Result.success(service.saveManagedMenu(id,command),TraceFilter.current(request));
    }
    @GetMapping("/dictionary-types") @PreAuthorize("hasAuthority('system:dict:list')")
    @Operation(summary="DictionaryType 配置列表", description="要求全部部门范围及显式安全管理权限；最大 1000 项，超限拒绝。")
    public Result<List<DictionaryTypeVO>> dictionaryTypes(HttpServletRequest request) {
        return Result.success(service.dictionaryTypes(),TraceFilter.current(request));
    }
    @PostMapping("/dictionary-types") @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary="新建 DictionaryType 配置")
    public Result<ConfigurationChangeVO> addDictionaryType(@Valid @RequestBody DictionaryTypeSaveDTO command,HttpServletRequest request) {
        return Result.success(service.createDictionaryType(command),TraceFilter.current(request));
    }
    @PostMapping("/dictionary-types/{id}/update") @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary="修改/启停 DictionaryType 配置",description="必须提供 version；冲突返回 409；reauthenticate=true 表示当前会话已撤销。")
    public Result<ConfigurationChangeVO> updateDictionaryType(@PathVariable String id,@Valid @RequestBody DictionaryTypeSaveDTO command,HttpServletRequest request) {
        return Result.success(service.saveDictionaryType(id,command),TraceFilter.current(request));
    }
    @GetMapping("/dictionary-items") @PreAuthorize("hasAuthority('system:dict:list')")
    @Operation(summary="DictionaryItem 配置列表", description="要求全部部门范围及显式安全管理权限；最大 1000 项，超限拒绝。")
    public Result<List<DictionaryItemVO>> dictionaryItems(@RequestParam String typeId, HttpServletRequest request) {
        return Result.success(service.dictionaryItems(typeId),TraceFilter.current(request));
    }
    @PostMapping("/dictionary-items") @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary="新建 DictionaryItem 配置")
    public Result<ConfigurationChangeVO> addDictionaryItem(@Valid @RequestBody DictionaryItemSaveDTO command,HttpServletRequest request) {
        return Result.success(service.createDictionaryItem(command),TraceFilter.current(request));
    }
    @PostMapping("/dictionary-items/{id}/update") @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary="修改/启停 DictionaryItem 配置",description="必须提供 version；冲突返回 409；reauthenticate=true 表示当前会话已撤销。")
    public Result<ConfigurationChangeVO> updateDictionaryItem(@PathVariable String id,@Valid @RequestBody DictionaryItemSaveDTO command,HttpServletRequest request) {
        return Result.success(service.saveDictionaryItem(id,command),TraceFilter.current(request));
    }
}
