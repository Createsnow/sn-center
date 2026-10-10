package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 当前身份装配专用语句；只按可信主体ID读取，不用部门推导工厂。 */
@Mapper
public interface FactoryMembershipMapper {
    @Select(
            """
            SELECT f.id, f.name, f.code FROM sn_user_factory uf
            JOIN sn_factory f ON f.id=uf.factory_id WHERE uf.user_id=#{userId}
            ORDER BY f.code
            """)
    List<com.adtek.framework.security.FactoryMembershipPort.Factory> details(
            @Param("userId") long userId);

    @Select("SELECT user_id FROM sn_user_factory WHERE factory_id=#{factoryId} ORDER BY user_id")
    List<Long> members(@Param("factoryId") long factoryId);

    @Select("SELECT factory_id FROM sn_user_factory WHERE user_id=#{userId} ORDER BY factory_id")
    List<Long> factoryIds(@Param("userId") long userId);

    @Delete("DELETE FROM sn_user_factory WHERE user_id=#{userId}")
    int clear(@Param("userId") long userId);

    @Insert("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(#{userId},#{factoryId})")
    int add(@Param("userId") long userId, @Param("factoryId") long factoryId);
}
