package com.adtek.system.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.adtek.framework.audit.RequestFailureAuditPort;

/** 只写审计事实；不提供普通修改/删除方法。changes 已由服务白名单构造，并用参数绑定。 */
@Mapper
public interface OperLogMapper {
    /** 只取非秘密身份列；普通一致性读避免独立失败审计等待外层账号锁。 */
    @Select("SELECT u.dept_id AS department_id,u.username,u.display_name,u.principal_type,COALESCE((SELECT CAST(JSON_ARRAYAGG(r.role_code) AS CHAR) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id),'[]') AS roles FROM sys_user u WHERE u.id=#{id}")
    ActorSnapshot actorSnapshot(@Param("id") long id);
    record ActorSnapshot(Long departmentId,String username,String displayName,String principalType,String roles) {}
    @Insert("""
            INSERT INTO sys_oper_log(user_id,actor_dept_id,actor_username,module_name,business_type,action,success,changes,trace_id,request_method,request_route,http_status,error_code)
            VALUES(#{event.actorId},#{departmentId},#{username},'请求安全','OTHER',
              IF(#{event.status} >= 500,'http:FAILURE','http:REJECT'),FALSE,'{}',#{event.traceId},#{event.method},#{event.route},#{event.status},#{event.errorCode})
            """)
    int requestFailure(@Param("event") RequestFailureAuditPort.Event event,@Param("departmentId") Long departmentId,@Param("username") String username);
    @Insert("""
            INSERT INTO sys_oper_log(user_id, module_name, business_type, action, object_id, success, changes, trace_id,actor_dept_id,actor_username)
            VALUES(#{userId}, #{module}, #{businessType}, #{action}, #{objectId}, #{success}, #{changes}, #{traceId},#{departmentId},#{username})
            """)
    int management(@Param("userId") long userId, @Param("module") String module, @Param("businessType") String businessType,
                   @Param("action") String action, @Param("objectId") String objectId, @Param("success") boolean success,
                   @Param("changes") String changes, @Param("traceId") String traceId,@Param("departmentId") Long departmentId,@Param("username") String username);
    @Insert("""
            INSERT INTO sys_oper_log(user_id, action, object_id, success, changes, trace_id,actor_dept_id,actor_username)
            VALUES(#{userId}, #{action}, #{objectId}, #{success}, #{changes}, #{traceId},#{departmentId},#{username})
            """)
    int insert(@Param("userId") Long userId, @Param("action") String action, @Param("objectId") String objectId,
               @Param("success") boolean success, @Param("changes") String changes, @Param("traceId") String traceId,@Param("departmentId") Long departmentId,@Param("username") String username);
}
