package com.adtek.sn.service;

import com.adtek.common.core.Actor;
import com.adtek.sn.service.port.ErpProductionOrderPort.*;
import com.adtek.sn.vo.OrderSyncVO;

import java.util.List;

/** 真实ERP拉取与短发布事务分开；输入只来自受控适配器，不能作为开放导入API。 */
public interface OrderPublishService {
    /** 当前全部工厂order:sync，独立事务记录FETCHING及基准发布版本，不占业务额度。 */
    OrderSyncVO begin(String billNo);

    /** 再核验原提交主体版本/当前权限，安全→发布锁；基准未变才发布整个范围。 头/分录/历史额度初始化/成功记录/审计同事务；任一失败回滚，空列表是已验证的合法空快照。 */
    OrderSyncVO publish(
            Actor requested, String attemptId, Source source, List<Row> rows, boolean orgFailed);

    /** 外部或发布失败后独立事务登记受控失败码；已经SUCCESS不覆盖，失败审计不伪造成功。 */
    void failed(String attemptId, int errorCode, String safeMessage);
}
