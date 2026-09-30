package com.adtek.sn.service;
import com.adtek.sn.service.port.ErpProductionOrderPort.*;
import com.adtek.sn.vo.*;
import java.util.List;
/** 已完整读取ERP后的事务入口；仅内部调用，不直接接收HTTP组织报文。 */
public interface FactoryPublishService {
 /** 当前factory:sync全厂授权，发布锁保护目录与映射；冲突逐组织跳过并返回，失败整笔回滚。 */
 FactorySyncVO publish(Source source,List<Organization> organizations);
 /** 当前factory:map全厂授权，锁定工厂版本，显式绑定不可变组织身份；与快照映射/审计原子提交。 */
 FactoryVO bind(Source source,Organization organization,long factoryId,long version);
 /** 必须已有当前order:sync事务且锁安全/发布行；另复核factory:sync资格，复用目录更新但不另开事务。 */
 FactorySyncVO withinOrderPublish(Source source,List<Organization> organizations,long revision);
}
