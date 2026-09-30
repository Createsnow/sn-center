import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Language } from '../api/contract';

export function LanguageSelect({
  value,
  onChange,
  disabled = false,
}: {
  value: Language;
  onChange: (value: Language) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Select
      aria-label={t('language')}
      value={value}
      onChange={onChange}
      disabled={disabled}
      style={{ minWidth: 145 }}
      options={[
        { value: 'zh-CN', label: '简体中文' },
        { value: 'en', label: 'English' },
        { value: 'vi', label: 'Tiếng Việt' },
      ]}
    />
  );
}
