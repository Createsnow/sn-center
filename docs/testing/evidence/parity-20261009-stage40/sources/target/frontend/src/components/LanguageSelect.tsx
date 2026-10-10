import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Language } from '../api/contract';
import type { ReactNode } from 'react';

export function LanguageSelect({
  value,
  onChange,
  disabled = false,
  compact = false,
  suffixIcon,
}: {
  value: Language;
  onChange: (value: Language) => void;
  disabled?: boolean;
  compact?: boolean;
  suffixIcon?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Select
      aria-label={t('language')}
      value={value}
      onChange={onChange}
      disabled={disabled}
      virtual={false}
      suffixIcon={suffixIcon}
      size={compact ? 'small' : 'middle'}
      style={{ width: compact ? 118 : 145 }}
      options={[
        { value: 'zh-CN', label: '简体中文' },
        { value: 'en', label: 'English' },
        { value: 'vi', label: 'Tiếng Việt' },
      ]}
    />
  );
}
