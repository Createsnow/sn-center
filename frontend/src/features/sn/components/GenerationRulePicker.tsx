import { useInfiniteQuery } from '@tanstack/react-query';
import { Alert, Button, Select, Space } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import type { SnRule } from '../../../api/sn';
import { snWorkApi } from '../../../api/snWork';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 首次生成必须明确选择；只保存页面状态，选择由成功生成事务固化。 */
export function GenerationRulePicker({
  profile,
  value,
  onChange,
}: {
  profile: UserProfile;
  value?: SnRule;
  onChange: (rule: SnRule) => void;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const rules = useInfiniteQuery({
    queryKey: ['sn', 'generate-rules', profile.id, profile.authorizationVersion, search],
    queryFn: ({ pageParam }) => snWorkApi.ruleOptions(search, pageParam),
    initialPageParam: 1,
    getNextPageParam: (p) => (BigInt(p.page * p.size) < BigInt(p.total) ? p.page + 1 : undefined),
  });
  const items = rules.data?.pages.flatMap((p) => p.items) ?? [];
  if (value && !items.some((r) => r.id === value.id)) items.unshift(value);
  return (
    <>
      <Alert type="info" title={t('snPages.generate.ruleChoiceHint')} />
      <Space wrap className="sn-operation-actions">
        <Select
          aria-label={t('snPages.generate.pickRule')}
          placeholder={t('snPages.generate.pickRule')}
          value={value?.id}
          style={{ minWidth: 240, maxWidth: '100%' }}
          loading={rules.isFetching}
          showSearch={{ filterOption: false, onSearch: setSearch }}
          options={items.map((r) => ({
            value: r.id,
            label: `${r.code} · ${r.name} · v${r.current.version}`,
          }))}
          onChange={(id: string) => {
            const rule = items.find((r) => r.id === id);
            if (rule) onChange(rule);
          }}
        />
        {rules.hasNextPage && (
          <Button
            loading={rules.isFetchingNextPage}
            onClick={() => {
              void rules.fetchNextPage();
            }}
          >
            {t('snPages.more')}
          </Button>
        )}
      </Space>
      <ApiErrorAlert error={rules.error} />
    </>
  );
}
