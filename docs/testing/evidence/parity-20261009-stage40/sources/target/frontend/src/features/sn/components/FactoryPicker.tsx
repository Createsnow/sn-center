import { useInfiniteQuery } from '@tanstack/react-query';
import { Alert, Button, Select } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi } from '../../../api/sn';
import type { UserProfile } from '../../../api/contract';

/** 候选按当前主体/授权版本分页，提供加载后续页，空范围不回退全厂；提交值保持精确ID字符串。 */
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
  const [search, setSearch] = useState('');
  const options = useInfiniteQuery({
    queryKey: ['sn', 'factory-options', profile.id, profile.authorizationVersion, search],
    queryFn: ({ pageParam }) => snApi.factories({ page: pageParam, size: 50, q: search }),
    initialPageParam: 1,
    getNextPageParam: (page) =>
      BigInt(page.page * page.size) < BigInt(page.total) ? page.page + 1 : undefined,
  });
  const choices = options.data?.pages.flatMap((page) =>
    page.items.map((factory) => ({
      value: factory.id,
      label: `${factory.name}（${factory.code}）`,
    })),
  );
  const shared = {
    virtual: false,
    disabled,
    'aria-label': t('snPages.common.factory'),
    placeholder: placeholder ?? t('snPages.common.factory'),
    allowClear: true,
    showSearch: { filterOption: false, onSearch: setSearch },
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
      {options.hasNextPage && (
        <Button
          size="small"
          onClick={() => {
            void options.fetchNextPage();
          }}
        >
          {t('snPages.more')}
        </Button>
      )}
      {options.isError && <Alert type="error" title={t('snPages.candidatesFailed')} />}
    </div>
  );
}
