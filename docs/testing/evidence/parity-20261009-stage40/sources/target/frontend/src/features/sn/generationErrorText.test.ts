import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError } from '../../api/contract';
import { generationErrorText, generationFailureText } from './generationErrorText';
import zh from '../../i18n/prototype/errors.zh-CN.json';
import en from '../../i18n/prototype/errors.en.json';

describe('generation failure feedback', () => {
  it('keeps exact sequence parameters and historical language while localizing current errors', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'en',
      resources: { en: { prototypeErrors: en }, 'zh-CN': { prototypeErrors: zh } },
    });
    const params = {
      reason: 'GEN_PREVIEW_STALE',
      pi: 'PI-TEST',
      expected: '9007199254740993',
      actual: '9007199254740994',
    };
    const current = generationErrorText(new ApiError(1004000016, 409, '', params), i18n.t);
    expect(current).toContain('9007199254740993');
    expect(current).toContain('9007199254740994');
    const historical = generationFailureText(1004000016, params, i18n.getFixedT('zh-CN'));
    expect(historical).toContain('9007199254740993');
    expect(historical).not.toBe(current);
    expect(generationFailureText(null, {}, i18n.t)).toBe('');
  });
  it('only translates reasons within the generation error family', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'zh-CN',
      resources: { 'zh-CN': { prototypeErrors: zh, translation: { errors: { unknown: '未知' } } } },
    });
    expect(
      generationErrorText(new ApiError(123, 409, '', { reason: 'GEN_PREVIEW_EXPIRED' }), i18n.t),
    ).toBe('未知');
    expect(
      generationErrorText(
        new ApiError(1004000016, 409, '', { reason: 'arbitrary-secret' }),
        i18n.t,
      ),
    ).toBe('未知');
    expect(
      generationErrorText(
        new ApiError(1004000016, 409, '', { reason: 'GEN_PREVIEW_EXPIRED' }),
        i18n.t,
      ),
    ).not.toBe('未知');
  });
});
