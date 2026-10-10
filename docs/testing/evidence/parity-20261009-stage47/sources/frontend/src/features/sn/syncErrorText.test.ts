import { createInstance } from 'i18next';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/contract';
import zh from '../../i18n/prototype/errors.zh-CN.json';
import en from '../../i18n/prototype/errors.en.json';
import vi from '../../i18n/prototype/errors.vi.json';
import { syncErrorText } from './syncErrorText';

describe('订单同步可见错误', () => {
  const translations = createInstance();
  void translations.init({
    resources: {
      'zh-CN': { prototypeErrors: zh },
      en: { prototypeErrors: en },
      vi: { prototypeErrors: vi },
    },
    fallbackLng: 'zh-CN',
    initAsync: false,
    interpolation: { escapeValue: false },
  });
  it('三语展示安全业务摘要且不暴露统一信封错误和追踪号', () => {
    for (const [language, messages] of [
      ['zh-CN', zh],
      ['en', en],
      ['vi', vi],
    ] as const) {
      const t = translations.getFixedT(language);
      expect(
        syncErrorText(new ApiError(1005000006, 502, 'private-trace', { message: 'HTTP 503' }), t),
      ).toBe(messages.K3_QUERY_FAILED.replace('{{message}}', 'HTTP 503'));
      expect(
        syncErrorText(
          new ApiError(1005000002, 502, 'private-trace', { message: '比较登录失败' }),
          t,
        ),
      ).toBe(messages.K3_LOGIN_FAILED.replace('{{message}}', '比较登录失败'));
    }
  });
  it('未知和网络错误不采用未经识别的响应摘要', () => {
    translations.addResourceBundle('zh-CN', 'translation', {
      errors: { unknown: '操作失败', network: '网络连接异常' },
    });
    const t = translations.getFixedT('zh-CN');
    expect(syncErrorText(new Error('private-internal-error'), t)).toBe('操作失败');
    expect(syncErrorText(new ApiError(999, 500, '', { message: 'untrusted' }), t)).toBe('操作失败');
    expect(syncErrorText(new ApiError(0, 0), t)).toBe('网络连接异常');
  });
  it('三语的本地事务异常与原型固定HTTP错误一致且不采纳原始异常', () => {
    for (const language of ['zh-CN', 'en', 'vi']) {
      expect(
        syncErrorText(
          new ApiError(1000000002, 500, 'private-trace', {
            message: 'private SQL payload',
          }),
          translations.getFixedT(language),
        ),
      ).toBe('Internal Server Error');
    }
  });
});
