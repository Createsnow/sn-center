import { Alert, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { useSnFactories } from '../useSnFactories';

/** 候选按主体/授权版本完整加载并本地筛选，空范围不回退全厂；提交值保持精确ID字符串。 */
export function FactoryPicker({
  profile,
  value,
  onChange,
  multiple,
  placeholder,
  disabled,
}: { profile: UserProfile; placeholder?: string; disabled?: boolean } & (
  | { multiple?: false; value?: string; onChange?: (value?: string) => void }
  | { multiple: true; value?: string[]; onChange?: (value: string[]) => void }
)) {
  const { t } = useTranslation();
  const options = useSnFactories(profile);
  const choices = options.data?.map((factory) => ({
    value: factory.id,
    label: `${factory.name}（${factory.code}）`,
  }));
  const shared = {
    virtual: false,
    disabled,
    'aria-label': t('snPages.common.factory'),
    placeholder: placeholder ?? t('snPages.common.factory'),
    allowClear: true,
    showSearch: { optionFilterProp: 'label' },
    loading: options.isFetching,
    options: choices,
  };
  return (
    <div className="sn-factory-picker">
      {multiple ? (
        <Select<string[]> {...shared} mode="multiple" value={value} onChange={onChange} />
      ) : (
        <Select<string> {...shared} value={value} onChange={onChange} />
      )}
      {options.isError && <Alert type="error" title={t('snPages.candidatesFailed')} />}
    </div>
  );
}
