package com.adtek.system.controller;
import com.adtek.common.core.*;
import com.adtek.framework.web.TraceFilter;
import com.adtek.system.dto.*;
import com.adtek.system.query.UserQuery;
import com.adtek.system.service.UserManagementService;
import com.adtek.system.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理")
@RestController
@RequestMapping(value = "/api/v1/system", produces = "application/json")
public class UserManagementController {
    private final UserManagementService users;
    public UserManagementController(UserManagementService users) { this.users = users; }
    @Operation(summary = "按当前 list 权限范围分页查询用户")
    @GetMapping("/users") @PreAuthorize("hasAuthority('system:user:list')")
    public Result<PageResult<ManagedUserVO>> list(@Valid @ModelAttribute UserQuery query, HttpServletRequest request) {
        return Result.success(users.list(query), TraceFilter.current(request));
    }
    @Operation(summary = "读取 add/update 对应的部门和角色选项", description = "角色仍需提交时通过反提权校验；不是预授权。")
    @GetMapping("/users/options")
    public Result<UserOptionsVO> options(@RequestParam String operation, HttpServletRequest request) {
        return Result.success(users.options(operation), TraceFilter.current(request));
    }
    @Operation(summary = "当前账号的授权菜单", description = "客户端本地组件注册表决定已实现页面，不执行服务端提供的代码。")
    @GetMapping("/menus/current")
    public Result<List<MenuVO>> menus(HttpServletRequest request) { return Result.success(users.menus(), TraceFilter.current(request)); }
    @Operation(summary = "创建强制首次改密账号")
    @PostMapping("/users") @PreAuthorize("hasAuthority('system:user:add')")
    public Result<ManagedUserVO> create(@Valid @RequestBody UserCreateDTO command, HttpServletRequest request) {
        return Result.success(users.create(command), TraceFilter.current(request));
    }
    @Operation(summary = "修改用户资料/部门/角色", description = "version 为原安全版本；成功使目标旧会话失效；409 表示版本冲突或最后管理员保护。")
    @PostMapping("/users/{id}/update") @PreAuthorize("hasAuthority('system:user:update')")
    public Result<Void> update(@PathVariable String id, @Valid @RequestBody UserUpdateDTO command, HttpServletRequest request) {
        users.update(id, command); return Result.success(null, TraceFilter.current(request));
    }
    @Operation(summary = "停用或重新启用用户", description = "无删除接口；本人停用及最后管理员失效被拒绝。")
    @PostMapping("/users/{id}/status") @PreAuthorize("hasAuthority('system:user:status')")
    public Result<Void> status(@PathVariable String id, @Valid @RequestBody UserStatusDTO command, HttpServletRequest request) {
        users.status(id, command); return Result.success(null, TraceFilter.current(request));
    }
    @Operation(summary = "重置其他用户密码", description = "重置后强制改密；密码不回显，不进入审计。")
    @PostMapping("/users/{id}/password") @PreAuthorize("hasAuthority('system:user:reset')")
    public Result<Void> reset(@PathVariable String id, @Valid @RequestBody UserPasswordResetDTO command, HttpServletRequest request) {
        users.resetPassword(id, command); return Result.success(null, TraceFilter.current(request));
    }
}
