<template>
  <el-dialog :model-value="modelValue" :title="t('generate.piRuleBtn')" width="640px" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <p class="muted small">{{ t("generate.ruleNote") }}</p>
    <div class="rbox">
      <b>{{ t("generate.piRule") }}</b>
      <div v-if="ctx.pi_rule" class="rule-now">
        <el-tag size="small" type="primary">{{ t(`generate.source.${ctx.pi_rule.source}`) }}</el-tag>
        <b>{{ ruleTitle(t, ctx.pi_rule) }}</b>
        <span class="sn-mono">{{ ctx.pi_rule.format }}</span>
        <el-tag size="small" :type="ctx.pi_rule.plan === 'PREFIX' ? 'danger' : 'info'">{{ t(`generate.plan.${ctx.pi_rule.plan}`) }}</el-tag>
        <el-button link type="danger" :loading="busy === 'unbindPi'" @click="unbindPi">{{ t("generate.unbindPi") }}</el-button>
      </div>
      <span v-else class="muted">{{ t("generate.noPiRule") }}</span>
      <div class="rule-now">
        <el-select v-model="bind.rule_id" filterable clearable :placeholder="t('generate.pickLibRule')" class="w320" @change="bind.new_prefix = ''">
          <!-- 精简：只列「规则编码 v版本」 -->
          <el-option v-for="o in ctx.rule_options" :key="o.rule_id" :value="o.rule_id" :label="optionTitle(o)" :title="o.format || ''">
            <span>{{ optionTitle(o) }}</span>
            <el-tag v-if="o.recommended" size="small" type="success" class="ml">{{ t("generate.rule.recommended") }}</el-tag>
          </el-option>
        </el-select>
        <el-input v-if="bindOption?.plan === 'PREFIX'" v-model="bind.new_prefix" class="w160" maxlength="32" :placeholder="t('generate.rule.newPrefix')" />
        <el-button type="primary" :disabled="!bind.rule_id || (bindOption?.plan === 'PREFIX' && !bind.new_prefix.trim())"
          :loading="busy === 'bindPi'" @click="bindPi">{{ t("generate.bindPi") }}</el-button>
      </div>
      <span v-if="bindOption?.plan === 'PREFIX'" class="muted warn">{{ t("generate.rule.prefixNeeded", { pi: bindOption.other_pi || "—" }) }}</span>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** PI 规则（F-08）：整张 PI 的默认规则（绑定、解除）；规则按 PI 维度绑定。不常用。 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ruleTitle } from "./format";

const props = defineProps<{ modelValue: boolean; ctx: any }>();
const emit = defineEmits<{ "update:modelValue": [open: boolean]; saved: [] }>();

const { t } = useI18n();
const busy = ref("");
const bind = reactive({ rule_id: undefined as number | undefined, new_prefix: "" });
const optionTitle = (o: any) => (o.version ? `${o.rule_code} v${o.version}` : o.rule_code);
const bindOption = computed(() => props.ctx.rule_options.find((o: any) => o.rule_id === bind.rule_id));

async function run(key: string, action: () => Promise<unknown>, message: string) {
  busy.value = key;
  try {
    await action();
    ElMessage.success(message);
    emit("saved");
  } finally {
    busy.value = "";
  }
}

async function bindPi() {
  const body: Record<string, unknown> = { pi_no: props.ctx.pi_no, rule_id: bind.rule_id };
  if (bindOption.value?.plan === "PREFIX") body.new_prefix = bind.new_prefix.trim();
  await run("bindPi", () => api.bindPiRule(body), t("common.saved"));
  bind.rule_id = undefined;
  bind.new_prefix = "";
}

async function unbindPi() {
  await ElMessageBox.confirm(t("generate.unbindPiConfirm"), t("generate.piRule"), { type: "warning" });
  await run("unbindPi", () => api.unbindPiRule(props.ctx.pi_no), t("common.done"));
}
</script>

<style scoped>
.ml { margin-left: 6px; }
.w160 { width: 160px; }
.w320 { width: 320px; }
.small { font-size: 12px; }
.warn { color: var(--el-color-warning-dark-2); }
.rule-now { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.rbox { display: flex; flex-direction: column; gap: 8px; padding: 12px; margin-bottom: 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
</style>
