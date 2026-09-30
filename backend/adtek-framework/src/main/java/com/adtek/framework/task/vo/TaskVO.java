package com.adtek.framework.task.vo;
import com.adtek.framework.task.*;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
/** 操作、执行和文件状态分别展示；processed是本代进度而非已生效业务数量。
 * @param taskId 服务器UUID
 * @param operationId 调用方稳定意图UUID
 * @param operationState 操作最终状态；文件恢复不撤销已成功的操作
 * @param state 当前执行周期状态，与操作终态分别判断
 * @param attempt 当前周期已领取次数的精确十进制字符串
 * @param executionEpoch 领取代次的精确字符串，恢复仍单调递增
 * @param processed 本代已处理行数的精确字符串；未记录或旧代进度输出0
 * @param phase 本代阶段，可空，不包含异常或业务原文
 * @param fileState 当前产物状态；尚无文件记录时为空
 * @param fileId 服务器文件UUID，可空，恢复保持原标识
 * @param rowCount 固定快照行数的精确字符串，尚无文件记录时为空
 * @param errorCode 当前任务受控失败码，可空，不含异常原文
 * @param resultRef 不可变完成引用，可空
 * @param byteSize READY后字节数的精确字符串，可空
 * @param createdAt 原任务创建时刻，以UTC输出，不随文件恢复重置
 */
@Schema(requiredProperties={"taskId","operationId","operationState","state","attempt","executionEpoch","processed","phase","fileState","fileId","byteSize","rowCount","errorCode","resultRef","createdAt"})
public record TaskVO(String taskId,String operationId,OperationState operationState,TaskState state,
 String attempt,String executionEpoch,String processed,@Schema(nullable=true) String phase,
 @Schema(nullable=true) FileState fileState,@Schema(nullable=true) String fileId,
 @Schema(nullable=true) String byteSize,@Schema(nullable=true) String rowCount,
 @Schema(nullable=true) Integer errorCode,@Schema(nullable=true) String resultRef,Instant createdAt) {}
