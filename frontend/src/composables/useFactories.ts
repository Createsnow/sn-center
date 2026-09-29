import { ref } from "vue";
import { api } from "@/api";
import type { Factory } from "@/types/api";

const list = ref<Factory[]>([]);
let loading: Promise<void> | null = null;

/** 工厂主数据（全局缓存一份）。 */
export function useFactories() {
  function load(force = false) {
    if (!force && (list.value.length || loading)) return loading ?? Promise.resolve();
    loading = api.factories().then((rows: Factory[]) => {
      list.value = rows;
    }).finally(() => {
      loading = null;
    });
    return loading;
  }
  function nameOf(code?: string | null) {
    if (!code) return "";
    return list.value.find((f) => f.factory_code === code)?.factory_name || code;
  }
  load();
  return { factories: list, load, nameOf };
}
