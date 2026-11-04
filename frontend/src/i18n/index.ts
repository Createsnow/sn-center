import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './locales/zh-CN.json';
import en from './locales/en.json';
import vi from './locales/vi.json';

export const languages = ['zh-CN', 'en', 'vi'] as const;
export type Language = (typeof languages)[number];

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (languages as readonly string[]).includes(value);
}

function savedLanguage(): Language {
  try {
    const value = localStorage.getItem('adtek-language');
    return isLanguage(value) ? value : 'zh-CN';
  } catch {
    return 'zh-CN';
  }
}

void i18n.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zh }, en: { translation: en }, vi: { translation: vi } },
  lng: savedLanguage(),
  fallbackLng: 'zh-CN',
  supportedLngs: [...languages],
  interpolation: { escapeValue: false },
  initAsync: false,
});

i18n.on('languageChanged', (language) => {
  if (isLanguage(language)) {
    document.documentElement.lang = language;
    try {
      localStorage.setItem('adtek-language', language);
    } catch {
      /* 隐私模式存储受限不影响语言切换。 */
    }
  }
});
document.documentElement.lang = i18n.language;
export default i18n;
