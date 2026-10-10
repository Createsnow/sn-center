package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 稳定分录和历史额度；material空串为真实空物料，不合并同物料多行。
 *
 * @param ordinal 当前快照显示顺序，不能作为来源身份
 * @param qty ERP精确非负计划数量文本，可含最多4位小数
 * @param quota 稳定来源行取整计划量减其历史已生成量，下限为0；仅供追溯，不代表整单生成可用量
 */
@Schema(
        requiredProperties = {
            "id",
            "sourceEntryId",
            "ordinal",
            "material",
            "materialName",
            "qty",
            "status",
            "demandBillNo",
            "generatedQty",
            "allocatedQty",
            "quota",
            "snapshotVersion"
        })
public record OrderLineVO(
        String id,
        String sourceEntryId,
        String ordinal,
        String material,
        String materialName,
        String qty,
        String status,
        String demandBillNo,
        String generatedQty,
        String allocatedQty,
        String quota,
        String snapshotVersion) {}
