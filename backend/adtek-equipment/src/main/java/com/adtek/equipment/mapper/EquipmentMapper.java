package com.adtek.equipment.mapper;

import com.adtek.equipment.domain.EquipmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 所有语句在 EquipmentDataPermissionRules 精确登记；更新同时约束当前权限与版本。 */
@Mapper
public interface EquipmentMapper extends BaseMapper<EquipmentEntity> {
    int change(
            @Param("id") long id,
            @Param("name") String name,
            @Param("enabled") boolean enabled,
            @Param("version") long version,
            @Param("actorId") long actorId);
}
