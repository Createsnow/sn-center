import test from "node:test";
import assert from "node:assert/strict";
import {
  billMark, defaultRulePrefix, emptySel, genLimit, lineOrder, scopeBody, selectedLines, seqSource, toggleBill, toggleLine,
} from "./genScope.js";

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

test("seqSource: MAX by default, RANGE needs a valid start / end (end defaults to start)", () => {
  assert.deepEqual(seqSource("MAX"), { body: { seq_mode: "MAX" }, error: null });
  assert.deepEqual(seqSource(undefined, 3, 4).body, { seq_mode: "MAX" });
  assert.deepEqual(seqSource("RANGE", 4, 7).body, { seq_mode: "RANGE", seq_start: 4, seq_end: 7 });
  assert.deepEqual(seqSource("RANGE", "5", "").body, { seq_mode: "RANGE", seq_start: 5, seq_end: 5 }, "只填起号 = 单个号");
  for (const [a, b] of [[null, 3], [0, 3], [5, 4], [1.5, 3], ["x", 3]]) {
    assert.deepEqual(seqSource("RANGE", a, b), { body: null, error: "SEQ_RANGE" }, `${a}~${b}`);
  }
});

test("default rule prefix: first picked bill until the PI has SNs, then the prefix in use", () => {
  const lines = [{ bill_no: "MO24000002", seq: 1 }, { bill_no: "MO24000001", seq: 1 }];
  // 还没有生成的号：点选的第一个单据编码后 7 位，不按单据编号大小
  assert.equal(defaultRulePrefix({ prefix: "4000001", custom: null }, lines), "4000002");
  // 已生成的号还在：沿用已用的前缀（含手填的），不论点选哪张单据
  assert.equal(defaultRulePrefix({ prefix: "HAND-01", custom: { seq_len: 4 } }, lines), "HAND-01");
  // 没有点选的单据：PI 第一张单据
  assert.equal(defaultRulePrefix({ prefix: "4000001", custom: null }, []), "4000001");
  // 单据编号不足 7 位：整个编号
  assert.equal(defaultRulePrefix({ prefix: "", custom: null }, [{ bill_no: "MO1", seq: 1 }]), "MO1");
  assert.equal(defaultRulePrefix(undefined, []), "");
});
