import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const load = (f) => JSON.parse(readFileSync(join(here, "locales", f), "utf8"));
const flat = (o, p = "", out = {}) => {
  for (const [k, v] of Object.entries(o)) {
    if (v && typeof v === "object") flat(v, `${p}${k}.`, out);
    else out[`${p}${k}`] = v;
  }
  return out;
};
const langs = ["zh-CN", "en", "vi"];
const ui = Object.fromEntries(langs.map((l) => [l, flat(load(`${l}.json`))]));

function sources(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return f === "locales" ? [] : sources(p);
    return /\.(vue|ts)$/.test(f) ? [p] : [];
  });
}

test("all languages have identical UI keys", () => {
  for (const l of ["en", "vi"]) assert.deepEqual(Object.keys(ui[l]).sort(), Object.keys(ui["zh-CN"]).sort(), l);
});

test("every static t('key') used in source exists", () => {
  const missing = [];
  for (const file of sources(here)) {
    const text = readFileSync(file, "utf8");
    for (const m of text.matchAll(/\bt\(\s*["'`]([A-Za-z0-9_.]+)["'`]/g)) {
      if (!(m[1] in ui["zh-CN"])) missing.push(`${file.replace(here, "")}: ${m[1]}`);
    }
  }
  assert.deepEqual(missing, []);
});

test("every message compiles in vue-i18n", async () => {
  // @ | 等是 vue-i18n 语法字符，原样出现会让 t() 抛错（如密码规则里的 @ 让表单项渲染失败），须由 gen_locales 转义
  const { createI18n } = await import("vue-i18n");
  const broken = [];
  for (const f of langs.flatMap((l) => [`${l}.json`, `errors.${l}.json`])) {
    const msgs = load(f);
    const { t } = createI18n({ legacy: false, locale: "x", messages: { x: msgs }, missingWarn: false }).global;
    for (const k of Object.keys(flat(msgs))) {
      try {
        t(k, {});
      } catch (e) {
        broken.push(`${f} ${k}: ${e.message.split("\n")[0]}`);
      }
    }
  }
  assert.deepEqual(broken, []);
});

test("error dictionaries share keys", () => {
  const zh = Object.keys(load("errors.zh-CN.json")).sort();
  for (const l of ["en", "vi"]) assert.deepEqual(Object.keys(load(`errors.${l}.json`)).sort(), zh);
});
