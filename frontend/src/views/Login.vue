<template>
  <div class="login">
    <div class="hero">
      <el-icon :size="40"><Stamp /></el-icon>
      <h1>{{ t("app.title") }}</h1>
      <p>{{ t("login.lead") }}</p>
      <ul>
        <li>{{ t("login.point1") }}</li>
        <li>{{ t("login.point2") }}</li>
        <li>{{ t("login.point3") }}</li>
      </ul>
    </div>
    <div class="panel">
      <div class="top">
        <h2>{{ t("login.title") }}</h2>
        <el-select :model-value="locale" size="small" class="lang" @change="onLocale">
          <el-option v-for="l in LOCALES" :key="l.value" :label="l.label" :value="l.value" />
        </el-select>
      </div>
      <el-form label-position="top" @submit.prevent="onLogin">
        <el-form-item :label="t('login.empNo')">
          <el-input v-model="empNo" size="large" prefix-icon="User" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('login.password')">
          <el-input v-model="password" size="large" type="password" prefix-icon="Lock" show-password
            autocomplete="current-password" @keyup.enter="onLogin" />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" native-type="submit">
          {{ t("login.submit") }}
        </el-button>
      </el-form>
      <p class="hint">{{ t("login.hint") }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";
import { LOCALES, setLocale, type Locale } from "@/i18n";

const { t, locale } = useI18n();
const router = useRouter();
const route = useRoute();
const store = useAuthStore();
const empNo = ref("");
const password = ref("");
const loading = ref(false);

function onLocale(v: Locale) {
  setLocale(v);
}

async function onLogin() {
  if (!empNo.value || !password.value) return;
  loading.value = true;
  try {
    await store.login(empNo.value.trim(), password.value);
    if (store.mustChangePwd) {
      router.push("/password");
    } else {
      router.push((route.query.redirect as string) || "/");
    }
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login { min-height: 100%; display: grid; grid-template-columns: 1.1fr 1fr; background: var(--app-canvas); }
.hero { background: linear-gradient(160deg, #0f172a 0%, #1e3a6b 100%); color: #e2e8f0; padding: 12vh 8vw; display: flex; flex-direction: column; gap: 12px; }
.hero h1 { margin: 8px 0 0; font-size: 28px; color: #fff; }
.hero p { color: #cbd5e1; margin: 0; line-height: 1.7; max-width: 520px; }
.hero ul { margin: 16px 0 0; padding-left: 18px; color: #cbd5e1; line-height: 2; }
.panel { align-self: center; justify-self: center; width: min(420px, 90%); background: var(--app-surface);
  border: 1px solid var(--app-line); border-radius: 12px; padding: 28px 28px 20px; }
.top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.top h2 { margin: 0; font-size: 20px; }
.lang { width: 118px; }
.submit { width: 100%; margin-top: 6px; }
.hint { color: var(--app-muted); font-size: 12px; margin: 16px 0 0; line-height: 1.6; }
@media (max-width: 900px) { .login { grid-template-columns: 1fr; } .hero { display: none; } }
</style>
