import zh from './locales/zh-CN.json';
import en from './locales/en.json';
import vi from './locales/vi.json';

// 字典组织：共享文案与错误码文案在默认命名空间 translation（src/i18n/locales）；
// 每个功能使用与目录同名的独立命名空间（src/features/{feature}/locales/{language}.json），
// 修改一个功能的文案不会触及其他功能的字典文件。

export const languages = ['zh-CN', 'en', 'vi'] as const;
export type Language = (typeof languages)[number];
export type Dictionary = Record<string, unknown>;
export const DEFAULT_NAMESPACE = 'translation';

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (languages as readonly string[]).includes(value);
}

const FEATURE_DICTIONARY = /\/features\/([a-z][a-z0-9-]*)\/locales\/([^/]+)\.json$/;

/** 按命名空间组织的三语字典：{ namespace: { language: dictionary } }。 */
export function groupDictionaries(
  featureFiles: Readonly<Record<string, Dictionary>>,
): Record<string, Partial<Record<Language, Dictionary>>> {
  const namespaces: Record<string, Partial<Record<Language, Dictionary>>> = {
    [DEFAULT_NAMESPACE]: { 'zh-CN': zh, en, vi },
  };
  for (const [file, dictionary] of Object.entries(featureFiles)) {
    const match = FEATURE_DICTIONARY.exec(file);
    const feature = match?.[1];
    const language = match?.[2];
    if (!feature || !isLanguage(language) || feature === DEFAULT_NAMESPACE) {
      throw new Error(`Dictionary ${file} must be src/features/{feature}/locales/{language}.json`);
    }
    (namespaces[feature] ??= {})[language] = dictionary;
  }
  return namespaces;
}

export const dictionaries = groupDictionaries(
  import.meta.glob<Dictionary>('../features/*/locales/*.json', { eager: true, import: 'default' }),
);
