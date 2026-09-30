package com.adtek.framework.task.convert;

import com.adtek.framework.task.domain.*;
import com.adtek.framework.task.vo.TaskVO;

import org.mapstruct.*;

/** 只发布状态及结果引用，不暴露工作器owner、存储路径、输入快照或范围摘要。 */
@Mapper(componentModel = "spring")
public interface TaskConvert {
    @Mapping(target = "taskId", source = "task.id")
    @Mapping(target = "operationId", source = "operation.operationId")
    @Mapping(target = "operationState", source = "operation.state")
    @Mapping(target = "state", source = "task.state")
    @Mapping(target = "attempt", source = "task.attempt")
    @Mapping(target = "executionEpoch", source = "task.executionEpoch")
    @Mapping(
            target = "processed",
            expression =
                    "java(progress == null || progress.executionEpoch() != task.executionEpoch() ?"
                            + " \"0\" : Long.toString(progress.processed()))")
    @Mapping(
            target = "phase",
            expression =
                    "java(progress == null || progress.executionEpoch() != task.executionEpoch() ?"
                            + " null : progress.phase())")
    @Mapping(target = "fileState", source = "file.state")
    @Mapping(target = "fileId", source = "file.id")
    @Mapping(target = "byteSize", source = "file.byteSize")
    @Mapping(target = "rowCount", source = "file.rowCount")
    @Mapping(target = "errorCode", source = "task.errorCode")
    @Mapping(target = "resultRef", source = "operation.resultRef")
    @Mapping(target = "createdAt", source = "task.createdAt")
    TaskVO view(
            TaskEntity task, OperationEntity operation, FileEntity file, ProgressEntity progress);
}
