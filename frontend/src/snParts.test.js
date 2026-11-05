import test from "node:test";
import assert from "node:assert/strict";
import {
  TEMPLATES, autoIndex, blockReason, defaultParts, formatDate, fromSpec, insertAt, isoWeek, movePart, moveTo, newErrors, newPart,
  partValue, partsSig, sampleSegments, todayIn, toSpec,
} from "./snParts.js";

const P = (kind, extra = {}) => ({ ...newPart(kind, "2026-11-05"), ...extra });
const ctx = { pi: "P2610-0701", billNo: "26100701" };
const codes = (r) => r.errors.map((e) => e.code);

/** 与后台 encoding.format_sn 同算法（10 / 32 进制），只用来核对换算结果能拼出示例 SN */
const CS = { 10: "0123456789", 32: "0123456789ABCDEFGHJKMNPQRSTVWXYZ" };
function sn(spec, seq, length = "", unit = "") {
  let s = "";
  for (let i = 0, x = seq; i < spec.seq_len; i++, x = Math.floor(x / spec.base)) s = CS[spec.base][x % spec.base] + s;
  const len = spec.length_pad ? length.padStart(spec.length_pad, "0") : length;
  const mid = spec.with_length ? spec.sep_before + len + spec.sep_after : "";
  return spec.prefix + s + mid + (spec.with_unit ? unit : "") + spec.suffix;
}

test("ISO week and date formats", () => {
  assert.deepEqual(isoWeek(2026, 11, 5), { year: 2026, week: 45 });
  assert.deepEqual(isoWeek(2026, 1, 1), { year: 2026, week: 1 });
  assert.deepEqual(isoWeek(2027, 1, 1), { year: 2026, week: 53 });
  assert.deepEqual(isoWeek(2024, 12, 30), { year: 2025, week: 1 });
  assert.equal(formatDate("YYWW", "2026-01-29"), "2605");
  assert.equal(formatDate("YYYYWW", "2027-01-01"), "202653");
  assert.equal(formatDate("YYMMDD", "2026-10-26"), "261026");
  assert.equal(formatDate("YYYYMMDD", "2026-04-09"), "20260409");
  assert.equal(formatDate("YYMM", "2026-04-09"), "2604");
  assert.equal(formatDate("YY", "2026-04-09"), "26");
  assert.equal(formatDate("YYMMDD", "2026-02-30"), "");
  assert.equal(formatDate("YYMMDD", ""), "");
});

test("today in the display time zone", () => {
  const now = Date.UTC(2026, 10, 4, 17, 0); // 11-04 17:00 UTC = 11-05 01:00 东八区
  assert.equal(todayIn(480, now), "2026-11-05");
  assert.equal(todayIn(0, now), "2026-11-04");
});

test("default is bill No. last 7 + 4-digit base-10 serial (same as before)", () => {
  const r = toSpec(defaultParts(), ctx);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.spec, {
    prefix: "6100701", base: 10, seq_len: 4, with_length: false, length_pad: 0, sep_before: "", sep_after: "",
    with_unit: false, suffix: "", seq_start: null,
  });
});

test("PI and bill No. cut", () => {
  assert.equal(partValue(P("BILL", { cut: "ALL" }), ctx), "26100701");
  assert.equal(partValue(P("BILL", { cut: "FIRST", n: 4 }), ctx), "2610");
  assert.equal(partValue(P("PI", { cut: "LAST", n: 4 }), ctx), "0701");
});

