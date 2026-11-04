package com.adtek.system.mapper;

import com.adtek.framework.audit.RequestFailureAuditPort;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只写审计事实；不提供普通修改/删除方法。changes 已由服务白名单构造，并用参数绑定。 */
@Mapper
public interface OperLogMapper {
    /** 只取非秘密身份列；普通一致性读避免独立失败审计等待外层账号锁。 */
    ActorSnapshot actorSnapshot(@Param("id") long id);

    record ActorSnapshot(
            Long departmentId,
            String username,
            String displayName,
            String principalType,
            String roles) {}

    int requestFailure(
            @Param("event") RequestFailureAuditPort.Event event,
            @Param("departmentId") Long departmentId,
            @Param("username") String username);

    int management(
            @Param("userId") long userId,
            @Param("module") String module,
            @Param("businessType") String businessType,
            @Param("action") String action,
            @Param("objectId") String objectId,
            @Param("success") boolean success,
            @Param("changes") String changes,
            @Param("traceId") String traceId,
            @Param("departmentId") Long departmentId,
            @Param("username") String username);

    int insert(
            @Param("userId") Long userId,
            @Param("action") String action,
            @Param("objectId") String objectId,
            @Param("success") boolean success,
            @Param("changes") String changes,
            @Param("traceId") String traceId,
            @Param("departmentId") Long departmentId,
            @Param("username") String username);
}
