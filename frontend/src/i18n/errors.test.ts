import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import zh from './locales/zh-CN.json';
import en from './locales/en.json';
import vi from './locales/vi.json';

// 按实际Java导出的常量检查，不只比较现有locale的键，避免三语同时漏掉同一错误。
describe('published error translations', () => {
  it('covers every published integer and uses identical interpolation parameters', () => {
    const backend = resolve(process.cwd(), '../backend');
    const seen = new Set<string>();
    for (const module of readdirSync(backend).filter((name) =>
      ['adtek-common', 'adtek-framework', 'adtek-system', 'adtek-equipment'].includes(name),
    )) {
      const source = readFileSync(
        resolve(
          backend,
          module,
          `src/main/java/com/adtek/${module.slice(6)}/constant/ErrorCodeConstants.java`,
        ),
        { encoding: 'utf8', flag: 'r' },
      );
      for (const match of source.matchAll(/new ErrorCode\(([\d_]+),/g)) {
        const code = match[1]!.replaceAll('_', '');
        expect(seen.has(code), code).toBe(false);
        seen.add(code);
        const values = [zh.errors, en.errors, vi.errors].map(
          (errors) => (errors as Record<string, string>)[code],
        );
        for (const value of values) expect(value, code).toBeTruthy();
        const params = values.map((value) =>
          [...value!.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort(),
        );
        expect(params[1], code).toEqual(params[0]);
        expect(params[2], code).toEqual(params[0]);
      }
    }
    expect(seen.size).toBeGreaterThan(30);
  });
});
