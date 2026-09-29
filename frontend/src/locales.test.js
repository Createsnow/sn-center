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

test("error dictionaries share keys", () => {
  const zh = Object.keys(load("errors.zh-CN.json")).sort();
  for (const l of ["en", "vi"]) assert.deepEqual(Object.keys(load(`errors.${l}.json`)).sort(), zh);
});
