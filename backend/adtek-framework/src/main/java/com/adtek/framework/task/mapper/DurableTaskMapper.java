package com.adtek.framework.task.mapper;
import com.adtek.framework.task.domain.*;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
/** 仅供持久任务设施；外部访问须先按所有者和业务处理器授权，执行写须持控制行并匹配代次。 */
@Mapper
public interface DurableTaskMapper {
    String TASK="SELECT id,user_id,task_type,input_digest,CAST(input_json AS CHAR) AS input_json,authorization_version,state,owner,execution_epoch,lease_until,attempt,max_attempts,deadline,available_at,result_ref,error_code,created_at,updated_at FROM sys_task ";
    String OP="SELECT id,principal_id,action,operation_id,normalization_version,input_digest,scope_digest,state,task_id,result_ref,error_code FROM sys_operation ";
    @Select("SELECT id FROM sys_operation_control WHERE id=1 FOR UPDATE") Integer lockSubmission();
    @Select("SELECT CURRENT_TIMESTAMP(6)") Instant now();
    @Select(OP+"WHERE principal_type=#{principalType} AND principal_id=#{user} AND action=#{action} AND operation_id=#{operation}")
    OperationEntity operationTyped(@Param("user") long user,@Param("action") String action,@Param("operation") String operation,@Param("principalType") String principalType);
    default OperationEntity operation(long user,String action,String operation) { return operationTyped(user,action,operation,"USER"); }
    @Select(OP+"WHERE task_id=#{id}") OperationEntity operationForTask(String id);
    @Select(TASK+"WHERE id=#{id}") TaskEntity find(String id);
    @Select(TASK+"WHERE id=#{id} FOR UPDATE") TaskEntity lock(String id);
    /** 租约过期仍须拿到旧事务持有的控制行；SKIP LOCKED不会越锁接管。 */
    @Select(TASK+"WHERE (state IN ('QUEUED','RETRY_WAIT') AND available_at<=CURRENT_TIMESTAMP(6)) OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    TaskEntity candidate();
    @Select("SELECT COUNT(*) FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')") long activeCount();
    @Select("SELECT COUNT(*) FROM sys_task WHERE user_id=#{user} AND state IN ('QUEUED','RUNNING','RETRY_WAIT')") long userActiveCount(long user);
    @Insert("INSERT INTO sys_operation(id,principal_type,principal_id,action,operation_id,normalization_version,input_digest,scope_digest,state,task_id) VALUES(#{id},#{principalType},#{user},#{action},#{operation},1,#{digest},#{scope},'PENDING',#{id})")
    int insertOperationTyped(@Param("id") String id,@Param("user") long user,@Param("action") String action,@Param("operation") String operation,@Param("digest") String digest,@Param("scope") String scope,@Param("principalType") String principalType);
    default int insertOperation(String id,long user,String action,String operation,String digest,String scope) { return insertOperationTyped(id,user,action,operation,digest,scope,"USER"); }
    @Insert("INSERT INTO sys_task(id,user_id,task_type,input_digest,input_json,authorization_version,state,max_attempts,deadline) VALUES(#{id},#{user},#{type},#{digest},#{input},#{version},'QUEUED',#{attempts},TIMESTAMPADD(SECOND,#{budget},CURRENT_TIMESTAMP(6)))")
    int insertTask(@Param("id") String id,@Param("user") long user,@Param("type") String type,@Param("digest") String digest,@Param("input") String input,@Param("version") long version,@Param("attempts") int attempts,@Param("budget") int budget);
    @Update("UPDATE sys_task SET state='RUNNING',owner=#{owner},execution_epoch=execution_epoch+1,attempt=attempt+1,lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND execution_epoch=#{epoch}")
    int claim(@Param("id") String id,@Param("epoch") long epoch,@Param("owner") String owner,@Param("seconds") int seconds);
    @Update("UPDATE sys_task SET state='SUCCESS',result_ref=#{result},error_code=NULL,owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND state='RUNNING' AND execution_epoch=#{epoch} AND owner=#{owner}")
    int succeed(@Param("id") String id,@Param("epoch") long epoch,@Param("owner") String owner,@Param("result") String result);
    @Update("UPDATE sys_operation SET state='SUCCEEDED',result_ref=#{result},error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE task_id=#{id} AND state='PENDING'")
    int operationSucceeded(@Param("id") String id,@Param("result") String result);
    @Update("UPDATE sys_task SET state=#{state},error_code=#{code},owner=NULL,lease_until=NULL,available_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND execution_epoch=#{epoch}")
    int fail(@Param("id") String id,@Param("epoch") long epoch,@Param("state") String state,@Param("code") int code,@Param("delay") int delay);
    @Update("UPDATE sys_operation SET state=#{state},error_code=#{code},updated_at=CURRENT_TIMESTAMP(6) WHERE task_id=#{id} AND state='PENDING'")
    int operationEnded(@Param("id") String id,@Param("state") String state,@Param("code") Integer code);
    @Update("UPDATE sys_task SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND state='QUEUED' AND attempt=0") int cancel(String id);
    /** 文件恢复保留原操作终态/结果、同一任务/文件标识；新恢复周期有新的有界预算。 */
    @Update("UPDATE sys_task SET state='QUEUED',attempt=0,error_code=NULL,owner=NULL,lease_until=NULL,available_at=CURRENT_TIMESTAMP(6),deadline=TIMESTAMPADD(SECOND,#{budget},CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND state IN ('SUCCESS','FAILED')")
    int recover(@Param("id") String id,@Param("budget") int budget);
    @Select("SELECT task_id,execution_epoch,processed,phase FROM sys_task_progress WHERE task_id=#{id}") ProgressEntity progress(String id);
    /** 不触碰任务行；仅同代或新代可覆盖，旧代的迟到进度不能覆盖接管者。 */
    @Insert("INSERT INTO sys_task_progress(task_id,execution_epoch,processed,phase) VALUES(#{id},#{epoch},#{processed},#{phase}) ON DUPLICATE KEY UPDATE processed=IF(execution_epoch<=#{epoch},#{processed},processed),phase=IF(execution_epoch<=#{epoch},#{phase},phase),execution_epoch=GREATEST(execution_epoch,#{epoch}),updated_at=CURRENT_TIMESTAMP(6)")
    int progressWrite(@Param("id") String id,@Param("epoch") long epoch,@Param("processed") long processed,@Param("phase") String phase);
}
