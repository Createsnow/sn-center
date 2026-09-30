package com.adtek.sn.service;

import com.adtek.sn.dto.LegacyMasterCommandDTO;
import com.adtek.sn.vo.LegacyMasterResultVO;

/**
 * 离线主数据阶段，只接受已完整验证的STAGED源包和独立MySQL8.4新目标；不由Web提供入口。
 * 默认dry-run；apply同事务写工厂/规则/停用身份/角色工厂关系与ID映射，封闭bootstrap，任何失败整体回滚。
 * 同运行重放逐字段核对目标，不覆盖用户后续变更。不会导入SN历史、启用账号或宣告全量迁移成功。
 */
public interface LegacyMasterService {
    LegacyMasterResultVO promote(LegacyMasterCommandDTO command);
}
