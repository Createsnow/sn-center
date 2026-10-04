import { ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Descriptions, Drawer, Empty, Input, Table, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type UserProfile } from '../../../api/contract';
import { snApi, paginationTotal, type Order, type SnFilter } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { OrderSyncSchedule } from '../components/OrderSyncSchedule';

function SyncHistory({ profile, close }: { profile: UserProfile; close: () => void }) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const list = useQuery({
    queryKey: ['sn', 'syncs', profile.id, profile.authorizationVersion, page],
    queryFn: () => snApi.syncs({ page, size: 20 }),
    refetchInterval: (query) =>
      query.state.data?.items.some((row) => row.state === 'FETCHING') ? 3000 : false,
  });
  return (
    <Drawer open width={850} title={t('snPages.syncHistory')} onClose={close}>
      <ApiErrorAlert error={list.error} />
      <Table
        rowKey="id"
        size="small"
        scroll={{ x: 650 }}
        loading={list.isFetching}
        dataSource={list.data?.items ?? []}
        columns={[
          {
            title: t('snPages.common.time'),
            render: (_, row) => new Date(row.startedAt).toLocaleString(i18n.language),
          },
          {
            title: t('snPages.common.billNo'),
            render: (_, row) => row.billNo || t('snPages.orders.syncAll'),
          },
          {
            title: t('snPages.syncState'),
            render: (_, row) => (
              <Tag
                color={
                  row.state === 'SUCCESS'
                    ? 'success'
                    : row.state === 'FAILED'
                      ? 'error'
                      : 'processing'
                }
              >
                {t(`snPages.syncStates.${row.state}`)}
              </Tag>
            ),
          },
          { title: t('snPages.orders.bills'), dataIndex: 'billsCount' },
          { title: t('snPages.orders.rows'), dataIndex: 'rowsCount' },
          { title: t('snPages.syncVersion'), render: (_, row) => row.publishedVersion ?? '—' },
          {
            title: t('snPages.syncFailure'),
            render: (_, row) => (row.errorCode === null ? '—' : t(`errors.${row.errorCode}`)),
          },
        ]}
        pagination={{
          current: page,
          pageSize: 20,
          total: paginationTotal(list.data?.total),
          onChange: setPage,
        }}
      />
    </Drawer>
  );
}

