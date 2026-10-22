import { reactive } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import type { OrderLine } from "@/types/api";

/** 金蝶缺失的附件项次（必填）、米标（选填，留空用默认 000）在表格里直接填写；物料行有号后置灰：
 *  已生成未分配的撤销生成后可改，已分配的不能再改。失焦或回车保存，保存后刷新详情。 */
export function useLineDrafts(reload: () => Promise<unknown>) {
  const { t } = useI18n();
  const DEFAULT_LENGTH = "000";
  type DraftField = "attach" | "length";
  const drafts = reactive<Record<string, string>>({});
  const draftKey = (row: OrderLine, f: DraftField) => `${f}:${row.bill_no}/${row.seq}`;
  function draftOf(row: OrderLine, f: DraftField): string {
    const k = draftKey(row, f);
    if (k in drafts) return drafts[k];
    return f === "attach" ? row.attach_no : row.length_source === "MANUAL" ? row.length : "";
  }
  function setDraft(row: OrderLine, f: DraftField, v: string) {
    drafts[draftKey(row, f)] = v;
  }
  function lockText(row: OrderLine) {
    return row.edit_lock === "ALLOCATED" ? t("generate.lockAllocated") : t("generate.lockGenerated");
  }
  async function saveDraft(row: OrderLine, f: DraftField, save: (value: string) => Promise<unknown>) {
    const k = draftKey(row, f);
    try {
      await save(drafts[k].trim());
      ElMessage.success(t("common.saved"));
      await reload();
    } finally {
      delete drafts[k];
    }
  }
  function saveAttach(row: OrderLine) {
    const value = (drafts[draftKey(row, "attach")] ?? "").trim();
    if (!value || value === row.attach_no) {
      if (!value) ElMessage.warning(t("orders.attachInvalid"));
      delete drafts[draftKey(row, "attach")];
      return;
    }
    return saveDraft(row, "attach", (v) => api.fillAttach(row.bill_no, row.seq, v));
  }
  function saveLength(row: OrderLine) {
    const value = (drafts[draftKey(row, "length")] ?? "").trim();
    if (value === (row.length_source === "MANUAL" ? row.length : "")) {
      delete drafts[draftKey(row, "length")];
      return;
    }
    return saveDraft(row, "length", (v) => api.fillLength(row.bill_no, row.seq, v));
  }

  return { DEFAULT_LENGTH, draftOf, setDraft, lockText, saveAttach, saveLength };
}
