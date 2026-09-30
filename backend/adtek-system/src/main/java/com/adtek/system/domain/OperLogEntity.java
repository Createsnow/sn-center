package com.adtek.system.domain;
import com.baomidou.mybatisplus.annotation.*;
/** 只读日志实体。actorDeptId 是写入时部门快照；历史缺失保持空，绝不由当前部门反推。 */
@TableName("sys_oper_log")
public class OperLogEntity {
    @TableId(type=IdType.AUTO) private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    private Long userId;
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId=value; }
    private Long actorDeptId;
    public Long getActorDeptId() { return actorDeptId; }
    public void setActorDeptId(Long value) { actorDeptId=value; }
    private String actorUsername;
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { actorUsername=value; }
    private String moduleName;
    public String getModuleName() { return moduleName; }
    public void setModuleName(String value) { moduleName=value; }
    private String businessType;
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String value) { businessType=value; }
    private String action;
    public String getAction() { return action; }
    public void setAction(String value) { action=value; }
    private String objectId;
    public String getObjectId() { return objectId; }
    public void setObjectId(String value) { objectId=value; }
    private Boolean success;
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean value) { success=value; }
    private String traceId;
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId=value; }
    private java.time.Instant createTime;
    public java.time.Instant getCreateTime() { return createTime; }
    public void setCreateTime(java.time.Instant value) { createTime=value; }
    private String requestMethod;
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String value) { requestMethod=value; }
    private String requestRoute;
    public String getRequestRoute() { return requestRoute; }
    public void setRequestRoute(String value) { requestRoute=value; }
    private Integer httpStatus;
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer value) { httpStatus=value; }
    private Integer errorCode;
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode=value; }
    private String changes;
    public String getChanges() { return changes; }
    public void setChanges(String value) { changes=value; }
}
