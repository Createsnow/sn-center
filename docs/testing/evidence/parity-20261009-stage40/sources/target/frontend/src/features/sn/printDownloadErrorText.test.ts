import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError, DownloadHttpError } from '../../api/contract';
import { printDownloadErrorText } from './printDownloadErrorText';
import zh from '../../i18n/locales/zh-CN.json';
import en from '../../i18n/locales/en.json';
import vi from '../../i18n/locales/vi.json';
import prototypeZh from '../../i18n/prototype/zh-CN.json';
import prototypeEn from '../../i18n/prototype/en.json';
import prototypeVi from '../../i18n/prototype/vi.json';

describe('print download feedback', () => {
  for (const [language, translation, prototype] of [
    ['zh-CN', zh, prototypeZh],
    ['en', en, prototypeEn],
    ['vi', vi, prototypeVi],
  ] as const) {
    it(`${language}: distinguishes HTTP fallback, business denial, network and invalid file`, async () => {
      const i18n = i18next.createInstance();
      await i18n.init({ lng: language, resources: { [language]: { translation, prototype } } });
      expect(printDownloadErrorText(new DownloadHttpError(503), i18n.t)).toBe(
        prototype.common.downloadFailed.replace('{{status}}', '503'),
      );
      expect(printDownloadErrorText(new ApiError(1000000004, 403), i18n.t)).toBe(
        translation.errors['1000000004'],
      );
      expect(printDownloadErrorText(new ApiError(1000000006, 0), i18n.t)).toBe(
        translation.errors.network,
      );
      expect(printDownloadErrorText(new ApiError(1000000006, 200), i18n.t)).toBe(
        translation.errors['1000000006'],
      );
      expect(printDownloadErrorText(new DownloadHttpError(200), i18n.t)).toBe(
        translation.errors['1000000006'],
      );
      expect(printDownloadErrorText(new ApiError(1000000006, 503), i18n.t)).toBe(
        translation.errors['1000000006'],
      );
      expect(printDownloadErrorText(new Error('untrusted response body'), i18n.t)).toBe(
        translation.errors.unknown,
      );
    });
  }
});
