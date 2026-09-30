package com.adtek.sn.service;

import com.adtek.common.core.PageResult;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.vo.*;

/** 工厂页用例，保留手工建档/来源展示/K3同步，增加明确稳定组织映射。 */
public interface FactoryService {
    /** 当前factory:list范围内按编码/ID稳定分页；空资格为空，不返回全目录。 */
    PageResult<FactoryVO> list(SnPageQuery query);

    /** 当前create权限须全部工厂；安全/发布锁内校验编码名称唯一，工厂与审计同事务。 */
    FactoryVO create(FactoryCreateDTO command);

    /** 全部工厂sync权限，ERP完整拉取在事务外；失败不写，发布与审计同事务，冲突不自动认领。 */
    FactorySyncVO synchronize();

    /** 当前map权限须全部工厂；读取当前ERP组织候选完整分页，依赖失败明确返回。 */
    PageResult<OrganizationVO> organizations(SnPageQuery query);

    /** 全部工厂map权限；先取ERP真实组织，事务内复核本地版本，不可把已有组织改派；同步更新未映射快照。 */
    FactoryVO bind(String factoryId, OrganizationMapDTO command);
}
