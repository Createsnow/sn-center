import test from "node:test";
import assert from "node:assert/strict";
import { clampQty, genQtyCap, parseSnList } from "./snList.js";

test("parseSnList takes first column, skips header, blanks and reports duplicates", () => {
  const r = parseSnList('﻿SN,备注\r\nA001,x\n"A002"\n\n  A003\t1\nA001\n');
  assert.deepEqual(r.sns, ["A001", "A002", "A003"]);
  assert.deepEqual(r.duplicates, ["A001"]);
});

test("parseSnList keeps first line when it is not a header", () => {
  assert.deepEqual(parseSnList("X1\nX2").sns, ["X1", "X2"]);
});

test("genQtyCap: quota within default limit, capped until unlocked, never above hard max", () => {
  const limits = { default: 10000, max: 500000 };
  assert.equal(genQtyCap(8000, limits, false), 8000);
  assert.equal(genQtyCap(10000, limits, false), 10000);
  assert.equal(genQtyCap(25000, limits, false), 10000);
  assert.equal(genQtyCap(25000, limits, true), 25000);
  assert.equal(genQtyCap(800000, limits, true), 500000);
  assert.equal(genQtyCap(0, limits, true), 0);
  assert.equal(genQtyCap(12.9, {}, false), 12);
});

test("clampQty corrects values above the cap and keeps at least 1", () => {
  assert.deepEqual(clampQty(60000, 50000), { qty: 50000, corrected: true });
  assert.deepEqual(clampQty(300, 50000), { qty: 300, corrected: false });
  assert.deepEqual(clampQty(12.7, 50000), { qty: 12, corrected: false });
  assert.deepEqual(clampQty(0, 50000), { qty: 1, corrected: false });
  assert.deepEqual(clampQty("abc", 50000), { qty: 1, corrected: false });
  assert.deepEqual(clampQty(5, 0), { qty: 0, corrected: true });
});
