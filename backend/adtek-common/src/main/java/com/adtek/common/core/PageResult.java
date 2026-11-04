package com.adtek.common.core;

import java.util.List;

/** total 使用十进制文本；page/size 为受限 UI 分页参数，不表示业务数量。 */
public record PageResult<T>(List<T> items, String total, int page, int size) {
    public PageResult {
        items = List.copyOf(items);
    }
}
