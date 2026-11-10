package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.UserCreateDTO;
import com.adtek.sn.dto.UserPasswordDTO;
import com.adtek.sn.dto.UserUpdateDTO;
import com.adtek.sn.query.UserQuery;
import com.adtek.sn.service.UserService;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.SecretVO;
import com.adtek.sn.vo.UserVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账户管理（F-01）：只有系统管理员账户。 */
@RestController
@RequestMapping("/api/v1/sn/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public Result<PageVO<UserVO>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        UserQuery query = new UserQuery();
        query.kind = kind;
        query.q = q;
        query.role = role;
        query.status = status;
        query.factoryCode = factoryCode;
        query.page = page;
        query.pageSize = pageSize;
        return Result.ok(users.list(query));
    }

    @PostMapping
    public Result<UserVO> create(@RequestBody UserCreateDTO body) {
        return Result.ok(users.create(body));
    }

    @PostMapping("/{id}/update")
    public Result<UserVO> update(@PathVariable long id, @RequestBody UserUpdateDTO body) {
        return Result.ok(users.update(id, body));
    }

    @PostMapping("/{id}/disable")
    public Result<UserVO> disable(@PathVariable long id) {
        return Result.ok(users.disable(id));
    }

    @PostMapping("/{id}/enable")
    public Result<UserVO> enable(@PathVariable long id) {
        return Result.ok(users.enable(id));
    }

    @PostMapping("/{id}/reset-password")
    public Result<UserVO> resetPassword(@PathVariable long id, @RequestBody UserPasswordDTO body) {
        return Result.ok(users.resetPassword(id, body.password));
    }

    @PostMapping("/{id}/reset-secret")
    public Result<UserVO> resetSecret(@PathVariable long id) {
        return Result.ok(users.resetSecret(id));
    }

    @GetMapping("/{id}/secret")
    public Result<SecretVO> secret(@PathVariable long id) {
        return Result.ok(users.viewSecret(id));
    }
}
