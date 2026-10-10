import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError } from '../../api/contract';
import { initializationErrorText } from './initializationErrorText';
import zh from '../../i18n/prototype/errors.zh-CN.json';
import en from '../../i18n/prototype/errors.en.json';
import vi from '../../i18n/prototype/errors.vi.json';

describe('initialization errors', () => {
  it('shows the exact upper bound and offending input in all three languages', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      resources: {
        'zh-CN': { prototypeErrors: zh },
        en: { prototypeErrors: en },
        vi: { prototypeErrors: vi },
      },
    });
    for (const language of ['zh-CN', 'en', 'vi']) {
      const t = i18n.getFixedT(language);
      expect(
        initializationErrorText(
          new ApiError(1004000011, 409, '', { reason: 'PI_IMPORT_TOO_LARGE', max: '500000' }),
          t,
        ),
      ).toContain('500000');
      expect(
        initializationErrorText(
          new ApiError(1004000002, 409, '', {
            reason: 'GEN_START_INVALID',
            max: '9223372036854775807',
          }),
          t,
        ),
      ).toContain('9223372036854775807');
      expect(
        initializationErrorText(
          new ApiError(1000000001, 400, '', { reason: 'PI_IMPORT_SN_INVALID', line: '2' }),
          t,
        ),
      ).toContain('2');
      expect(
        initializationErrorText(
          new ApiError(1004000013, 409, '', { reason: 'PI_INIT_LOCKED', pi: 'PI-UNCHANGED' }),
          t,
        ),
      ).toContain('PI-UNCHANGED');
      for (const seq of ['0', '9223372036854775807'])
        expect(
          initializationErrorText(
            new ApiError(1004000015, 409, '', {
              reason: 'PI_IMPORT_SEQ_DUP',
              seq,
              pi: 'PI-CONFLICT',
            }),
            t,
          ),
        ).toContain(seq);
      expect(
        initializationErrorText(
          new ApiError(1004000015, 409, '', {
            reason: 'PI_IMPORT_DUP_EXISTING',
            sn: 'SN-CONFLICT',
            pi: 'PI-CONFLICT',
          }),
          t,
        ),
      ).toContain('SN-CONFLICT');
    }
  });
  it('does not translate missing parameters, unrelated codes or arbitrary reasons', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'zh-CN',
      resources: { 'zh-CN': { prototypeErrors: zh, translation: { errors: { unknown: '未知' } } } },
    });
    for (const error of [
      new ApiError(123, 409, '', { reason: 'PI_INIT_LOCKED', pi: 'P' }),
      new ApiError(1004000013, 409, '', { reason: 'PI_INIT_LOCKED' }),
      new ApiError(1004000002, 409, '', { reason: 'GEN_START_INVALID', max: '1e3' }),
      new ApiError(1000000001, 400, '', { reason: 'arbitrary-secret' }),
      new ApiError(1004000015, 409, '', { reason: 'PI_IMPORT_SEQ_DUP', seq: '-1', pi: 'P' }),
      new ApiError(1004000015, 409, '', { reason: 'PI_IMPORT_DUP_EXISTING', sn: 'S' }),
    ])
      expect(initializationErrorText(error, i18n.t)).toBe('未知');
  });
});
