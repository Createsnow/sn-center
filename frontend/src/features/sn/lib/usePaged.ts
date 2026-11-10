import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { QueryParams } from '../../../api/client';
import type { Page } from '../../../api/sn';

/**
 * 分页列表（TanStack Query）：params 为「已提交」的查询条件（点查询时 submit），页码、每页条数变化自动重查。
 * 翻页时保留上一页数据，避免表格闪空。
 */
export function usePaged(
  key: string,
  fetch: (q: QueryParams) => Promise<Page>,
  initial: QueryParams = {},
  size = 20,
  enabled = true,
) {
  const [params, setParams] = useState<QueryParams>(initial);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(size);
  const [seq, setSeq] = useState(0);
  const query = useQuery({
    queryKey: ['sn', key, params, page, pageSize, seq],
    queryFn: () => fetch({ ...params, page, pageSize }),
    placeholderData: keepPreviousData,
    enabled,
  });
  return {
    items: query.data?.items ?? [],
    data: query.data,
    total: Number(query.data?.total ?? 0),
    capped: !!query.data?.totalCapped,
    loading: query.isFetching,
    page,
    pageSize,
    params,
    /** 按新条件从第 1 页查（条件相同时也重新查询）。 */
    submit(next: QueryParams) {
      setParams(next);
      setPage(1);
      setSeq((s) => s + 1);
    },
    setPage,
    setPageSize(n: number) {
      setPageSize(n);
      setPage(1);
    },
    reload() {
      setSeq((s) => s + 1);
    },
  };
}

/** 去掉条件两端空白。 */
export function trimmed<T extends Record<string, unknown>>(f: T): QueryParams {
  const out: Record<string, string | number | boolean | null | undefined> = {};
  for (const [k, v] of Object.entries(f)) {
    out[k] = typeof v === 'string' ? v.trim() : (v as string | number | boolean | null | undefined);
  }
  return out;
}
