import { SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Input, Table, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { type UserProfile } from '../../../api/contract';
import { snApi, paginationTotal, type Order, type SnFilter } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { OrderSyncSchedule } from '../components/OrderSyncSchedule';
import { PrototypeEmpty } from '../../../components/prototype/PrototypeEmpty';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { useSnContext } from '../useSnContext';
import { useSyncFeedback } from '../useSyncFeedback';

/** 延续旧订单页左单据/右分录、同步工具栏和未建档提示；全部读取按同一工厂权限。 */
export function OrdersPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const context = useSnContext(profile);
  const [filter, setFilter] = useState<SnFilter>({ page: 1, size: 20 });
  const [draft, setDraft] = useState<SnFilter>({});
  const [selected, setSelected] = useState<Order>();
  const [billNo, setBillNo] = useState('');
  const { feedback, holder: feedbackHolder, error: showSyncError } = useSyncFeedback();
  const key = [profile.id, profile.authorizationVersion];
  const list = useQuery({
    queryKey: ['sn', 'orders', ...key, filter],
    queryFn: () => snApi.orders(filter),
  });
  const meta = useQuery({ queryKey: ['sn', 'order-meta', ...key], queryFn: snApi.meta });
  const lines = useQuery({
    queryKey: ['sn', 'order-lines', ...key, selected?.id, selected?.snapshotVersion],
    queryFn: ({ signal }) => snApi.allLines(selected?.id ?? '', signal),
    enabled: !!selected,
  });
  const sync = useMutation({
    mutationFn: snApi.syncOrders,
    retry: false,
    onSuccess: async (result) => {
      void feedback.success({
        className: 'sn-prototype-success-message',
        content:
          result.mode === 'one'
            ? result.rowsCount === '0'
              ? t('prototype:orders.syncedRemoved', { bill: result.billNo })
              : t('prototype:orders.syncedOne', { bill: result.billNo, rows: result.rowsCount })
            : t('prototype:orders.syncedAll', { bills: result.billsCount, rows: result.rowsCount }),
      });
      setSelected(undefined);
      setFilter((current) => ({ ...current, page: 1 }));
      await cache.invalidateQueries({ queryKey: ['sn'] });
    },
    onError: async (error) => {
      showSyncError(error);
      await cache.invalidateQueries({ queryKey: ['sn', 'syncs'] });
    },
  });
  const apply = (next = draft) => {
    setFilter({ ...next, page: 1, size: filter.size });
    setSelected(undefined);
  };
  const canSync = profile.permissions.includes('sn:order:sync');
  return (
    <section className="sn-page sn-orders-page">
      {feedbackHolder}
      <SnPageHead title={t('snPages.orders.title')} description={t('snPages.orders.desc')}>
        {canSync && (
          <>
            <Input
              disabled={sync.isPending}
              className="sn-sync-bill"
              aria-label={t('snPages.orders.billToSync')}
              placeholder={t('snPages.orders.billToSync')}
              value={billNo}
              allowClear
              onChange={(event) => setBillNo(event.target.value)}
              onPressEnter={() => {
                if (billNo.trim() && !sync.isPending) sync.mutate(billNo.trim());
              }}
            />
            <Button
              aria-label={t('snPages.orders.syncOne')}
              aria-busy={sync.isPending && sync.variables !== undefined}
              disabled={!billNo.trim() || sync.isPending}
              loading={sync.isPending && sync.variables !== undefined}
              onClick={() => sync.mutate(billNo.trim())}
            >
              {t('snPages.orders.syncOne')}
            </Button>
            <Button
              aria-label={t('snPages.orders.syncAll')}
              aria-busy={sync.isPending && sync.variables === undefined}
              type="primary"
              icon={<PrototypeIcon name="Refresh" />}
              disabled={sync.isPending}
              loading={sync.isPending && sync.variables === undefined}
              onClick={() => sync.mutate(undefined)}
            >
              {t('snPages.orders.syncAll')}
            </Button>
          </>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error ?? meta.error} />
      {!!meta.data?.unmappedOrganizations.length && (
        <Alert
          className="sn-order-unmapped"
          type="warning"
          showIcon
          icon={
            <span>
              <PrototypeIcon name="WarningFilled" />
            </span>
          }
          title={t('snPages.orders.unmapped', { orgs: meta.data.unmappedOrganizations.join('、') })}
        />
      )}
      <div className="sn-surface sn-order-meta">
        <span>
          {t('snPages.orders.syncedAt')}：<b>{formatSnTime(meta.data?.syncedAt)}</b>
        </span>
        <span>
          {t('snPages.orders.bills')}：<b>{meta.data?.bills ?? '0'}</b>
        </span>
        <span>
          {t('snPages.orders.rows')}：<b>{meta.data?.rows ?? '0'}</b>
        </span>
        <span className="sn-muted">
          {t('snPages.orders.excluded', { n: meta.data?.excludedEmptyCustomer ?? '0' })}
        </span>
        <OrderSyncSchedule profile={profile} />
      </div>
      <div className="sn-orders-grid">
        <div className="sn-surface">
          <div className="sn-toolbar">
            <Input
              aria-label={t('prototype:orders.search')}
              placeholder={t('prototype:orders.search')}
              allowClear
              value={draft.q ?? ''}
              prefix={<SearchOutlined />}
              className="sn-order-search"
              onChange={(event) => {
                const next = { ...draft, q: event.target.value };
                setDraft(next);
                if (!event.target.value) apply(next);
              }}
              onPressEnter={() => apply()}
            />
            <Input
              aria-label={t('snPages.common.customer')}
              placeholder={t('snPages.common.customer')}
              allowClear
              value={draft.customer ?? ''}
              onChange={(event) => {
                const next = { ...draft, customer: event.target.value };
                setDraft(next);
                if (!event.target.value) apply(next);
              }}
              onPressEnter={() => apply()}
            />
            {context.data?.allFactories && (
              <FactoryPicker
                profile={profile}
                value={draft.factoryId}
                onChange={(factoryId) => {
                  const next = { ...draft, factoryId };
                  setDraft(next);
                  apply(next);
                }}
              />
            )}
            <Button type="primary" onClick={() => apply()}>
              {t('snPages.common.search')}
            </Button>
          </div>
          <Table
            rowKey="id"
            size="small"
            data-testid="orders-table"
            loading={list.isFetching}
            dataSource={list.data?.items ?? []}
            scroll={{ x: 740, y: 527 }}
            className="sn-order-table"
            rowClassName={(row) => (row.id === selected?.id ? 'sn-selected-row' : '')}
            onRow={(row) => ({
              onClick: () => {
                setSelected(row);
              },
            })}
            columns={[
              {
                title: t('snPages.common.billNo'),
                dataIndex: 'billNo',
                width: 120,
                ellipsis: true,
              },
              {
                title: t('snPages.common.customer'),
                dataIndex: 'customer',
                width: 80,
                ellipsis: true,
              },
              { title: 'PI', dataIndex: 'pi', width: 140, ellipsis: true },
              {
                title: t('snPages.orders.org'),
                width: 100,
                ellipsis: true,
                render: (_, row) => (
                  <>
                    {row.orgName}
                    {!row.factoryId && <Tag color="error">{t('snPages.orders.noFactory')}</Tag>}
                  </>
                ),
              },
              {
                title: t('snPages.orders.status'),
                width: 84,
                ellipsis: true,
                render: (_, row) =>
                  row.statuses
                    .split(',')
                    .map((status) => t(`snPages.orders.st${status}`))
                    .join(' / '),
              },
              {
                title: t('snPages.orders.totalQty'),
                dataIndex: 'totalQty',
                align: 'right',
                width: 76,
              },
              {
                title: t('snPages.orders.generated'),
                dataIndex: 'generatedQty',
                align: 'right',
                width: 70,
              },
              {
                title: t('snPages.orders.quota'),
                dataIndex: 'quota',
                align: 'right',
                width: 70,
                render: (quota: string) => (
                  <span className={quota === '0' ? 'sn-muted' : undefined}>{quota}</span>
                ),
              },
            ]}
            pagination={{
              current: filter.page,
              pageSize: filter.size,
              total: paginationTotal(list.data?.total),
              showSizeChanger: true,
              showTotal: (total) => t('shell.paginationTotal', { total }),
              pageSizeOptions: [20, 50, 100],
              onChange: (page, size) => {
                setFilter({ ...filter, page: size === filter.size ? page : 1, size });
                setSelected(undefined);
              },
            }}
          />
        </div>
        <div className="sn-surface sn-order-lines">
          <div className="sn-surface-title">
            <span>
              {selected
                ? t('snPages.orders.linesOf', { bill: selected.billNo })
                : t('snPages.orders.lines')}
            </span>
            {selected && profile.permissions.includes('sn:generation:view') && (
              <Link to={'/generate?billId=' + encodeURIComponent(selected.id)}>
                <Button type="primary" size="small">
                  {t('snPages.orders.toGenerate')}
                </Button>
              </Link>
            )}
          </div>
          {selected ? (
            <>
              <Descriptions
                column={1}
                size="small"
                bordered
                items={[
                  { key: 'pi', label: 'PI', children: selected.pi },
                  { key: 'po', label: 'PO', children: selected.po },
                  {
                    key: 'customer',
                    label: t('snPages.common.customer'),
                    children: selected.customer,
                  },
                  {
                    key: 'factory',
                    label: t('snPages.common.factory'),
                    children: `${selected.orgName}（${selected.factoryCode ?? '—'}）`,
                  },
                ]}
              />
              <ApiErrorAlert error={lines.error} />
              <Table
                rowKey="id"
                size="small"
                data-testid="order-lines"
                loading={lines.isFetching}
                dataSource={lines.data ?? []}
                scroll={{ x: 338 }}
                columns={[
                  { title: '#', dataIndex: 'ordinal', width: 48 },
                  {
                    title: t('snPages.common.material'),
                    render: (_, row) =>
                      row.material || (
                        <span className="sn-muted">{t('snPages.orders.noMaterial')}</span>
                      ),
                    width: 100,
                  },
                  {
                    title: t('snPages.orders.materialName'),
                    width: 120,
                    ellipsis: true,
                    render: (_, row) => (
                      <>
                        {row.materialName}
                        {row.demandBillNo && (
                          <div className="sn-muted">
                            {t('snPages.orders.demand')} {row.demandBillNo}
                          </div>
                        )}
                      </>
                    ),
                  },
                  {
                    title: t('snPages.common.qty'),
                    dataIndex: 'qty',
                    align: 'right',
                    width: 70,
                    render: (qty: string) => qty.replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1'),
                  },
                ]}
                pagination={false}
              />
            </>
          ) : (
            <PrototypeEmpty description={t('snPages.orders.pick')} />
          )}
        </div>
      </div>
    </section>
  );
}
