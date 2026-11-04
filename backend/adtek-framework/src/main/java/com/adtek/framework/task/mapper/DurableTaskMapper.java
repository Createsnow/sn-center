package com.adtek.framework.task.mapper;

import com.adtek.framework.task.domain.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** 仅供持久任务设施；外部访问须先按所有者和业务处理器授权，执行写须持控制行并匹配代次。 */
@Mapper
public interface DurableTaskMapper {
    Integer lockSubmission();

    Instant now();

    OperationEntity operationTyped(
            @Param("user") long user,
            @Param("action") String action,
            @Param("operation") String operation,
            @Param("principalType") String principalType);

    default OperationEntity operation(long user, String action, String operation) {
        return operationTyped(user, action, operation, "USER");
    }

    OperationEntity operationForTask(@Param("id") String id);

    TaskEntity find(@Param("id") String id);

    TaskEntity lock(@Param("id") String id);

    /** 租约过期仍须拿到旧事务持有的控制行；SKIP LOCKED不会越锁接管。 */
    TaskEntity candidate();

    long activeCount();

    long userActiveCount(@Param("user") long user);

    int insertOperationTyped(
            @Param("id") String id,
            @Param("user") long user,
            @Param("action") String action,
            @Param("operation") String operation,
            @Param("digest") String digest,
            @Param("scope") String scope,
            @Param("principalType") String principalType);

    default int insertOperation(
            String id, long user, String action, String operation, String digest, String scope) {
        return insertOperationTyped(id, user, action, operation, digest, scope, "USER");
    }

    int insertTask(
            @Param("id") String id,
            @Param("user") long user,
            @Param("type") String type,
            @Param("digest") String digest,
            @Param("input") String input,
            @Param("version") long version,
            @Param("attempts") int attempts,
            @Param("budget") int budget);

    int claim(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("owner") String owner,
            @Param("seconds") int seconds);

    int succeed(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("owner") String owner,
            @Param("result") String result);

    int operationSucceeded(@Param("id") String id, @Param("result") String result);

    int fail(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("state") String state,
            @Param("code") int code,
            @Param("delay") int delay);

    int operationEnded(
            @Param("id") String id, @Param("state") String state, @Param("code") Integer code);

    int cancel(@Param("id") String id);

    /** 文件恢复保留原操作终态/结果、同一任务/文件标识；新恢复周期有新的有界预算。 */
    int recover(@Param("id") String id, @Param("budget") int budget);

    ProgressEntity progress(@Param("id") String id);

    /** 不触碰任务行；仅同代或新代可覆盖，旧代的迟到进度不能覆盖接管者。 */
    int progressWrite(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("processed") long processed,
            @Param("phase") String phase);
}
