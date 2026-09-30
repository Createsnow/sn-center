package com.adtek.sn.service;

import com.adtek.sn.dto.LegacyStageCommandDTO;
import com.adtek.sn.vo.LegacyStageResultVO;

/** 离线迁移原始数据层，仅专用运维账号，不装配Web主体/接口。 */
public interface LegacyStageService {
    /**
     * 默认核验快照/目标身份并返回只读计划。apply逐块保存原行与同事务检查点，不更新任何业务表。
     * runId绑定manifest、来源指纹和映射版本，同号异来源拒绝；中断重试逐行核验已提交内容后续传。
     * 同schema连接锁避免并发修改检查点；任何原文件篡改、目标行差异或数据库失败均保留已提交块，
     * 未提交块回滚并标记FAILED。STAGED仍须解决全部历史/身份/文件问题后才能进入独立业务推广门禁。
     */
    LegacyStageResultVO stage(LegacyStageCommandDTO command);
}
