package com.adtek.sn.service;
import com.adtek.sn.dto.MaintenanceCommandDTO;
import com.adtek.sn.vo.MaintenanceResultVO;
/** 独立运维账号入口，不注入页面应用上下文、不接受Web主体；见docs/14维护契约。 */
public interface SnMaintenanceService {
    /**
     * 校验目标schema白名单后执行封闭维护动作；dry-run只读且不写运行记录。
     * apply使用schema级连接锁，同号同输入重放结果，异常/中断后以相同号核对并重入。
     * 分区DDL逐条自动提交，部分失败不承诺回滚；DML及成功运行记录同事务提交。
     * 归档复制永久保留；ARCHIVE_FINALIZE需完整恢复证据与当前锁定内容一致才移除整月在线副本。
     * 恢复证明摘要绑定运行号，缺失/篡改/源身份变化拒绝；不删除归档、SN或历史成员。
     * 锁/结构/内容/数量不满足时抛出稳定运维错误，调用进程须非零退出并告警。
     */
    MaintenanceResultVO execute(MaintenanceCommandDTO command);
}
