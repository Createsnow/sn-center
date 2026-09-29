<template>
  <div class="wrap">
    <div class="panel">
      <h2>{{ t("password.title") }}</h2>
      <el-alert v-if="store.mustChangePwd" type="warning" :closable="false" show-icon :title="t('password.forced')" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="form" @submit.prevent="submit">
        <el-form-item :label="t('password.old')" prop="old">
          <el-input v-model="form.old" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item :label="t('password.new')" prop="next">
          <el-input v-model="form.next" type="password" show-password autocomplete="new-password" />
          <div class="muted">{{ t("password.policy") }}</div>
        </el-form-item>
        <el-form-item :label="t('password.confirm')" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <div class="actions">
          <el-button v-if="!store.mustChangePwd" @click="router.back()">{{ t("common.cancel") }}</el-button>
          <el-button v-else @click="logout">{{ t("app.logout") }}</el-button>
          <el-button type="primary" :loading="loading" native-type="submit">{{ t("common.save") }}</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ old: "", next: "", confirm: "" });
const rules: FormRules = {
  old: [{ required: true, message: () => t("common.required"), trigger: "blur" }],
  next: [
    { required: true, message: () => t("common.required"), trigger: "blur" },
    {
      validator: (_r, v: string, cb) =>
        v && v.length >= 8 && v.length <= 64 && /[A-Za-z]/.test(v) && /\d/.test(v) ? cb() : cb(new Error(t("password.policy"))),
      trigger: "blur",
    },
  ],
  confirm: [
    { validator: (_r, v: string, cb) => (v === form.next ? cb() : cb(new Error(t("password.mismatch")))), trigger: "blur" },
  ],
};

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  loading.value = true;
  try {
    await store.changePassword(form.old, form.next);
    ElMessage.success(t("password.done"));
    router.push("/");
  } finally {
    loading.value = false;
  }
}

function logout() {
  store.clear();
  router.push("/login");
}
</script>

<style scoped>
.wrap { min-height: 100%; display: grid; place-items: center; background: var(--app-canvas); padding: 24px; }
.panel { width: min(440px, 100%); background: var(--app-surface); border: 1px solid var(--app-line); border-radius: 12px; padding: 28px; }
h2 { margin: 0 0 12px; font-size: 20px; }
.form { margin-top: 16px; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
