package com.adtek.system.mapper;

import com.adtek.system.domain.ManagedMenuEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface ManagedMenuMapper {
    @Select(
            "SELECT id, menu_name AS name, parent_id AS parentId, menu_type AS menuType,"
                    + " permission_code AS permission, route_path AS path,"
                    + " component_key AS component,"
                    + " sort_order AS sortOrder, enabled, version FROM sys_menu ORDER BY id"
                    + " LIMIT 1001")
    List<ManagedMenuEntity> list();

    @Select(
            "SELECT id, menu_name AS name, parent_id AS parentId, menu_type AS menuType,"
                    + " permission_code AS permission, route_path AS path,"
                    + " component_key AS component,"
                    + " sort_order AS sortOrder, enabled, version FROM sys_menu WHERE id=#{id}")
    ManagedMenuEntity find(@Param("id") long id);

    @Insert(
            "INSERT INTO"
                    + " sys_menu(menu_name,parent_id,menu_type,permission_code,route_path,"
                    + "component_key,sort_order,enabled)"
                    + " VALUES"
                    + " (#{name},#{parentId},#{menuType},#{permission},#{path},#{component},"
                    + "#{sortOrder},#{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ManagedMenuEntity entity);

    @Update(
            "UPDATE sys_menu SET"
                    + " menu_name=#{name},parent_id=#{parentId},menu_type=#{menuType},"
                    + "permission_code=#{permission},route_path=#{path},"
                    + "component_key=#{component},sort_order=#{sortOrder},enabled=#{enabled},"
                    + "version=version+1"
                    + " WHERE id=#{id} AND version=#{version}")
    int update(ManagedMenuEntity entity);
}