/** 延续旧订单页左单据/右分录、同步工具栏和未建档提示；全部读取按同一工厂权限。 */
export function OrdersPage({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const cache = useQueryClient();
  const [filter, setFilter] = useState<SnFilter>({ page: 1, size: 20 });
  const [draft, setDraft] = useState<SnFilter>({});
  const [selected, setSelected] = useState<Order>();
  const [linePage, setLinePage] = useState(1);
  const [billNo, setBillNo] = useState('');
  const [history, setHistory] = useState(false);
  const key = [profile.id, profile.authorizationVersion];
  const list = useQuery({
    queryKey: ['sn', 'orders', ...key, filter],
    queryFn: () => snApi.orders(filter),
  });
  const meta = useQuery({ queryKey: ['sn', 'order-meta', ...key], queryFn: snApi.meta });
  const lines = useQuery({
    queryKey: ['sn', 'order-lines', ...key, selected?.id, selected?.snapshotVersion, linePage],
    queryFn: () => snApi.lines(selected?.id ?? '', { page: linePage, size: 20 }),
    enabled: !!selected,
  });
  const sync = useMutation({
    mutationFn: snApi.syncOrders,
    retry: false,
    onSuccess: async () => {
      setSelected(undefined);
      setLinePage(1);
      setFilter((current) => ({ ...current, page: 1 }));
      await cache.invalidateQueries({ queryKey: ['sn'] });
    },
    onError: async () => {
      await cache.invalidateQueries({ queryKey: ['sn', 'syncs'] });
    },
  });
  const apply = () => {
    setFilter({ ...draft, page: 1, size: filter.size });
    setSelected(undefined);
    setLinePage(1);
  };
  const canSync = profile.permissions.includes('sn:order:sync');
  return (
    <section className="sn-page">
      <SnPageHead title={t('snPages.orders.title')} description={t('snPages.orders.desc')}>
        {canSync && (
          <>
            <Input
              disabled={sync.isPending}
              className="sn-sync-bill"
              aria-label={t('snPages.orders.billToSync')}
              placeholder={t('snPages.orders.billToSync')}
              value={billNo}
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
              icon={<ReloadOutlined aria-hidden />}
              disabled={sync.isPending}
              loading={sync.isPending && sync.variables === undefined}
              onClick={() => sync.mutate(undefined)}
            >
              {t('snPages.orders.syncAll')}
            </Button>
            <Button onClick={() => setHistory(true)}>{t('snPages.syncHistory')}</Button>
          </>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error ?? meta.error ?? sync.error} />
      {sync.isPending && <Alert type="info" showIcon title={t('snPages.orders.syncing')} />}
      {sync.isError &&
        (!(sync.error instanceof ApiError) ||
          sync.error.status === 0 ||
          sync.error.code === 1_000_000_002) && (
          <Alert type="warning" title={t('snPages.syncUnknown')} />
        )}
      {sync.isSuccess && sync.data && (
        <Alert
          type="success"
          title={
            sync.data.mode === 'one' && sync.data.rowsCount === '0'
              ? t('snPages.orders.syncedRemoved', { bill: sync.data.billNo })
              : t('snPages.orders.syncedAll', {
                  bills: sync.data.billsCount,
                  rows: sync.data.rowsCount,
                })
          }
        />
      )}
      {sync.isSuccess && sync.data?.orgSyncFailed && (
        <Alert type="warning" title={t('snPages.organizationSyncFailed')} />
      )}
      {!!meta.data?.unmappedOrganizations.length && (
        <Alert
          type="warning"
          title={t('snPages.orders.unmapped', { orgs: meta.data.unmappedOrganizations.join('、') })}
        />
      )}
      <div className="sn-surface sn-order-meta">
        <span>
          {t('snPages.orders.syncedAt')}：
          <b>
            {meta.data?.syncedAt ? new Date(meta.data.syncedAt).toLocaleString(i18n.language) : '—'}
          </b>
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
      </div>
      {canSync && <OrderSyncSchedule profile={profile} />}
      <div className="sn-orders-grid">
        <div className="sn-surface">
          <div className="sn-toolbar">
            <Input
              aria-label={t('snPages.orders.search')}
              placeholder={t('snPages.orders.search')}
              allowClear
              value={draft.q ?? ''}
              onChange={(event) => setDraft({ ...draft, q: event.target.value })}
              onPressEnter={apply}
            />
            <Input
              aria-label={t('snPages.common.customer')}
              placeholder={t('snPages.common.customer')}
              allowClear
              value={draft.customer ?? ''}
              onChange={(event) => setDraft({ ...draft, customer: event.target.value })}
              onPressEnter={apply}
            />
            <Input
              aria-label="PI"
              placeholder="PI"
              allowClear
              value={draft.pi ?? ''}
              onChange={(event) => setDraft({ ...draft, pi: event.target.value })}
              onPressEnter={apply}
            />
            <FactoryPicker
              profile={profile}
              value={draft.factoryId}
              onChange={(factoryId) => setDraft({ ...draft, factoryId })}
            />
            <Button type="primary" onClick={apply}>
              {t('snPages.common.search')}
            </Button>
            <Checkbox
              checked={draft.pending ?? false}
              onChange={(event) => setDraft({ ...draft, pending: event.target.checked })}
            >
              {t('snPages.orders.pendingOnly')}
            </Checkbox>
          </div>
          <Table
            rowKey="id"
            size="small"
            data-testid="orders-table"
            loading={list.isFetching}
            dataSource={list.data?.items ?? []}
            scroll={{ x: 1000, y: 560 }}
            rowClassName={(row) => (row.id === selected?.id ? 'sn-selected-row' : '')}
            onRow={(row) => ({
              onClick: () => {
                setSelected(row);
                setLinePage(1);
              },
            })}
            columns={[
              {
                title: t('snPages.common.billNo'),
                dataIndex: 'billNo',
                width: 140,
                render: (value: string, row) => (
                  <Button
                    type="link"
                    onClick={() => {
                      setSelected(row);
                      setLinePage(1);
                    }}
                  >
                    {value}
                  </Button>
                ),
              },
              { title: t('snPages.common.customer'), dataIndex: 'customer', width: 100 },
              { title: 'PI', dataIndex: 'pi', width: 140 },
              {
                title: t('snPages.orders.org'),
                width: 150,
                render: (_, row) => (
                  <>
                    {row.orgName}
                    {!row.factoryId && <Tag color="error">{t('snPages.orders.noFactory')}</Tag>}
                  </>
                ),
              },
              {
                title: t('snPages.orders.status'),
                width: 130,
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
                width: 110,
                render: (_, row) => (
                  <>
                    {row.totalQty}
                    {!row.quantityReady && (
                      <Tag color="warning">{t('snPages.orders.fractionalQty')}</Tag>
                    )}
                  </>
                ),
              },
              {
                title: t('snPages.orders.generated'),
                dataIndex: 'generatedQty',
                align: 'right',
                width: 110,
              },
              { title: t('snPages.orders.quota'), dataIndex: 'quota', align: 'right', width: 110 },
            ]}
            pagination={{
              current: filter.page,
              pageSize: filter.size,
              total: paginationTotal(list.data?.total),
              showSizeChanger: true,
              pageSizeOptions: [20, 50, 100],
              onChange: (page, size) => {
                setFilter({ ...filter, page, size });
                setSelected(undefined);
              },
            }}
          />
        </div>
        <div className="sn-surface sn-order-lines">
          <div className="sn-surface-title">
            <b>
              {selected
                ? t('snPages.orders.linesOf', { bill: selected.billNo })
                : t('snPages.orders.lines')}
            </b>
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
                dataSource={lines.data?.items ?? []}
                scroll={{ x: 420 }}
                columns={[
                  { title: '#', dataIndex: 'ordinal', width: 48 },
                  {
                    title: t('snPages.common.material'),
                    render: (_, row) =>
                      row.material || (
                        <span className="sn-muted">{t('snPages.orders.noMaterial')}</span>
                      ),
                    width: 105,
                  },
                  {
                    title: t('snPages.orders.materialName'),
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
                  { title: t('snPages.common.qty'), dataIndex: 'qty', align: 'right', width: 80 },
                ]}
                pagination={{
                  current: linePage,
                  pageSize: 20,
                  total: paginationTotal(lines.data?.total),
                  onChange: setLinePage,
                  showSizeChanger: false,
                }}
              />
            </>
          ) : (
            <Empty description={t('snPages.orders.pick')} />
          )}
        </div>
      </div>
      {history && <SyncHistory profile={profile} close={() => setHistory(false)} />}
    </section>
  );
}
