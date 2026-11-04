import { describe, expect, it } from 'vitest';
import zh from './locales/zh-CN.json';
import en from './locales/en.json';
import vi from './locales/vi.json';

// 三语字典必须同步：键集合与插值参数一致，避免某一语言漏译或参数错位。
function flatten(value: unknown, prefix = ''): Map<string, string> {
  const result = new Map<string, string>();
  if (typeof value === 'string') {
    result.set(prefix, value);
    return result;
  }
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    for (const entry of flatten(child, prefix ? `${prefix}.${key}` : key)) {
      result.set(...entry);
    }
  }
  return result;
}

function parameters(text: string): string[] {
  return [...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]!).sort();
}

describe('locale dictionaries', () => {
  const base = flatten(zh);
  it.each([
    ['en', en],
    ['vi', vi],
  ])('%s has the same keys and interpolation parameters as zh-CN', (_, dictionary) => {
    const other = flatten(dictionary);
    expect([...other.keys()].sort()).toEqual([...base.keys()].sort());
    for (const [key, text] of base) {
      expect(parameters(other.get(key) ?? ''), key).toEqual(parameters(text));
      expect(other.get(key)?.trim(), key).not.toBe('');
    }
  });
});
