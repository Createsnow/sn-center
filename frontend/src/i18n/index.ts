import i18n, { type Resource } from 'i18next';
import { initReactI18next } from 'react-i18next';
import {
  DEFAULT_NAMESPACE,
  dictionaries,
  isLanguage,
  languages,
  type Language,
} from './dictionaries';

export { isLanguage, languages, type Language };

function savedLanguage(): Language {
  try {
    const value = localStorage.getItem('adtek-language');
    return isLanguage(value) ? value : 'zh-CN';
  } catch {
    return 'zh-CN';
  }
}

const resources: Resource = {};
for (const language of languages) {
  resources[language] = {};
  for (const [namespace, byLanguage] of Object.entries(dictionaries)) {
    const dictionary = byLanguage[language];
    if (dictionary) {
      resources[language][namespace] = dictionary;
    }
  }
}

void i18n.use(initReactI18next).init({
  resources,
  ns: Object.keys(dictionaries),
  defaultNS: DEFAULT_NAMESPACE,
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
