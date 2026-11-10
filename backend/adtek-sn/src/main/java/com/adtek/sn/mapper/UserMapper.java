package com.adtek.sn.mapper;

import com.adtek.sn.domain.UserEntity;
import com.adtek.sn.vo.UserFactoryVO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 账户（sn_user）与授权生产组织（sn_user_factory）。 */
@Mapper
public interface UserMapper {
    UserEntity byId(@Param("id") long id);

    UserEntity byEmpNo(@Param("empNo") String empNo);

    List<String> boundFactories(@Param("userId") long userId);

    List<UserFactoryVO> boundOf(@Param("ids") Collection<Long> ids);

    long count(
            @Param("kind") String kind,
            @Param("q") String q,
            @Param("qLike") String qLike,
            @Param("role") String role,
            @Param("status") String status,
            @Param("factory") String factory);

    List<UserEntity> page(
            @Param("kind") String kind,
            @Param("q") String q,
            @Param("qLike") String qLike,
            @Param("role") String role,
            @Param("status") String status,
            @Param("factory") String factory,
            @Param("offset") int offset,
            @Param("size") int size);

    LocalDateTime lockLastLogin(@Param("id") long id);

    int setLastLogin(@Param("id") long id, @Param("at") LocalDateTime at);

    int setPassword(
            @Param("id") long id,
            @Param("hash") String hash,
            @Param("pwdChangedAt") LocalDateTime pwdChangedAt,
            @Param("now") LocalDateTime now);

    int setLang(@Param("id") long id, @Param("lang") String lang);

    int insert(@Param("entity") UserEntity entity);

    int deleteBindings(@Param("userId") long userId);

    int insertBinding(@Param("userId") long userId, @Param("factoryCode") String factoryCode);

    long activeAdmins();

    int update(
            @Param("id") long id,
            @Param("name") String name,
            @Param("role") String role,
            @Param("factoryCode") String factoryCode,
            @Param("ipWhitelist") String ipWhitelist,
            @Param("now") LocalDateTime now);

    int resetSecret(
            @Param("id") long id,
            @Param("hash") String hash,
            @Param("sealed") String sealed,
            @Param("pwdChangedAt") LocalDateTime pwdChangedAt,
            @Param("now") LocalDateTime now);

    int disable(@Param("id") long id, @Param("by") String by, @Param("now") LocalDateTime now);

    int enable(@Param("id") long id, @Param("now") LocalDateTime now);

    int restoreAdmin(
            @Param("id") long id,
            @Param("hash") String hash,
            @Param("pwdChangedAt") LocalDateTime pwdChangedAt,
            @Param("now") LocalDateTime now);

    long countAll();

    UserEntity lockAuth(@Param("id") long id);

    int unlockAuth(@Param("id") long id);

    int setAuthFailures(
            @Param("id") long id,
            @Param("failures") int failures,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    int authOk(@Param("id") long id, @Param("now") LocalDateTime now);
}
