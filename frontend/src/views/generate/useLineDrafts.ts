import { reactive } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import type { OrderLine } from "@/types/api";

/** 中文（含中文标点、全角字符），与后端 encoding.has_chinese 一致。 */
const CHINESE = /[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]/;
export const hasChinese = (v: string | null | undefined) => !!v && CHINESE.test(v);

/** 金蝶缺失的附件项次（必填）、米数（选填，留空用默认 000；金蝶米数含中文时必须改成不含中文的米数）、
 *  产品单位（金蝶为空或含中文（条、个等）时，规则带单位须手动填写不含中文的单位，如 M、m、L）在表格里直接填写；
 *  物料行有号后置灰：已生成未分配的撤销生成后可改，已分配的不能再改。失焦或回车保存，保存后刷新详情。 */
export function useLineDrafts(reload: () => Promise<unknown>) {
  const { t } = useI18n();
  const DEFAULT_LENGTH = "000";
  type DraftField = "attach" | "length" | "unit";
  const drafts = reactive<Record<string, string>>({});
  const draftKey = (row: OrderLine, f: DraftField) => `${f}:${row.bill_no}/${row.seq}`;
  function draftOf(row: OrderLine, f: DraftField): string {
    const k = draftKey(row, f);
    if (k in drafts) return drafts[k];
    if (f === "attach") return row.attach_no;
    if (f === "unit") return row.unit_source === "MANUAL" ? row.unit : "";
    return row.length_source === "MANUAL" ? row.length : "";
  }
  function setDraft(row: OrderLine, f: DraftField, v: string) {
    drafts[draftKey(row, f)] = v;
  }
  /** 金蝶米数含中文、页面还没改过 */
  const lengthMissing = (row: OrderLine) => row.length_source === "K3_CHINESE" && !draftOf(row, "length");
  const lengthTip = (row: OrderLine) =>
    row.length_source === "K3_CHINESE" || hasChinese(row.length_raw)
      ? t("generate.lengthChineseTip", { k3: row.length_raw || row.length })
      : t("generate.lengthDefaultTip");
  const lengthPlaceholder = (row: OrderLine) => (row.length_source === "K3_CHINESE" ? row.length : DEFAULT_LENGTH);
  /** 规则带单位、产品单位金蝶为空或含中文、页面还没填 */
  const unitMissing = (row: OrderLine) =>
    !!row.rule?.with_unit && (row.unit_source === "EMPTY" || row.unit_source === "K3_CHINESE") && !draftOf(row, "unit");
  const unitTip = (row: OrderLine) =>
    row.unit_raw ? t("generate.unitChineseTip", { k3: row.unit_raw }) : t("generate.unitEmptyTip");
  const unitPlaceholder = (row: OrderLine) => row.unit_raw || (row.rule?.with_unit ? t("generate.attachRequired") : "");
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
    if (hasChinese(value)) {
      ElMessage.warning(t("generate.lengthChinese"));
      return;
    }
    return saveDraft(row, "length", (v) => api.fillLength(row.bill_no, row.seq, v));
  }

  function saveUnit(row: OrderLine) {
    const value = (drafts[draftKey(row, "unit")] ?? "").trim();
    if (value === (row.unit_source === "MANUAL" ? row.unit : "")) {
      delete drafts[draftKey(row, "unit")];
      return;
    }
    if (hasChinese(value)) {
      ElMessage.warning(t("generate.unitChinese"));
      return;
    }
    return saveDraft(row, "unit", (v) => api.fillUnit(row.bill_no, row.seq, v));
  }

  return {
    draftOf, setDraft, lockText, saveAttach, saveLength, saveUnit,
    lengthTip, lengthMissing, lengthPlaceholder, unitTip, unitMissing, unitPlaceholder,
  };
}