test("collected formats map onto the existing fields", () => {
  const cases = [
    // #1 CS-年周-流水-米数单位
    [[P("TEXT", { text: "CS" }), P("SEP"), P("DATE", { fmt: "YYWW", date: "2026-01-29" }), P("SEP"), P("SEQ"), P("SEP"), P("LEN", { pad: 3 }), P("UNIT")], 1, "CS-2605-0001-012M"],
    // #2 订单号-08 + 流水 + 米数单位
    [[P("BILL", { cut: "ALL" }), P("SEP"), P("TEXT", { text: "08" }), P("SEQ"), P("LEN", { pad: 3 }), P("UNIT")], 1, "26100701-080001012M"],
    // #3 订单号-SX-流水-米数-单位
    [[P("BILL", { cut: "ALL" }), P("SEP"), P("TEXT", { text: "SX" }), P("SEP"), P("SEQ", { len: 3 }), P("SEP"), P("LEN", { pad: 3 }), P("SEP"), P("UNIT")], 1, "26100701-SX-001-012-M"],
    // #5 年月日-Z01-流水
    [[P("DATE", { date: "2026-10-26" }), P("SEP"), P("TEXT", { text: "Z" }), P("TEXT", { text: "01" }), P("SEP"), P("SEQ", { len: 3 })], 1, "261026-Z01-001"],
    // #7 字母日期码（手动输入的固定文本）+ 数字标识 + 流水
    [[P("TEXT", { text: "AVFH" }), P("TEXT", { text: "6" }), P("SEQ")], 8, "AVFH60008"],
    // #8 CC-年月日流水-米数单位-01-04
    [[P("TEXT", { text: "CC" }), P("SEP"), P("DATE", { date: "2026-10-26" }), P("SEQ"), P("SEP"), P("LEN", { pad: 3 }), P("UNIT"), P("SEP"), P("TEXT", { text: "01" }), P("SEP"), P("TEXT", { text: "04" })], 1, "CC-2610260001-012M-01-04"],
    // #11 81 + 年周 + 4 位 32 进制（01AD = 1357）
    [[P("TEXT", { text: "81" }), P("DATE", { fmt: "YYWW", date: "2026-11-05" }), P("SEQ", { base: 32 })], 1357, "81264501AD"],
    // #15 年月日 + 5 位流水
    [[P("DATE", { date: "2026-04-09" }), P("SEQ", { len: 5 })], 1, "26040900001"],
  ];
  for (const [parts, seq, expect] of cases) {
    const r = toSpec(parts, ctx);
    assert.deepEqual(r.errors, [], expect);
    assert.equal(sn(r.spec, seq, "12", "M"), expect);
  }
});

test("text after length without unit goes to the suffix", () => {
  const r = toSpec([P("BILL"), P("SEQ"), P("SEP"), P("LEN"), P("SEP"), P("TEXT", { text: "A" })], ctx);
  assert.deepEqual(r.errors, []);
  assert.equal(r.spec.sep_before, "-");
  assert.equal(r.spec.sep_after, "");
  assert.equal(r.spec.suffix, "-A");
});

test("order rules", () => {
  assert.deepEqual(codes(toSpec([P("BILL")], ctx)), ["NO_SEQ"]);
  assert.deepEqual(codes(toSpec([P("SEQ"), P("BILL")], ctx)), ["PREFIX_EMPTY"]);
  assert.deepEqual(codes(toSpec([P("TEXT", { text: " " }), P("SEQ")], ctx)), ["PREFIX_EMPTY"]);
  assert.deepEqual(codes(toSpec([P("BILL"), P("LEN"), P("SEQ")], ctx)), ["AFTER_SEQ"]);
  assert.deepEqual(codes(toSpec([P("BILL"), P("SEQ"), P("UNIT"), P("LEN")], ctx)), ["UNIT_AFTER_LEN"]);
  assert.deepEqual(codes(toSpec([P("BILL"), P("SEQ"), P("SEP"), P("UNIT")], ctx)), ["UNIT_GAP"]);
  assert.deepEqual(codes(toSpec([P("BILL"), P("SEQ"), P("SEQ")], ctx)), ["DUPLICATE"]);
  assert.deepEqual(codes(toSpec([P("TEXT", { text: "X".repeat(33) }), P("SEQ")], ctx)), ["PREFIX_TOO_LONG"]);
  assert.deepEqual(codes(toSpec([P("BILL"), P("SEQ"), P("TEXT", { text: "Y".repeat(17) }), P("LEN")], ctx)), ["SEP_TOO_LONG"]);
});

test("start number only when a positive integer", () => {
  assert.equal(toSpec([P("BILL"), P("SEQ", { start: 101 })], ctx).spec.seq_start, 101);
  assert.equal(toSpec([P("BILL"), P("SEQ", { start: null })], ctx).spec.seq_start, null);
  assert.equal(toSpec([P("BILL"), P("SEQ", { start: 0 })], ctx).spec.seq_start, null);
});

test("existing custom rule splits back into parts and round-trips", () => {
  const spec = {
    prefix: "CS-2605-", base: 32, seq_len: 5, with_length: true, length_pad: 3, sep_before: "-", sep_after: "/",
    with_unit: true, suffix: "-01-04", seq_start: 9,
  };
  const parts = fromSpec(spec, [P("TEXT", { text: spec.prefix })]);
  assert.deepEqual(parts.map((p) => p.kind), ["TEXT", "SEQ", "SEP", "LEN", "SEP", "UNIT", "TEXT"]);
  assert.deepEqual(toSpec(parts, ctx).spec, spec);
  const plain = fromSpec({ base: 10, seq_len: 4, with_length: false, with_unit: false, suffix: "" }, [P("BILL")]);
  assert.deepEqual(plain.map((p) => p.kind), ["BILL", "SEQ"]);
});

