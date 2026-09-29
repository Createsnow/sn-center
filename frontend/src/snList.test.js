import test from "node:test";
import assert from "node:assert/strict";
import { parseSnList, suggestQty } from "./snList.js";

test("parseSnList takes first column, skips header, blanks and reports duplicates", () => {
  const r = parseSnList('﻿SN,备注\r\nA001,x\n"A002"\n\n  A003\t1\nA001\n');
  assert.deepEqual(r.sns, ["A001", "A002", "A003"]);
  assert.deepEqual(r.duplicates, ["A001"]);
});

test("parseSnList keeps first line when it is not a header", () => {
  assert.deepEqual(parseSnList("X1\nX2").sns, ["X1", "X2"]);
});

test("suggestQty respects page limit and quota", () => {
  assert.equal(suggestQty(25000, 10000), 10000);
  assert.equal(suggestQty(300, 10000), 300);
  assert.equal(suggestQty(0, 10000), 0);
  assert.equal(suggestQty(10, 0), 0);
});
