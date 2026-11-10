package com.adtek.sn.vo;

import java.util.List;

/**
 * 分页结果：{@code total} 为计数（JSON 十进制字符串）；{@code totalCapped} 为 true 时 total 只是下限（大表计数封顶）。
 *
 * @param <T> 记录类型
 */
public class PageVO<T> {
    public List<T> items;
    public long total;
    public int page;
    public int pageSize;
    public boolean totalCapped;

    public static <T> PageVO<T> of(List<T> items, long total, int page, int pageSize) {
        PageVO<T> p = new PageVO<>();
        p.items = items;
        p.total = total;
        p.page = page;
        p.pageSize = pageSize;
        return p;
    }

    public static <T> PageVO<T> capped(
            List<T> items, long total, int page, int pageSize, long cap) {
        PageVO<T> p = of(items, Math.min(total, cap), page, pageSize);
        p.totalCapped = total > cap;
        return p;
    }
}
