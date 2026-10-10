package com.adtek.sn.vo;

import java.util.List;

/** 页面总数最多展示十万条；明确标识截断，精确数量仍使用 count 接口。 */
@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {"items", "total", "page", "size", "totalCapped"})
public record SnQueryPageVO(
        List<SnQueryItemVO> items, String total, int page, int size, boolean totalCapped) {}
