<template>
  <el-dialog v-model="open" :title="t('generate.revokeTitle')" width="480px">
    <!-- 不放说明文字；只在流水在后的行要一并撤销时列出行数 -->
    <p v-if="extra" class="extra">{{ t("generate.revokeExtra", { n: extra }) }}</p>
    <template #footer>
      <el-button @click="open = false">{{ t("common.cancel") }}</el-button>
      <el-button type="danger" :loading="busy" @click="submit">{{ t("generate.revoke") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 撤销生成：从流水最末往前，撤到所选物料行为止（流水在其后的行一并撤），一个事务内完成。
 *  弹窗不放说明文字（流水在后的行要一并撤时只列行数），不列号段、不填原因（后端记默认原因）。
 *  父组件用 ref 调 open(要撤的号段, 所选物料行)，完成后收到 done。 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { num } from "./format";

type LineRef = { bill_no: string; seq: number };
type Segment = LineRef & { qty: number };

const props = defineProps<{ pi: string }>();
const emit = defineEmits<{ done: [] }>();

const { t } = useI18n();
const open = ref(false);
const busy = ref(false);
const segs = ref<Segment[]>([]);
const picked = ref<LineRef[]>([]);
const isPicked = (s: LineRef) => picked.value.some((p) => p.bill_no === s.bill_no && p.seq === s.seq);
/** 流水排在所选行之后、须一并撤销的物料行数 */
const extra = computed(() => new Set(segs.value.filter((s) => !isPicked(s)).map((s) => `${s.bill_no}#${s.seq}`)).size);

function show(range: Segment[], rows: LineRef[]) {
  segs.value = range;
  picked.value = rows.map((x) => ({ bill_no: x.bill_no, seq: x.seq }));
  open.value = true;
}

async function submit() {
  busy.value = true;
  try {
    const r = await api.revokeLines(props.pi, picked.value);
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
.extra { margin: 0; color: var(--el-color-warning-dark-2); }
</style>
