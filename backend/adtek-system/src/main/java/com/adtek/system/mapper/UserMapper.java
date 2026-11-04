package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 所有读写精确登记范围规则；不得调用未登记的 BaseMapper 删除或任意批量方法。 */
@Mapper
public interface UserMapper extends BaseMapper<SysUserEntity> {
    SysUserEntity lock(@Param("id") long id);
}
