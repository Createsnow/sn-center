package com.adtek.sn.service;

import com.adtek.sn.dto.OrderScheduleDTO;
import com.adtek.sn.vo.OrderScheduleVO;

/** 定时全量同步；业务最终状态和发布栅栏由MySQL持久化，Redis不决定执行权。 */
public interface OrderScheduleService {
    /** 只有order:list全部工厂范围可读全局调度信息。 */
    OrderScheduleVO status();

    /**
     * 当前order:sync全部工厂授权才能修改；事务内复核，责任主体取当前用户。 开关/间隔/责任主体变化使在途运行失效并记录失败；完全相同的保存不延后下次时间。
     * 设置和成功审计同事务，失败全部回滚，不自动发起立即同步。
     */
    OrderScheduleVO save(OrderScheduleDTO command);

    /**
     * 内部工作器扫描入口；到期时短事务抢占一个运行号，最多一个执行者拉取。 当前责任主体须仍有全局同步权限；外部完整拉取后带原号发布，过期或设置变化拒绝。
     * 成败持久记录；进程中断超过五分钟后登记失败，下一到期周期重新读取，不重放旧快照。
     */
    boolean runDue();
}
