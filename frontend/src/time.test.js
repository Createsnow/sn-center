import test from "node:test";
import assert from "node:assert/strict";
import { formatTime, tzLabel, formatDuration } from "./utils/time.ts";

test("UTC ISO is shown in the org time zone", () => {
  assert.equal(formatTime("2026-10-18T16:00:00Z", 480), "2026-10-19 00:00:00");
  assert.equal(formatTime("2026-10-19T11:14:02Z", 420, false), "2026-10-19 18:14");
  assert.equal(formatTime(null), "—");
  assert.equal(tzLabel(480), "UTC+08:00");
  assert.equal(tzLabel(-330), "UTC-05:30");
  assert.equal(formatDuration(3723), "1h 2m 3s");
});
