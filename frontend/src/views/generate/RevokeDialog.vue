<template>
  <el-dialog v-model="open" :title="t('generate.revokeTitle')" width="680px">
    <p>{{ t("generate.revokeConfirm", { m: segs.length, n: num(qty) }) }}<span v-if="extra">{{ t("generate.revokeExtra", { n: extra }) }}</span></p>
    <el-table :data="segs" size="small" max-height="280">
      <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="120" />
      <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
      <el-table-column :label="t('common.qty')" align="right" width="80">
        <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('generate.segment')" min-width="230">
        <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
      </el-table-column>
      <el-table-column width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="isPicked(row) ? 'primary' : 'warning'" disable-transitions>
            {{ isPicked(row) ? t("generate.revokePicked") : t("generate.revokeWith") }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-input v-model="reason" type="textarea" :rows="2" maxlength="500" show-word-limit class="mt" :placeholder="t('common.reasonPlaceholder')" />
    <template #footer>
      <el-button @click="open = false">{{ t("common.cancel") }}</el-button>
      <el-button type="danger" :disabled="!reason.trim()" :loading="busy" @click="submit">{{ t("generate.revokeBtn", { n: num(qty) }) }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 撤销生成：从流水最末往前，撤到所选物料行为止（流水在其后的行一并撤），一个事务内完成；原因必填。
 *  父组件用 ref 调 open(要撤的号段, 所选物料行)，完成后收到 done。 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { num } from "./format";

type LineRef = { bill_no: string; seq: number };
type Segment = LineRef & { qty: number; start_sn: string; end_sn: string };

const props = defineProps<{ pi: string }>();
const emit = defineEmits<{ done: [] }>();

const { t } = useI18n();
const open = ref(false);
const busy = ref(false);
const segs = ref<Segment[]>([]);
const picked = ref<LineRef[]>([]);
const reason = ref("");
const qty = computed(() => segs.value.reduce((a, s) => a + s.qty, 0));
const isPicked = (s: LineRef) => picked.value.some((p) => p.bill_no === s.bill_no && p.seq === s.seq);
const extra = computed(() => segs.value.filter((s) => !isPicked(s)).length);

function show(range: Segment[], rows: LineRef[]) {
  segs.value = range;
  picked.value = rows.map((x) => ({ bill_no: x.bill_no, seq: x.seq }));
  reason.value = "";
  open.value = true;
}

async function submit() {
  busy.value = true;
  try {
    const r = await api.revokeLines(props.pi, picked.value, reason.value.trim());
    ElMessage.success(t("generate.revokedN", { n: num(r.qty) }));
    open.value = false;
    emit("done");
  } finally {
    busy.value = false;
  }
}

defineExpose({ open: show });
</script>

<style scoped>
.mt { margin-top: 10px; }
</style>
