package com.adtek.system.mapper;

import com.adtek.system.domain.RoleEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface RoleMapper {
    List<RoleEntity> list();

    RoleEntity find(@Param("id") long id);

    int insert(@Param("entity") RoleEntity entity);

    int update(@Param("entity") RoleEntity entity);
}
