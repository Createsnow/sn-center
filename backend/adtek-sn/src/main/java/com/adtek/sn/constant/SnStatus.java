package com.adtek.sn.constant;
/** SN业务状态与持久任务/操作状态分离；只有专用命令能够推进状态。 */
public enum SnStatus { PENDING_ALLOC, TO_ACQUIRE, TO_PRINT, PRINTED, APPLYING, TRANSFERRED_OUT }
