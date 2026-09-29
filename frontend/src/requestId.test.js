import assert from "node:assert/strict";
import { test } from "node:test";
import { newRequestId } from "./requestId.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test("有 randomUUID 时直接使用", () => {
  const id = newRequestId({
    randomUUID: () => "11111111-1111-4111-8111-111111111111",
  });
  assert.equal(id, "11111111-1111-4111-8111-111111111111");
});

test("局域网非安全上下文：没有 randomUUID 但有 getRandomValues 时仍返回 UUID", () => {
  const fake = {
    getRandomValues(arr) {
      for (let i = 0; i < arr.length; i++) arr[i] = (i * 17) & 0xff;
      return arr;
    },
  };
  const id = newRequestId(fake);
  assert.match(id, UUID_V4);
});

test("crypto.randomUUID 缺失时不抛 TypeError", () => {
  assert.doesNotThrow(() => newRequestId({}));
  const id = newRequestId({});
  assert.equal(typeof id, "string");
  assert.ok(id.length > 8);
});

test("默认走全局 crypto，不抛错", () => {
  const id = newRequestId();
  assert.equal(typeof id, "string");
  assert.ok(id.length > 8);
});
