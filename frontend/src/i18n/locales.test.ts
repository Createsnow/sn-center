import { describe, expect, it } from 'vitest';
import { dictionaries, languages } from './dictionaries';

// 三语字典必须同步：每个命名空间（共享 translation 与各功能）三种语言齐全，键集合与插值参数一致。
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

describe.each(Object.entries(dictionaries))('namespace %s', (_, byLanguage) => {
  it('has every language', () => {
    expect(Object.keys(byLanguage).sort()).toEqual([...languages].sort());
  });

  it.each(languages.filter((language) => language !== 'zh-CN'))(
    '%s has the same keys and interpolation parameters as zh-CN',
    (language) => {
      const base = flatten(byLanguage['zh-CN']);
      const other = flatten(byLanguage[language]);
      expect([...other.keys()].sort()).toEqual([...base.keys()].sort());
      for (const [key, text] of base) {
        expect(parameters(other.get(key) ?? ''), key).toEqual(parameters(text));
        expect(other.get(key)?.trim(), key).not.toBe('');
      }
    },
  );
});