test("move part", () => {
  const a = [P("BILL"), P("SEQ"), P("UNIT")];
  assert.deepEqual(movePart(a, 2, -1).map((p) => p.kind), ["BILL", "UNIT", "SEQ"]);
  assert.equal(movePart(a, 0, -1), a);
  assert.equal(new Set([...a, P("SEQ")].map((p) => p.id)).size, 4);
});

const kinds = (ps) => ps.map((p) => p.kind);

test("moveTo / insertAt", () => {
  const a = [P("TEXT"), P("SEP"), P("SEQ")];
  assert.deepEqual(kinds(moveTo(a, 0, 3)), ["SEP", "SEQ", "TEXT"]);
  assert.deepEqual(kinds(moveTo(a, 2, 0)), ["SEQ", "TEXT", "SEP"]);
  assert.deepEqual(kinds(moveTo(a, 0, 2)), ["SEP", "TEXT", "SEQ"]);
  assert.deepEqual(kinds(insertAt(a, 1, P("BILL"))), ["TEXT", "BILL", "SEP", "SEQ"]);
  assert.equal(a.length, 3);
});

test("placement checks only count new errors", () => {
  const a = [P("BILL"), P("SEQ"), P("UNIT")];
  assert.deepEqual(newErrors(a, moveTo(a, 2, 1), ctx), ["AFTER_SEQ"]);
  assert.deepEqual(newErrors(a, moveTo(a, 1, 0), ctx), ["UNIT_GAP", "PREFIX_EMPTY"]);
  assert.equal(blockReason(a, "LEN", 1, ctx), "AFTER_SEQ");
  assert.equal(blockReason(a, "LEN", 2, ctx), "");
  assert.equal(blockReason(a, "SEP", 2, ctx), "UNIT_GAP");
  assert.equal(blockReason(a, "UNIT", 3, ctx), "TAKEN");
  assert.equal(blockReason(a, "TEXT", 3, ctx), "");
  // 已有的错误不挡别的操作
  const bad = [P("SEQ"), P("BILL")];
  assert.deepEqual(newErrors(bad, insertAt(bad, 2, P("SEP")), ctx), []);
});

test("auto position for add", () => {
  const a = [P("BILL"), P("SEQ"), P("SEP"), P("TEXT", { text: "A" })];
  assert.equal(autoIndex(a, "UNIT", ctx), 2); // 放最后会隔着分隔符 → 紧跟流水
  assert.equal(autoIndex(a, "LEN", ctx), 4);
  assert.equal(autoIndex(a, "TEXT", ctx), 4);
  const b = [P("BILL"), P("SEQ"), P("UNIT")];
  assert.equal(autoIndex(b, "LEN", ctx), 2);
  assert.equal(autoIndex([P("BILL"), P("LEN"), P("UNIT")], "SEQ", ctx), 1);
  for (const k of ["TEXT", "SEP", "PI", "BILL", "DATE", "LEN"]) {
    assert.deepEqual(newErrors(b, insertAt(b, autoIndex(b, k, ctx), P(k)), ctx), [], k);
  }
});

test("common formats are valid and spell the collected samples", () => {
  const expect = {
    BILL_SEQ: [1, "61007010001"],
    CS_WEEK: [1, "CS-2645-0001-012M"],
    BILL_SX: [1, "26100701-SX-001-012-M"],
    DATE_SEQ: [1, "26110500001"],
    WEEK_B32: [1357, "81264501AD"],
  };
  for (const [k, f] of Object.entries(TEMPLATES)) {
    const r = toSpec(f("2026-11-05"), ctx);
    assert.deepEqual(r.errors, [], k);
    assert.equal(sn(r.spec, expect[k][0], "12", "M"), expect[k][1], k);
  }
  assert.equal(partsSig(TEMPLATES.BILL_SEQ()), partsSig(defaultParts()));
  assert.notEqual(partsSig(TEMPLATES.BILL_SEQ()), partsSig(TEMPLATES.DATE_SEQ("2026-11-05")));
});

test("sample segments colour serial, length and unit", () => {
  const ps = TEMPLATES.CS_WEEK("2026-11-05");
  const segs = sampleSegments(ps, ctx, "CS-2645-0001-012M", "0001", "012", "M");
  assert.deepEqual(segs.filter((s) => s.cls).map((s) => [s.text, s.cls]), [["0001", "SEQ"], ["012", "LINE"], ["M", "LINE"]]);
  assert.equal(segs.map((s) => s.text).join(""), "CS-2645-0001-012M");
  assert.equal(sampleSegments(ps, ctx, "OTHER", "0001", "012", "M"), null);
});
