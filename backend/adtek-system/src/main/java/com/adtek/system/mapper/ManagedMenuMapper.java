package com.adtek.system.mapper;

import com.adtek.system.domain.ManagedMenuEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface ManagedMenuMapper {
    List<ManagedMenuEntity> list();

    ManagedMenuEntity find(@Param("id") long id);

    int insert(@Param("entity") ManagedMenuEntity entity);

    int update(@Param("entity") ManagedMenuEntity entity);
}
