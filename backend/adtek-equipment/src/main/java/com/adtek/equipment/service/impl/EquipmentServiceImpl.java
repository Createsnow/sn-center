package com.adtek.equipment.service.impl;

import static com.adtek.common.annotation.OperLog.BusinessType.*;
import static com.adtek.equipment.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.DataScope;
import com.adtek.common.annotation.OperLog;
import com.adtek.common.core.*;
import com.adtek.equipment.convert.EquipmentConvert;
import com.adtek.equipment.domain.EquipmentEntity;
import com.adtek.equipment.dto.*;
import com.adtek.equipment.mapper.EquipmentMapper;
import com.adtek.equipment.query.EquipmentQuery;
import com.adtek.equipment.service.EquipmentService;
import com.adtek.equipment.vo.EquipmentVO;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.security.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class EquipmentServiceImpl implements EquipmentService {
    private final EquipmentMapper rows;
    private final EquipmentConvert convert;
    private final CurrentActor actors;
    private final AuthorizationMutationPort authorization;
    private final OrganizationMembershipPort organization;
    private final CommandValidator validator;

    public EquipmentServiceImpl(
            EquipmentMapper rows,
            EquipmentConvert convert,
            CurrentActor actors,
            AuthorizationMutationPort authorization,
            OrganizationMembershipPort organization,
            CommandValidator validator) {
        this.rows = rows;
        this.convert = convert;
        this.actors = actors;
        this.authorization = authorization;
        this.organization = organization;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "equipment:asset", permission = "equipment:asset:list")
    public PageResult<EquipmentVO> list(EquipmentQuery query) {
        validator.requireValid(query);
        var where =
                Wrappers.<EquipmentEntity>lambdaQuery()
                        .eq(
                                query.getEnabled() != null,
                                EquipmentEntity::getEnabled,
                                query.getEnabled())
                        .orderByDesc(EquipmentEntity::getId);
        if (query.getKeyword() != null && !query.getKeyword().isBlank())
            where.and(
                    q ->
                            q.like(EquipmentEntity::getCode, query.getKeyword().strip())
                                    .or()
                                    .like(EquipmentEntity::getName, query.getKeyword().strip()));
        var page = rows.selectPage(new Page<>(query.getPage(), query.getSize()), where);
        return new PageResult<>(
                page.getRecords().stream().map(convert::toVO).toList(),
                Long.toString(page.getTotal()),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "equipment:asset", permission = "equipment:asset:detail")
    public EquipmentVO detail(String id) {
        return convert.toVO(require(id(id)));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @DataScope(resource = "equipment:asset", permission = "equipment:asset:create")
    @OperLog(
            title = "设备台账",
            action = "equipment:asset:create",
            businessType = INSERT,
            fields = {"code", "name", "enabled", "deptId"})
    public EquipmentVO create(EquipmentCreateDTO command) {
        validator.requireValid(command);
        var actor = lock("equipment:asset:create");
        long department =
                organization
                        .currentDepartment(actor.userId())
                        .orElseThrow(() -> new BusinessException(DEPARTMENT_REQUIRED));
        if (!actor.grants()
                .get("equipment:asset:create")
                .departments()
                .permits(actor.userId(), actor.userId(), department)) throw forbidden();
        var row = convert.fromCreate(command);
        row.setName(command.name().strip());
        row.setDeptId(department);
        row.setCreateBy(actor.userId());
        row.setUpdateBy(actor.userId());
        row.setVersion(1L);
        row.setCreateTime(Instant.now());
        row.setUpdateTime(row.getCreateTime());
        try {
            rows.insert(row);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(CODE_EXISTS);
        }
        AuditContext.record(row.getId().toString(), Map.of(), snapshot(row));
        return convert.toVO(row);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @DataScope(resource = "equipment:asset", permission = "equipment:asset:update")
    @OperLog(
            title = "设备台账",
            action = "equipment:asset:update",
            businessType = UPDATE,
            fields = {"code", "name", "enabled", "deptId"})
    public EquipmentVO update(String value, EquipmentUpdateDTO command) {
        validator.requireValid(command);
        var actor = lock("equipment:asset:update");
        long id = id(value), version = id(command.version());
        var before = require(id);
        if (rows.change(id, command.name().strip(), command.enabled(), version, actor.userId())
                != 1) throw new BusinessException(VERSION_CHANGED);
        var after = require(id);
        AuditContext.record(value, snapshot(before), snapshot(after));
        return convert.toVO(after);
    }

    private EquipmentEntity require(long id) {
        var row = rows.selectById(id);
        if (row == null)
            throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        return row;
    }

    private Actor lock(String permission) {
        var actor = authorization.lockCurrent(actors.requirePermission(permission));
        if (!actor.permissions().contains(permission)) throw forbidden();
        return actor;
    }

    private static BusinessException forbidden() {
        return new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);
    }

    private static long id(String text) {
        try {
            if (text == null || !text.matches("[1-9][0-9]{0,18}"))
                throw new NumberFormatException();
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(
                    com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);
        }
    }

    private static Map<String, Object> snapshot(EquipmentEntity row) {
        return Map.of(
                "code",
                row.getCode(),
                "name",
                row.getName(),
                "enabled",
                row.getEnabled(),
                "deptId",
                row.getDeptId().toString());
    }
}
