import { useInfiniteQuery } from '@tanstack/react-query';
import { Button, Checkbox, Select, Space } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { snApi, type Order } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 三级候选按当前主体/授权版本缓存；待办过滤不移除当前已选订单，便于分配后继续查看结果。 */
export function GenerationOrderPicker({
  profile,
  billId,
  bill,
  onChange,
}: {
  profile: UserProfile;
  billId: string;
  bill?: Order;
  onChange: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [customer, setCustomer] = useState<string>(),
    [pi, setPi] = useState<string>(),
    [pending, setPending] = useState(true),
    [customerSearch, setCustomerSearch] = useState(''),
    [piSearch, setPiSearch] = useState(''),
    [search, setSearch] = useState('');
  const [syncedBill, setSyncedBill] = useState('');
  // 深链接及历史导航回到已选单据时，同步其客户/PI，不能显示上一个候选筛选。
  if (billId !== syncedBill && (!billId || bill?.id === billId)) {
    setSyncedBill(billId);
    if (bill) {
      setCustomer(bill.customer);
      setPi(bill.pi);
      setCustomerSearch('');
      setPiSearch('');
      setSearch('');
    }
  }
  const prefix = ['sn', 'generate', profile.id, profile.authorizationVersion];
  const pageOptions = {
    initialPageParam: 1,
    getNextPageParam: (p: { page: number; size: number; total: string }) =>
      BigInt(p.page * p.size) < BigInt(p.total) ? p.page + 1 : undefined,
  };
  const customers = useInfiniteQuery({
    ...pageOptions,
    queryKey: [...prefix, 'customers', customerSearch],
    queryFn: ({ pageParam }) => snApi.customers({ q: customerSearch, page: pageParam, size: 50 }),
  });
  const pis = useInfiniteQuery({
    ...pageOptions,
    queryKey: [...prefix, 'pis', customer, piSearch, pending],
    queryFn: ({ pageParam }) =>
      snApi.pis({ customer, q: piSearch, pending, page: pageParam, size: 50 }),
  });
  const orders = useInfiniteQuery({
    ...pageOptions,
    queryKey: [...prefix, 'orders', customer, pi, search, pending],
    queryFn: ({ pageParam }) =>
      snApi.orders({ customer, pi, q: search, pending, page: pageParam, size: 50 }),
  });
  const choices =
    orders.data?.pages.flatMap((p) =>
      p.items.map((order) => ({
        value: order.id,
        label: `${order.billNo} · ${order.pi} · ${order.orgName}`,
      })),
    ) ?? [];
  if (bill && !choices.some((c) => c.value === bill.id))
    choices.unshift({
      value: bill.id,
      label: `${bill.billNo} · ${bill.pi} · ${bill.orgName}`,
    });
  return (
    <>
      <Space wrap>
        <Select
          aria-label={t('snPages.generate.pickCustomer')}
          placeholder={t('snPages.generate.pickCustomer')}
          allowClear
          value={customer}
          style={{ width: 190 }}
          loading={customers.isFetching}
          showSearch={{ filterOption: false, onSearch: setCustomerSearch }}
          options={customers.data?.pages.flatMap((p) => p.items.map((c) => ({ value: c })))}
          onChange={(value: string | undefined) => {
            setCustomer(value);
            setPi(undefined);
            setPiSearch('');
            setSearch('');
            onChange('');
          }}
        />
        {customers.hasNextPage && (
          <Button
            loading={customers.isFetchingNextPage}
            onClick={() => {
              void customers.fetchNextPage();
            }}
          >
            {t('snPages.generate.moreCustomers')}
          </Button>
        )}
        <Select
          aria-label={t('snPages.generate.pickPi')}
          placeholder={t('snPages.generate.pickPi')}
          allowClear
          value={pi}
          style={{ width: 290 }}
          loading={pis.isFetching}
          showSearch={{ filterOption: false, onSearch: setPiSearch }}
          options={pis.data?.pages.flatMap((p) =>
            p.items.map((item) => ({
              value: item.pi,
              label: t('snPages.generate.piOption', {
                pi: item.pi,
                bills: item.bills,
                quota: item.quota,
                pending: item.pendingQuantity,
              }),
            })),
          )}
          onChange={(value: string | undefined) => {
            setPi(value);
            setSearch('');
            onChange('');
          }}
        />
        {pis.hasNextPage && (
          <Button
            loading={pis.isFetchingNextPage}
            onClick={() => {
              void pis.fetchNextPage();
            }}
          >
            {t('snPages.generate.morePis')}
          </Button>
        )}
        <Select
          aria-label={t('snPages.generate.pickOrder')}
          placeholder={t('snPages.generate.pickOrder')}
          value={billId || undefined}
          options={choices}
          style={{ width: 350, maxWidth: '100%' }}
          loading={orders.isFetching}
          showSearch={{ filterOption: false, onSearch: setSearch }}
          onChange={onChange}
        />
        {orders.hasNextPage && (
          <Button
            loading={orders.isFetchingNextPage}
            onClick={() => {
              void orders.fetchNextPage();
            }}
          >
            {t('snPages.more')}
          </Button>
        )}
        <Checkbox checked={pending} onChange={(e) => setPending(e.target.checked)}>
          {t('snPages.orders.pendingOnly')}
        </Checkbox>
      </Space>
      <ApiErrorAlert error={customers.error ?? pis.error ?? orders.error} />
    </>
  );
}
