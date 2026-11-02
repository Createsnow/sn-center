import test from "node:test";
import assert from "node:assert/strict";
import { buttonWidth, colWidth, tagWidth, textWidth } from "./colWidth.js";

test("textWidth: empty and nullish are 0; CJK wider than latin; mono adds spacing", () => {
  assert.equal(textWidth(""), 0);
  assert.equal(textWidth(null), 0);
  assert.equal(textWidth(undefined), 0);
  assert.ok(textWidth("生产组织") > textWidth("ABCD"));
  assert.ok(textWidth("ABCD", { mono: true }) > textWidth("ABCD"));
  assert.equal(textWidth(1234), textWidth("1234"));
});

test("colWidth: widest cell wins, header counts, padding added", () => {
  const cells = [textWidth("PI-2026-0001"), textWidth("PI-2026-000000000001")];
  const w = colWidth("PI", cells);
  assert.ok(w >= textWidth("PI-2026-000000000001") + 16, "cell padding included");
  // 表头比内容宽时按表头
  assert.ok(colWidth("一个很长很长的表头", [textWidth("1")]) >= textWidth("一个很长很长的表头", { bold: true }) + 16);
  // 没有数据时只按表头
  assert.equal(colWidth("PI", []), colWidth("PI", [0]));
});

test("colWidth: min and max clamp, result is an integer", () => {
  assert.equal(colWidth("", [], { min: 60 }), 60);
  assert.equal(colWidth("", [5000], { max: 320 }), 320);
  assert.ok(Number.isInteger(colWidth("PI", [textWidth("abc.def")])));
});

test("tagWidth / buttonWidth include padding", () => {
  assert.equal(tagWidth(""), 0);
  assert.ok(tagWidth("已改 PI") > textWidth("已改 PI"));
  assert.ok(buttonWidth("生成") > textWidth("生成"));
});
