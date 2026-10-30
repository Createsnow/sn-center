import { onMounted, onUnmounted, ref, type Ref } from "vue";

/** 表格固定行高（与 .fit-table 的 CSS 一致）：每页条数按容器高度能放下的行数算，表格不出滚动条。 */
export const FIT_ROW_H = 36;

/** 观察容器高度，返回放得下的数据行数（扣掉一行表头），至少 min 行；高度变化后调用 onChange。 */
export function useFitRows(box: Ref<HTMLElement | null>, onChange?: (rows: number) => void, min = 3) {
  const rows = ref(0);
  let ro: ResizeObserver | undefined;

  function measure() {
    const h = box.value?.clientHeight ?? 0;
    const n = Math.max(min, Math.floor(h / FIT_ROW_H) - 1);
    if (h && n !== rows.value) {
      rows.value = n;
      onChange?.(n);
    }
  }

  onMounted(() => {
    ro = new ResizeObserver(measure);
    if (box.value) ro.observe(box.value);
    measure();
  });
  onUnmounted(() => ro?.disconnect());

  return { rows, measure };
}
