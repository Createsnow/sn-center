package com.adtek.common.core;

import java.util.List;

/** 大结果使用不透明游标；nextCursor 为 null 表示已读完固定查询集合。 */
public record CursorResult<T>(List<T> items, String nextCursor) {
    public CursorResult { items = List.copyOf(items); }
}
