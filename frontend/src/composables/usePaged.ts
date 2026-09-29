import { reactive, ref } from "vue";
import type { PageResult } from "@/types/api";

/** 通用分页列表：page / page_size / total 与加载状态。 */
export function usePaged<T>(fetch: (q: { page: number; page_size: number }) => Promise<PageResult<T>>, size = 20) {
  const items = ref<T[]>([]) as { value: T[] };
  const state = reactive({ page: 1, pageSize: size, total: 0, capped: false, loading: false });

  async function load(page = state.page) {
    state.loading = true;
    try {
      const r = await fetch({ page, page_size: state.pageSize });
      items.value = r.items;
      state.total = r.total;
      state.capped = !!r.total_capped;
      state.page = page;
    } finally {
      state.loading = false;
    }
  }

  function onSize(s: number) {
    state.pageSize = s;
    load(1);
  }

  return { items, state, load, onSize };
}
