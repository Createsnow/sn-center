package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 单次初始化专用 SQL；固定控制行串行化，普通启动不调用。 */
@Mapper
public interface BootstrapMapper extends BaseMapper<SysUserEntity> {
    Boolean lockInitialization();

    long countUsers();

    int assignInitialRoles(@Param("userId") long userId);

    int markOwnership(@Param("userId") long userId);

    int finishInitialization();
}
