package com.adtek.sn.query;

import jakarta.validation.constraints.*;

/** 领取详情按当前成员分页；页号和页大小在服务入口校验。 */
public record AcquireItemsQuery(@Min(1) int page, @Min(1) @Max(100) int size) {}
