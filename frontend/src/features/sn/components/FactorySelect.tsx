import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAccount } from '../lib/auth';
import { useFactories } from '../lib/factories';
import { bool, str } from '../lib/row';

/** scoped：只列当前账户授权的生产组织（系统管理员列全部）。 */
export function FactorySelect(props: {
  value: string;
  onChange: (v: string) => void;
  clearable?: boolean;
  disabled?: boolean;
  placeholder?: string;
  scoped?: boolean;
  width?: number;
}) {
  const { t } = useTranslation('sn');
  const { factories } = useFactories();
  const account = useAccount();
  const options = (props.scoped && !account.isAdmin
    ? factories.filter((f) => account.scopeFactories.includes(str(f.factoryCode)))
    : factories
  ).map((f) => ({
    value: str(f.factoryCode),
    label: `${str(f.factoryName)}（${str(f.factoryCode)}）${bool(f.mesEnabled) ? ' · MES' : ''}${bool(f.enabled) ? '' : t('common.disabledSuffix')}`,
  }));
  return (
    <Select
      showSearch={{ optionFilterProp: 'label' }}
      allowClear={props.clearable}
      disabled={props.disabled}
      placeholder={props.placeholder || t('common.factory')}
      style={{ width: props.width ?? 220 }}
      value={props.value || undefined}
      options={options}
      onChange={(v?: string) => props.onChange(v ?? '')}
    />
  );
}
