package com.adtek.sn.service;
import com.adtek.sn.dto.LegacyMasterCommandDTO;
import com.adtek.sn.vo.LegacyInventoryResultVO;
/**
 * 离线IMPORT库存阶段，最多25000枚及25000个PI；命令沿用完整STAGED和MASTER_STAGED运行号。
 * 默认dry-run；显式apply将SN/双护栏/水位/ID映射/证明整事务提交，失败全部回滚；无后台重试和部分成功。
 * 仅支持无批次/打印/在途关联的PRINTED IMPORT；其他来源需后续证据转换，不能以清空指针通过。
 * 重放核对全部原始暂存、停用身份和目标字段，不启用账号，不代表历史迁移完成。
 */
public interface LegacyInventoryService {LegacyInventoryResultVO promote(LegacyMasterCommandDTO command);}
