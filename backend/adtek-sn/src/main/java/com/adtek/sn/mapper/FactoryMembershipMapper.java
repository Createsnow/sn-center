package com.adtek.sn.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 当前身份装配专用语句；只按可信主体ID读取，不用部门推导工厂。 */
@Mapper
public interface FactoryMembershipMapper {
 @Select("SELECT factory_id FROM sn_user_factory WHERE user_id=#{userId} ORDER BY factory_id")
 List<Long> factoryIds(@Param("userId") long userId);
 @Delete("DELETE FROM sn_user_factory WHERE user_id=#{userId}")
 int clear(@Param("userId") long userId);
 @Insert("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(#{userId},#{factoryId})")
 int add(@Param("userId") long userId,@Param("factoryId") long factoryId);
}
