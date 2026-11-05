package com.adtek.common.core;

import java.util.List;

/**
 * 分页结果：{@code total} 为数据库计数，使用十进制字符串以免前端丢失精度；{@code page}、{@code size} 为有界页码与页大小。
 *
 * <p>列表与 total 必须使用相同的权限与数据范围条件。
 *
 * @param records 当前页记录，不可变
 * @param total 符合条件的总数（十进制字符串）
 * @param page 从 1 开始的页码
 * @param size 页大小
 * @param <T> 记录类型
 */
public record PageResult<T>(List<T> records, String total, int page, int size) {
    public PageResult {
        records = List.copyOf(records);
        if (!total.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("total must be a non-negative decimal string");
        }
    }

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        return new PageResult<>(records, Long.toString(total), page, size);
    }
}
