import { describe, expect, it } from 'vitest';
import { DEFAULT_NAMESPACE } from '../i18n/dictionaries';

// 功能隔离门禁（与 eslint-plugin-boundaries 的导入边界互补）：功能代码只能使用本功能命名空间或共享 translation 命名空间的文案，
// 不能读取其他功能的字典。导入边界由 ESLint 检查。

const FEATURE_FILE = /\/features\/([a-z][a-z0-9-]*)\//;

export function namespaceViolations(file: string, source: string): string[] {
  const feature = FEATURE_FILE.exec(file)?.[1];
  if (!feature) {
    return [];
  }
  const allowed = new Set([feature, DEFAULT_NAMESPACE]);
  const used = [
    ...[...source.matchAll(/useTranslation\(\s*\[?\s*['"`]([^'"`]+)['"`]/g)].map((m) => m[1]!),
    ...[...source.matchAll(/\bns\s*:\s*\[?\s*['"`]([^'"`]+)['"`]/g)].map((m) => m[1]!),
    ...[...source.matchAll(/['"`]([a-z][a-z0-9-]*):[a-zA-Z]/g)].map((m) => m[1]!),
  ];
  return [...new Set(used)]
    .filter((namespace) => !allowed.has(namespace))
    .map((namespace) => `${file} uses namespace ${namespace}`);
}

describe('feature boundaries', () => {
  it('features only use their own or the shared translation namespace', () => {
    const sources = import.meta.glob<string>('../features/**/*.{ts,tsx}', {
      eager: true,
      query: '?raw',
      import: 'default',
    });
    const violations = Object.entries(sources).flatMap(([file, source]) =>
      namespaceViolations(file, source),
    );
    expect(violations).toEqual([]);
  });

  it('detects another feature namespace', () => {
    const source =
      "const { t } = useTranslation('stock'); t('order:title'); t('x', { ns: 'stock' });";
    expect(namespaceViolations('../features/order/pages/OrderPage.tsx', source)).toEqual([
      '../features/order/pages/OrderPage.tsx uses namespace stock',
    ]);
    expect(
      namespaceViolations('../features/order/a.ts', "useTranslation('order'); t('title')"),
    ).toEqual([]);
  });
});
