package com.adtek.framework.task.service;

/** 可丢失并重算的阶段进度，调用方不得把它当作已提交业务数量。 */
public interface TaskProgressService {
    /** 独立短事务只写进度表；代次单调，phase仅接受登记阶段，禁止消息/秘密字段。 */
    void record(String id, long epoch, long processed, String phase);
}
