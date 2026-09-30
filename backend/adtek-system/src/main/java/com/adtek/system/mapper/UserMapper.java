package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

/** 所有读写精确登记范围规则；不得调用未登记的 BaseMapper 删除或任意批量方法。 */
@Mapper
public interface UserMapper extends BaseMapper<SysUserEntity> {
    @Select("SELECT * FROM sys_user WHERE id=#{id} FOR UPDATE")
    SysUserEntity lock(@Param("id") long id);
}
