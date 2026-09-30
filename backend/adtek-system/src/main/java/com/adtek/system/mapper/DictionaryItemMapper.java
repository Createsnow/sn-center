package com.adtek.system.mapper;

import com.adtek.system.domain.DictionaryItemEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface DictionaryItemMapper {
    @Select(
            "SELECT id, type_id AS typeId, value, label_zh AS labelZh, label_en AS labelEn,"
                    + " label_vi AS labelVi, sort_order AS sortOrder, enabled, version FROM"
                    + " sys_dict_item WHERE type_id=#{typeId} ORDER BY sort_order, id LIMIT 1001")
    List<DictionaryItemEntity> list(@Param("typeId") long typeId);

    @Select(
            "SELECT id, type_id AS typeId, value, label_zh AS labelZh, label_en AS labelEn,"
                    + " label_vi AS labelVi, sort_order AS sortOrder, enabled, version FROM"
                    + " sys_dict_item WHERE id=#{id}")
    DictionaryItemEntity find(@Param("id") long id);

    @Insert(
            "INSERT INTO sys_dict_item(type_id,value,label_zh,label_en,label_vi,"
                    + "sort_order,enabled)"
                    + " VALUES"
                    + " (#{typeId},#{value},#{labelZh},#{labelEn},"
                    + "#{labelVi},#{sortOrder},#{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DictionaryItemEntity entity);

    @Update(
            "UPDATE sys_dict_item SET"
                    + " type_id=#{typeId},value=#{value},label_zh=#{labelZh},"
                    + "label_en=#{labelEn},label_vi=#{labelVi},sort_order=#{sortOrder},"
                    + "enabled=#{enabled},version=version+1"
                    + " WHERE id=#{id} AND version=#{version}")
    int update(DictionaryItemEntity entity);
}
