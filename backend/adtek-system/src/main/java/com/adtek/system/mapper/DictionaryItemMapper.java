package com.adtek.system.mapper;

import com.adtek.system.domain.DictionaryItemEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface DictionaryItemMapper {
    List<DictionaryItemEntity> list(@Param("typeId") long typeId);

    DictionaryItemEntity find(@Param("id") long id);

    int insert(@Param("entity") DictionaryItemEntity entity);

    int update(@Param("entity") DictionaryItemEntity entity);
}
