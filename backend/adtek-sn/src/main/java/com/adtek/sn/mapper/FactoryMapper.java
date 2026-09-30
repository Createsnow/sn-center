package com.adtek.sn.mapper;
import com.adtek.sn.domain.FactoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
/** 列表/COUNT由工厂拦截器收窄；内部目录冲突检查只在全部工厂管理用例调用。 */
@Mapper
public interface FactoryMapper extends BaseMapper<FactoryEntity> {
 @Select("SELECT * FROM sn_factory WHERE code=#{code}") FactoryEntity byCode(@Param("code") String code);
 @Select("SELECT * FROM sn_factory WHERE name=#{name}") FactoryEntity byName(@Param("name") String name);
 @Select("SELECT * FROM sn_factory WHERE id=#{id} FOR UPDATE") FactoryEntity lock(@Param("id") long id);
}
