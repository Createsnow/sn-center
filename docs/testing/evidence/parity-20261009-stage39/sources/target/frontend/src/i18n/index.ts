import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { isLanguage } from '../api/contract';
import zh from './locales/zh-CN.json';
import en from './locales/en.json';
import vi from './locales/vi.json';
import prototypeZh from './prototype/zh-CN.json';
import prototypeEn from './prototype/en.json';
import prototypeVi from './prototype/vi.json';
import errorsZh from './prototype/errors.zh-CN.json';
import errorsEn from './prototype/errors.en.json';
import errorsVi from './prototype/errors.vi.json';

function normalizeLanguage(value: string | null) {
  if (/^zh/i.test(value ?? '')) return 'zh-CN';
  if (/^en/i.test(value ?? '')) return 'en';
  if (/^vi/i.test(value ?? '')) return 'vi';
  return undefined;
}

function savedLanguage() {
  try {
    const value = normalizeLanguage(localStorage.getItem('adtek-language'));
    if (value) return value;
  } catch {
    /* 存储受限时继续使用浏览器的语言偏好。 */
  }
  return (
    (navigator.languages ?? [navigator.language]).map(normalizeLanguage).find(Boolean) ?? 'zh-CN'
  );
}

void i18n.use(initReactI18next).init({
  resources: {
    'zh-CN': { translation: zh, prototype: prototypeZh, prototypeErrors: errorsZh },
    en: { translation: en, prototype: prototypeEn, prototypeErrors: errorsEn },
    vi: { translation: vi, prototype: prototypeVi, prototypeErrors: errorsVi },
  },
  lng: savedLanguage(),
  fallbackLng: 'zh-CN',
  supportedLngs: ['zh-CN', 'en', 'vi'],
  interpolation: { escapeValue: false },
  initAsync: false,
});

i18n.on('languageChanged', (language) => {
  if (isLanguage(language)) {
    document.documentElement.lang = language;
    document.title = i18n.t('prototype:app.title');
    try {
      localStorage.setItem('adtek-language', language);
    } catch {
      /* 隐私模式存储受限不影响语言切换。 */
    }
  }
});
document.documentElement.lang = i18n.language;
document.title = i18n.t('prototype:app.title');
export default i18n;
