package com.adtek.system.mapper;
import com.adtek.system.domain.DictionaryTypeEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface DictionaryTypeMapper {
    @Select("SELECT id, code, name, enabled, version FROM sys_dict_type ORDER BY id LIMIT 1001")
    List<DictionaryTypeEntity> list();
    @Select("SELECT id, code, name, enabled, version FROM sys_dict_type WHERE id=#{id}")
    DictionaryTypeEntity find(@Param("id") long id);
    @Insert("INSERT INTO sys_dict_type(code,name,enabled) VALUES (#{code},#{name},#{enabled})")
    @Options(useGeneratedKeys=true, keyProperty="id")
    int insert(DictionaryTypeEntity entity);
    @Update("UPDATE sys_dict_type SET code=#{code},name=#{name},enabled=#{enabled},version=version+1 WHERE id=#{id} AND version=#{version}")
    int update(DictionaryTypeEntity entity);
}
