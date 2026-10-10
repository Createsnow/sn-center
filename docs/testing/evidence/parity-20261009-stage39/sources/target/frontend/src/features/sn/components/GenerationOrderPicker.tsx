import { RightOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Checkbox, Select, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { UserProfile } from '../../../api/contract';
import { snApi, type Order } from '../../../api/sn';
import { loadCatalog } from '../../../api/catalog';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { formatSnTime } from '../../../utils/snTime';

/** 候选保持原型的搜索上限；清空筛选不会清掉仍匹配的当前订单，选中唯一 PI 单据时自动进入。 */
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
  const { t } = useTranslation('prototype');
  const [customer, setCustomer] = useState<string>(),
    [pi, setPi] = useState<string>();
  const [pending, setPending] = useState(true),
    [customerSearch, setCustomerSearch] = useState('');
  const [piSearch, setPiSearch] = useState(''),
    [search, setSearch] = useState('');
  const [syncedBill, setSyncedBill] = useState(''),
    [autoPickPi, setAutoPickPi] = useState('');
  if (billId !== syncedBill && (!billId || bill?.id === billId)) {
    setSyncedBill(billId);
    if (bill) {
      setCustomer(bill.customer);
      setPi(bill.pi);
    }
  }
  const prefix = ['sn', 'generate-picker', profile.id, profile.authorizationVersion];
  const customers = useQuery({
    queryKey: [...prefix, 'customers', customerSearch],
    queryFn: ({ signal }) => snApi.ruleCustomers(customerSearch, signal),
  });
  const pis = useQuery({
    queryKey: [...prefix, 'pis', customer, piSearch, pending],
    queryFn: () => snApi.pis({ customer, q: piSearch, pending, page: 1, size: 50 }),
  });
  const orders = useQuery({
    queryKey: [...prefix, 'orders', customer, pi, search, pending],
    queryFn: async ({ signal }) => {
      const first = await snApi.orders({
        customer,
        pi,
        q: search,
        pending,
        page: 1,
        size: pi ? 100 : 50,
      });
      const items = pi
        ? await loadCatalog(
            (page) =>
              page === 1
                ? Promise.resolve(first)
                : snApi.orders({ customer, pi, q: search, pending, page, size: 100 }),
            signal,
            200,
          )
        : first.items;
      return { items, total: first.total };
    },
  });
  const meta = useQuery({ queryKey: [...prefix, 'meta'], queryFn: snApi.meta });
  useEffect(() => {
    if (autoPickPi && autoPickPi === pi && !billId && orders.data?.items.length === 1)
      onChange(orders.data.items[0]!.id);
  }, [autoPickPi, pi, billId, orders.data, onChange]);
  const choices = orders.data?.items ?? [];
  const optionRows =
    bill && !choices.some((item) => item.id === bill.id) ? [bill, ...choices] : choices;
  const quantities = (quota: string, pendingQty: string) => (
    <span>
      <Tag color={quota === '0' ? 'default' : 'green'}>
        {t('orders.quota')} {quota}
      </Tag>
      {pendingQty !== '0' && (
        <Tag color="orange">
          {t('generate.pendingShort')} {pendingQty}
        </Tag>
      )}
    </span>
  );
  return (
    <section className="sn-surface sn-generation-picker">
      <div className="sn-toolbar">
        <Select
          aria-label={t('generate.pickCustomer')}
          placeholder={t('generate.pickCustomer')}
          suffixIcon={null}
          allowClear
          value={customer}
          className="sn-generation-customer"
          loading={customers.isFetching}
          showSearch={{ filterOption: false, onSearch: setCustomerSearch }}
          onFocus={() => setCustomerSearch('')}
          options={customers.data?.map((c) => ({ value: c }))}
          onChange={(value: string | undefined) => {
            setCustomer(value);
            setPi(undefined);
            setAutoPickPi('');
            setPiSearch('');
            setSearch('');
            if (bill && value && bill.customer !== value) onChange('');
          }}
        />
        <RightOutlined className="sn-muted" />
        <Select
          aria-label={t('generate.pickPi')}
          placeholder={t('generate.pickPi')}
          suffixIcon={null}
          allowClear
          value={pi}
          className="sn-generation-pi"
          loading={pis.isFetching}
          showSearch={{ filterOption: false, onSearch: setPiSearch }}
          onFocus={() => setPiSearch('')}
          options={pis.data?.items.map((item) => ({ value: item.pi, label: item.pi, item }))}
          optionRender={({ data }) => (
            <div className="sn-generation-option">
              <b>{data.item.pi}</b>
              <span className="sn-muted">
                {data.item.customer} · {t('generate.piBills', { n: data.item.bills })}
              </span>
              {quantities(data.item.quota, data.item.pendingQuantity)}
            </div>
          )}
          onChange={(value: string | undefined) => {
            setPi(value);
            setSearch('');
            setAutoPickPi(value ?? '');
            if (bill && value && bill.pi !== value) onChange('');
          }}
        />
        <RightOutlined className="sn-muted" />
        <Select
          aria-label={t('generate.pickOrder')}
          placeholder={t('generate.pickOrder')}
          suffixIcon={null}
          allowClear
          value={billId || undefined}
          className="sn-generation-bill"
          loading={orders.isFetching}
          showSearch={{ filterOption: false, onSearch: setSearch }}
          onFocus={() => setSearch('')}
          options={optionRows.map((item) => ({ value: item.id, label: item.billNo, item }))}
          optionRender={({ data }) => (
            <div className="sn-generation-option">
              <b>{data.item.billNo}</b>
              <span className="sn-muted">
                {data.item.pi} · {data.item.orgName}
              </span>
              {quantities(
                data.item.quota,
                (BigInt(data.item.generatedQty) - BigInt(data.item.allocatedQty)).toString(),
              )}
            </div>
          )}
          popupRender={(menu) => (
            <>
              {menu}
              {orders.data && BigInt(orders.data.total) > BigInt(choices.length) && (
                <div className="sn-generation-option-footer sn-muted">
                  {t('generate.moreBills', { shown: choices.length, total: orders.data.total })}
                </div>
              )}
            </>
          )}
          onChange={(id: string | undefined) => {
            setAutoPickPi('');
            onChange(id ?? '');
          }}
        />
        <Checkbox checked={pending} onChange={(e) => setPending(e.target.checked)}>
          {t('generate.onlyPending')}
        </Checkbox>
        <span className="sn-muted sn-generation-snapshot">
          {t('generate.snapshotAt', { time: formatSnTime(meta.data?.syncedAt) })}{' '}
          <Link to="/orders">{t('generate.toOrders')}</Link>
        </span>
      </div>
      <ApiErrorAlert error={customers.error ?? pis.error ?? orders.error ?? meta.error} />
    </section>
  );
}
