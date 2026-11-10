package com.adtek.sn.support;

/** 分页参数：页码从 1 开始；每页 1–5000 条（默认 20）。 */
public record PageReq(int page, int size) {
    public static final int MAX_PAGE_SIZE = 5000;

    public static PageReq of(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return new PageReq(p, s);
    }

    public static PageReq of(Integer page, Integer size, int defaultSize) {
        return of(page, size == null ? defaultSize : size);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
