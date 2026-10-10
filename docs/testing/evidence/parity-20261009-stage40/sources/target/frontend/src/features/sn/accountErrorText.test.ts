import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError } from '../../api/contract';
import { accountErrorText } from './accountErrorText';
import zh from '../../i18n/prototype/errors.zh-CN.json';
import en from '../../i18n/prototype/errors.en.json';
import vi from '../../i18n/prototype/errors.vi.json';

describe('SN account refusal messages', () => {
  it('translates duplicate names in three languages without exposing unknown parameters', async () => {
    for (const [language, messages] of [
      ['zh-CN', zh],
      ['en', en],
      ['vi', vi],
    ] as const) {
      const i18n = i18next.createInstance();
      await i18n.init({
        lng: language,
        resources: {
          [language]: {
            prototypeErrors: messages,
            translation: { errors: { unknown: 'unknown' } },
          },
        },
      });
      expect(
        accountErrorText(
          new ApiError(1004000032, 409, '', { reason: 'USER_EMP_NO_EXISTS', emp_no: 'sample' }),
          i18n.t,
        ),
      ).toBe(messages.USER_EMP_NO_EXISTS.replace('{{emp_no}}', 'sample'));
      expect(
        accountErrorText(
          new ApiError(1004000030, 400, '', { reason: 'UNRECOGNIZED', private: 'sensitive' }),
          i18n.t,
        ),
      ).toBe('unknown');
      expect(
        accountErrorText(new ApiError(999, 400, '', { reason: 'USER_FACTORY_REQUIRED' }), i18n.t),
      ).toBe('unknown');
    }
  });
});
