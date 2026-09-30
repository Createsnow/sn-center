package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 单次初始化专用 SQL；固定控制行串行化，普通启动不调用。 */
@Mapper
public interface BootstrapMapper extends BaseMapper<SysUserEntity> {
    @Select("SELECT initialized FROM sys_security_control WHERE id=1 FOR UPDATE")
    Boolean lockInitialization();
    @Select("SELECT COUNT(*) FROM sys_user")
    long countUsers();
    @Insert("INSERT INTO sys_user_role(user_id,role_id) VALUES (#{userId},1),(#{userId},2)")
    int assignInitialRoles(@Param("userId") long userId);
    @Update("UPDATE sys_user SET create_by=#{userId}, update_by=#{userId} WHERE id=#{userId}")
    int markOwnership(@Param("userId") long userId);
    @Update("UPDATE sys_security_control SET initialized=TRUE WHERE id=1 AND initialized=FALSE")
    int finishInitialization();
}
