<template>
  <div class="banner run" :class="{ cx: !!running.cancel_at }">
    <div class="prog-h">
      <b class="pct num">{{ percent }}%</b>
      <span class="num">{{ t("generate.runCount", { done: num(running.done_qty), qty: num(running.qty) }) }}</span>
      <span class="muted">{{ t("generate.writtenTo") }} <span class="sn-mono">{{ running.current_sn || "—" }}</span></span>
      <span class="muted">{{ t("generate.speed") }} {{ running.speed_per_sec ? t("generate.speedVal", { n: num(running.speed_per_sec) }) : "—" }}</span>
      <span class="muted">{{ t("generate.eta") }} {{ formatDuration(running.eta_seconds) }}</span>
      <span class="push muted small">{{ running.cancel_at ? t("generate.canceling") : t("generate.runNote") }}</span>
      <el-button size="small" type="danger" plain :disabled="!!running.cancel_at" :loading="canceling" @click="emit('cancel')">{{ t("generate.cancelGen") }}</el-button>
    </div>
    <el-progress :percentage="Number(percent)" :stroke-width="8" :show-text="false" striped striped-flow
      :status="running.cancel_at ? 'warning' : undefined" />
  </div>
</template>

<script setup lang="ts">
/** 后台生成中：进度、已写到的号、速度、预计剩余时间；可取消。 */
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { formatDuration } from "@/utils/time";
import { num, pct } from "./format";

const props = defineProps<{ running: any; canceling: boolean }>();
const emit = defineEmits<{ cancel: [] }>();
const { t } = useI18n();
const percent = computed(() => props.running.percent ?? pct(props.running));
</script>

<style scoped>
.small { font-size: 12px; }
.push { margin-left: auto; }
.banner { display: flex; flex-direction: column; gap: 10px; padding: 8px 14px; border-radius: 8px; font-size: 13px; background: var(--el-color-primary-light-9); }
.banner.cx { background: var(--el-color-warning-light-9); }
.prog-h { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.prog-h .pct { font-size: 20px; color: var(--el-color-primary); }
.banner.cx .pct { color: var(--el-color-warning); }
</style>
