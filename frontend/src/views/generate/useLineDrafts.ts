import { reactive } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import type { OrderLine } from "@/types/api";

/** 中文（含中文标点、全角字符），与后端 encoding.has_chinese 一致。 */
const CHINESE = /[　-〿㐀-䶿一-鿿豈-﫿＀-￯]/;
export const hasChinese = (v: string | null | undefined) => !!v && CHINESE.test(v);

/** 金蝶缺失的附件项次（必填；点选物料行时自动填点选顺序，可改）、米数、产品单位在表格里直接填写：
 *  米数、产品单位用金蝶原值（原样显示、原样使用），金蝶为空时规则带米数 / 单位则必填，其他情况可选改（留空即改回金蝶值）；
 *  物料行有号后置灰：已生成未分配的撤销生成后可改，已分配的不能再改。失焦或回车保存，保存后刷新详情。 */
export function useLineDrafts(reload: () => Promise<unknown>) {
  const { t } = useI18n();
  type DraftField = "attach" | "length" | "unit";
  const drafts = reactive<Record<string, string>>({});
  /** 点选时自动填的附件项次草稿（draftKey → 物料行），连续点选停下 AUTO_SAVE_DELAY 毫秒后一并保存 */
  const autoAttach: Record<string, OrderLine> = {};
  const AUTO_SAVE_DELAY = 1200;
  let autoTimer: ReturnType<typeof setTimeout> | undefined;
  const draftKey = (row: OrderLine, f: DraftField) => `${f}:${row.bill_no}/${row.seq}`;
  function draftOf(row: OrderLine, f: DraftField): string {
    const k = draftKey(row, f);
    if (k in drafts) return drafts[k];
    if (f === "attach") return row.attach_no;
    return f === "unit" ? row.unit : row.length;
  }
  function setDraft(row: OrderLine, f: DraftField, v: string) {
    const k = draftKey(row, f);
    drafts[k] = v;
    // 自动填的附件项次在页面上改过：不再随点选一并保存，由这次失焦 / 回车保存
    delete autoAttach[k];
  }
  /** 金蝶米数为空、页面没填，且规则带米数（还没定规则时也标出） */
  const lengthMissing = (row: OrderLine) =>
    row.length_source === "EMPTY" && row.rule?.with_length !== false && !draftOf(row, "length");
  const lengthTip = (row: OrderLine) =>
    row.length_raw?.trim() ? t("generate.lengthK3Tip", { k3: row.length_raw.trim() }) : t("generate.lengthEmptyTip");
  const lengthPlaceholder = (row: OrderLine) =>
    row.length_raw?.trim() || (row.rule?.with_length !== false ? t("generate.attachRequired") : "");
  /** 金蝶产品单位为空、页面没填，且规则带单位 */
  const unitMissing = (row: OrderLine) => row.unit_source === "EMPTY" && !!row.rule?.with_unit && !draftOf(row, "unit");
  const unitTip = (row: OrderLine) =>
    row.unit_raw ? t("generate.unitK3Tip", { k3: row.unit_raw }) : t("generate.unitEmptyTip");
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
    const k = draftKey(row, "attach");
    delete autoAttach[k];
    const value = (drafts[k] ?? "").trim();
    if (!value || value === row.attach_no) {
      if (!value) ElMessage.warning(t("orders.attachInvalid"));
      delete drafts[k];
      return;
    }
    return saveDraft(row, "attach", (v) => api.fillAttach(row.bill_no, row.seq, v));
  }
  function saveLength(row: OrderLine) {
    const value = (drafts[draftKey(row, "length")] ?? "").trim();
    if (value === row.length) {
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
    if (value === row.unit) {
      delete drafts[draftKey(row, "unit")];
      return;
    }
    if (hasChinese(value)) {
      ElMessage.warning(t("generate.unitChinese"));
      return;
    }
    return saveDraft(row, "unit", (v) => api.fillUnit(row.bill_no, row.seq, v));
  }

  // ---------------- 附件项次自动填：点选附件项次为空、可填写的物料行时填上点选顺序（草稿，可改）；
  // 连续点选停下后一并保存、刷新一次（每点一行就刷新的话，补齐的行会立刻离开待处理页签，打断连续点选）

  const attachEditable = (row: OrderLine) => row.attach_source !== "K3" && !row.edit_lock;

  /** 点选物料行：附件项次为空（且页面没在填）时填上点选顺序 order（从 1 起）。 */
  function autoFillAttach(row: OrderLine, order: number) {
    const k = draftKey(row, "attach");
    if (!attachEditable(row) || row.attach_no || drafts[k]) return;
    drafts[k] = String(order);
    autoAttach[k] = row;
    clearTimeout(autoTimer);
    autoTimer = setTimeout(flushAutoAttach, AUTO_SAVE_DELAY);
  }
  /** 取消点选（不给 row 时为全部，如清空勾选、换 PI）：还没保存、也没改过的自动填值一并撤掉。 */
  function dropAutoAttach(row?: OrderLine) {
    for (const k of row ? [draftKey(row, "attach")] : Object.keys(autoAttach)) {
      if (!(k in autoAttach)) continue;
      delete autoAttach[k];
      delete drafts[k];
    }
  }
  async function flushAutoAttach() {
    clearTimeout(autoTimer);
    const items = Object.entries(autoAttach);
    for (const [k] of items) delete autoAttach[k];
    const todo = items.filter(([k]) => drafts[k]?.trim());
    if (!todo.length) return;
    let saved = 0;
    try {
      for (const [k, row] of todo) {
        await api.fillAttach(row.bill_no, row.seq, drafts[k].trim());
        saved++;
      }
    } finally {
      for (const [k] of todo) delete drafts[k];
      if (saved) ElMessage.success(t("generate.attachAutoSaved", { n: saved }));
      await reload();
    }
  }

  return {
    draftOf, setDraft, lockText, saveAttach, saveLength, saveUnit,
    lengthTip, lengthMissing, lengthPlaceholder, unitTip, unitMissing, unitPlaceholder,
    autoFillAttach, dropAutoAttach, flushAutoAttach,
  };
}
