import test from "node:test";
import assert from "node:assert/strict";
import { billMark, emptySel, genLimit, lineOrder, revokeRange, scopeBody, selectedLines, toggleBill, toggleLine } from "./genScope.js";

const BILLS = { A: [1, 2, 3], B: [1, 2] };
const linesOf = (no) => (BILLS[no] || []).map((seq) => ({ bill_no: no, seq }));
const L = (bill_no, seq) => ({ bill_no, seq });

test("ticking bills keeps the tick order and scopes by bill", () => {
  let s = toggleBill(emptySel(), "B", true, linesOf);
  s = toggleBill(s, "A", true, linesOf);
  assert.deepEqual(scopeBody(s), { scope: "BILLS", bills: ["B", "A"] });
  assert.deepEqual(billMark(s, "A", linesOf), { checked: true, indeterminate: false, order: 1 });
  assert.deepEqual(selectedLines(s, linesOf), [L("B", 1), L("B", 2), L("A", 1), L("A", 2), L("A", 3)]);
  s = toggleBill(s, "B", false, linesOf);
  assert.deepEqual(scopeBody(s), { scope: "BILLS", bills: ["A"] });
});

test("picking a line after ticking bills expands the bills into lines in the same order", () => {
  let s = toggleBill(emptySel(), "B", true, linesOf);
  s = toggleLine(s, L("A", 3), true, linesOf);
  assert.deepEqual(scopeBody(s), { scope: "LINES", lines: [L("B", 1), L("B", 2), L("A", 3)] });
  assert.equal(lineOrder(s, L("A", 3)), 2);
  assert.deepEqual(billMark(s, "B", linesOf), { checked: true, indeterminate: false, order: -1 });
  assert.deepEqual(billMark(s, "A", linesOf), { checked: false, indeterminate: true, order: -1 });
  s = toggleLine(s, L("B", 1), false, linesOf);
  assert.deepEqual(s.lines, [L("B", 2), L("A", 3)]);
});

test("ticking a bill while lines are picked appends its lines; unticking removes them", () => {
  let s = toggleLine(emptySel(), L("A", 2), true, linesOf);
  s = toggleBill(s, "B", true, linesOf);
  assert.deepEqual(s.lines, [L("A", 2), L("B", 1), L("B", 2)]);
  s = toggleBill(s, "A", true, linesOf);
  assert.deepEqual(s.lines, [L("B", 1), L("B", 2), L("A", 1), L("A", 2), L("A", 3)]);
  s = toggleBill(s, "B", false, linesOf);
  s = toggleBill(s, "A", false, linesOf);
  assert.equal(scopeBody(s), null, "都不选 = 整张 PI");
});

test("genLimit: over the default needs unlock, over the hard max is refused", () => {
  const limits = { default: 10000, max: 500000 };
  assert.equal(genLimit(10000, limits, false), null);
  assert.equal(genLimit(10001, limits, false), "UNLOCK");
  assert.equal(genLimit(10001, limits, true), null);
  assert.equal(genLimit(500001, limits, true), "MAX");
  assert.equal(genLimit(5, {}, false), null);
});

test("revoking a line takes every segment after it in the serial", () => {
  // 从流水最末往前：A/3、B/1（另一次生成）、A/1；A/1 自己已分配
  const segs = [
    { bill_no: "A", seq: 3, bad: 0, ok: true },
    { bill_no: "B", seq: 1, bad: 0, ok: true },
    { bill_no: "A", seq: 1, bad: 2, ok: false },
  ];
  assert.equal(revokeRange(segs, [L("A", 2)]), null, "没有号的行不能撤");
  const r = revokeRange(segs, [L("B", 1)]);
  assert.deepEqual(r.segs.map((s) => `${s.bill_no}/${s.seq}`), ["A/3", "B/1"]);
  assert.equal(r.ok, true);
  const both = revokeRange(segs, [L("A", 3), L("A", 1)]);
  assert.equal(both.segs.length, 3, "撤到所选行中流水最靠前的一段");
  assert.deepEqual([both.ok, both.own], [false, true]);
  const later = revokeRange([{ bill_no: "A", seq: 3, bad: 1, ok: false }, { bill_no: "B", seq: 1, bad: 0, ok: false }], [L("B", 1)]);
  assert.deepEqual([later.ok, later.own], [false, false], "之后的行已分配");
});
