import { useQuery } from '@tanstack/react-query';
import { Select } from 'antd';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import type { SnRule } from '../../../api/sn';
import { loadCatalog } from '../../../api/catalog';
import { snWorkApi } from '../../../api/snWork';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 沿用全部规则目录和通用默认选择；选定结果只在首次生成成功的事务中固化。 */
export function GenerationRulePicker({
  profile,
  value,
  onChange,
}: {
  profile: UserProfile;
  value?: SnRule;
  onChange: (rule: SnRule) => void;
}) {
  const { t } = useTranslation('prototype');
  const rules = useQuery({
    queryKey: ['sn', 'generate-rules', profile.id, profile.authorizationVersion],
    queryFn: ({ signal }) => loadCatalog((page) => snWorkApi.ruleOptions('', page), signal),
  });
  useEffect(() => {
    if (rules.data && !rules.data.some((r) => r.id === value?.id)) {
      const general = rules.data.find((r) => r.bindScope === 'GENERAL');
      if (general) onChange(general);
    }
  }, [rules.data, value?.id, onChange]);
  return (
    <>
      <Select
        aria-label={t('generate.rulePick')}
        placeholder={t('generate.rulePick')}
        value={value?.id}
        className="sn-generation-rule-picker"
        loading={rules.isFetching}
        showSearch={{ optionFilterProp: 'label' }}
        options={rules.data?.map((rule) => ({
          value: rule.id,
          rule,
          label:
            rule.code +
            ' · ' +
            rule.name +
            '（' +
            t('rules.scope' + rule.bindScope) +
            (rule.bindValue ? ' ' + rule.bindValue : '') +
            '）',
        }))}
        optionRender={({ data }) => (
          <div className="sn-generation-option">
            <span>
              <b>{data.rule.code}</b> · {data.rule.name}
            </span>
            <span className="sn-muted sn-mono">
              {data.rule.current.prefix +
                Array.from(data.rule.current.alphabet)[0]!.repeat(data.rule.current.length - 1) +
                Array.from(data.rule.current.alphabet)[1]! +
                data.rule.current.suffix}
            </span>
          </div>
        )}
        onChange={(id: string) => {
          const rule = rules.data?.find((r) => r.id === id);
          if (rule) onChange(rule);
        }}
      />
      <div className="sn-muted sn-generation-rule-hint">{t('generate.rulePickHint')}</div>
      <ApiErrorAlert error={rules.error} />
    </>
  );
}
