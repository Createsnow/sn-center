package com.adtek.system.mapper;

import com.adtek.system.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 管理写持共同安全协调锁；认证按精确clientId查找，不暴露秘密列表。 */
@Mapper
public interface ServiceClientMapper extends BaseMapper<SysUserEntity> {
    @Select("SELECT user_id,client_id,secret_hash FROM sys_service_client WHERE client_id=#{id}")
    ServiceClientEntity client(String id);

    @Select("SELECT user_id,client_id,secret_hash FROM sys_service_client WHERE user_id=#{id}")
    ServiceClientEntity byUser(long id);

    @Select(
            "SELECT permission_code FROM sys_service_permission WHERE user_id=#{id} ORDER BY"
                    + " permission_code")
    List<String> permissions(long id);

    @Insert(
            "INSERT INTO sys_service_client(user_id,client_id,secret_hash)"
                    + " VALUES(#{id},#{client},#{hash})")
    int create(@Param("id") long id, @Param("client") String client, @Param("hash") String hash);

    @Update(
            "UPDATE sys_service_client SET secret_hash=#{hash},rotated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE user_id=#{id}")
    int rotate(@Param("id") long id, @Param("hash") String hash);

    @Delete("DELETE FROM sys_service_permission WHERE user_id=#{id}")
    int clear(long id);

    @Insert(
            "INSERT INTO sys_service_permission(user_id,permission_code)"
                    + " VALUES(#{id},#{permission})")
    int grant(@Param("id") long id, @Param("permission") String permission);

    @Update(
            "UPDATE sys_user SET"
                    + " security_version=security_version+1,enabled=#{enabled},"
                    + "display_name=#{name},update_by=#{actor},update_time=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=#{id} AND principal_type='SERVICE'")
    int updateClient(
            @Param("id") long id,
            @Param("enabled") boolean enabled,
            @Param("name") String name,
            @Param("actor") long actor);
}
