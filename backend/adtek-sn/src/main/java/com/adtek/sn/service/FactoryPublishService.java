package com.adtek.sn.service;

import com.adtek.sn.service.port.ErpProductionOrderPort.*;
import com.adtek.sn.vo.*;

import java.util.List;

/** 已完整读取ERP后的事务入口；仅内部调用，不直接接收HTTP组织报文。 */
public interface FactoryPublishService {
    /** 当前factory:sync全厂授权，发布锁保护目录与映射；冲突逐组织跳过并返回，失败整笔回滚。 */
    FactorySyncVO publish(Source source, List<Organization> organizations);

    /** 当前factory:map全厂授权，锁定工厂版本，显式绑定不可变组织身份；与快照映射/审计原子提交。 */
    FactoryVO bind(Source source, Organization organization, long factoryId, long version);

    /** 全量同步的独立工厂事务；复核当前权限/版本/租约，目录、审计和本次工厂版本一起提交。 */
    FactorySyncVO publishForOrderSync(
            com.adtek.common.core.Actor requested,
            String attemptId,
            Source source,
            List<Organization> organizations);
}
