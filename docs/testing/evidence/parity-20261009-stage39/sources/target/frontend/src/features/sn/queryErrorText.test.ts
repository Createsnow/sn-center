import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError } from '../../api/contract';
import { queryErrorText } from './queryErrorText';
import zh from '../../i18n/prototype/errors.zh-CN.json';
import en from '../../i18n/prototype/errors.en.json';
import vi from '../../i18n/prototype/errors.vi.json';

describe('query scope errors', () => {
  it('explains the rejected PI in all supported languages', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      resources: {
        'zh-CN': { prototypeErrors: zh },
        en: { prototypeErrors: en },
        vi: { prototypeErrors: vi },
      },
    });
    for (const language of ['zh-CN', 'en', 'vi']) {
      const actual = queryErrorText(
        new ApiError(1003000004, 403, '', { reason: 'QUERY_PI_NOT_OWNED', pi: 'PI-A' }),
        i18n.getFixedT(language),
      );
      expect(actual).toContain('PI-A');
      expect(actual).not.toContain('QUERY_PI_NOT_OWNED');
    }
  });
  it('keeps unrelated or malformed rejections generic', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'zh-CN',
      resources: {
        'zh-CN': {
          prototypeErrors: zh,
          translation: { errors: { unknown: '未知', 1003000004: '无权限' } },
        },
      },
    });
    const invalid: Record<string, string>[] = [
      {},
      { reason: 'other', pi: 'PI-A' },
      { reason: 'QUERY_PI_NOT_OWNED' },
      { reason: 'QUERY_PI_NOT_OWNED', pi: 'P\nI' },
      { reason: 'QUERY_PI_NOT_OWNED', pi: 'x'.repeat(129) },
    ];
    for (const params of invalid)
      expect(queryErrorText(new ApiError(1003000004, 403, '', params), i18n.t)).toBe('无权限');
    expect(
      queryErrorText(
        new ApiError(1, 403, '', { reason: 'QUERY_PI_NOT_OWNED', pi: 'PI-A' }),
        i18n.t,
      ),
    ).toBe('未知');
  });
});
