import { BarcodeOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Progress, Table, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import {
  BATCH_RESULT_COLOR,
  batchResultLabel,
  batchSourceLabel,
  batchTypeLabel,
  statusLabel,
} from '../lib/labels';
import { MENU } from '../lib/menu';
import { usePage } from '../lib/page';
import { fmtNum, num, obj, rows, str } from '../lib/row';
import { tzLabel } from '../lib/time';
import { ICONS } from '../shell/icons';

const TODAY_KEYS = ['SN_GENERATE', 'SN_ALLOCATE', 'CONFIRM_RECEIPT', 'CONFIRM_MANUAL', 'SN_PULL', 'TRANSFER_BILL'];

interface Kpi {
  key: string;
  label: string;
  value: unknown;
  to?: string;
  alarm?: boolean;
}

export default function Dashboard() {
  const { t, i18n } = useTranslation('sn');
  const account = useAccount();
  const { open } = usePage();
  const q = useQuery({ queryKey: ['sn', 'dashboard'], queryFn: () => snApi.dashboard() });
  const d: Row = q.data ?? {};
  const c = obj(d.statusCounts);
  const alerts = Object.values(obj(d.deliveryAlerts)).reduce<number>((a, b) => a + num(b), 0);

  let kpis: Kpi[] = [];
  if ('PENDING_ALLOC' in c) {
    kpis.push({ key: 'PENDING_ALLOC', label: statusLabel('PENDING_ALLOC'), value: c.PENDING_ALLOC, to: '/sn/allocate' });
  }
  kpis.push(
    { key: 'TO_PUSH', label: statusLabel('TO_PUSH'), value: c.TO_PUSH },
    { key: 'ALLOCATED', label: statusLabel('ALLOCATED'), value: c.ALLOCATED, to: '/sn/allocate' },
    { key: 'suspended', label: t('dashboard.suspended'), value: d.suspended, alarm: true },
    { key: 'interrupted', label: t('dashboard.pushInterrupted'), value: d.pushInterrupted, alarm: true, to: '/sn/query?tab=delivery' },
    { key: 'pull', label: t('dashboard.pullUnconfirmed'), value: d.pullUnconfirmed, alarm: true, to: '/sn/query?tab=delivery' },
    { key: 'dalerts', label: t('dashboard.deliveryAlertKpi'), value: alerts, alarm: true, to: '/sn/query?tab=alerts' },
  );
  if (account.canAct) {
    kpis.push({ key: 'oalerts', label: t('dashboard.orderAlertKpi'), value: d.orderAlerts, alarm: true, to: '/sn/orders?tab=alerts' });
    kpis.push({ key: 'running', label: t('dashboard.runningJobs'), value: d.runningJobs, to: '/sn/generate' });
  }
  if (d.totalGenerated !== null && d.totalGenerated !== undefined) {
    kpis.push({ key: 'total', label: t('dashboard.totalGenerated'), value: d.totalGenerated });
  }
  if (!account.canAct) {
    // 审计员只有 SN 查询：已分配跳 SN 查询，其他跳转不给
    kpis = kpis.map((r) =>
      r.to?.startsWith('/sn/query') || r.key === 'ALLOCATED' ? { ...r, to: '/sn/query' } : { ...r, to: undefined },
    );
  }

  const today = obj(d.today);
  const todayRows = [...TODAY_KEYS, ...Object.keys(today).filter((k) => !TODAY_KEYS.includes(k))].map((k) => ({
    key: k,
    label: i18n.exists(`dashboard.todayItem.${k}`, { ns: 'sn' }) ? t(`dashboard.todayItem.${k}`) : k,
    qty: today[k] ?? 0,
  }));
  const shortcuts = MENU.flatMap((g) => g.items)
    .filter((m) => m.path !== '/sn' && m.roles.includes(account.role) && (!m.superAdmin || account.isSuperAdmin))
    .slice(0, 6);
  const openBatch = (batchNo: string) => open(`/sn/query?tab=delivery&batchNo=${encodeURIComponent(batchNo)}`);

  return (
    <div className="page">
      <PageHead
        title={t('dashboard.title', { name: str(account.profile?.name) })}
        desc={account.bound ? t('dashboard.scopeFactory', { f: account.scopeNames }) : t('dashboard.scopeAll')}
      >
        <Button icon={<ReloadOutlined />} loading={q.isFetching} onClick={() => void q.refetch()}>
          {t('common.refresh')}
        </Button>
      </PageHead>
      <div className="surface sn-hero-card">
        <BarcodeOutlined style={{ fontSize: 28, color: '#93c5fd', marginTop: 2 }} />
        <div>
          <div className="name">{t('app.title')}</div>
          <p className="desc">{t('app.desc')}</p>
        </div>
      </div>
      <div className="kpi-grid">
        {kpis.map((k) => (
          <div
            key={k.key}
            className={`kpi${k.to ? ' link' : ''}${k.alarm && num(k.value) > 0 ? ' alarm' : ''}`}
            onClick={() => k.to && open(k.to)}
          >
            <div className="v">{fmtNum(k.value)}</div>
            <div className="l">{k.label}</div>
          </div>
        ))}
      </div>
      <p className="muted" style={{ margin: '-8px 0 0' }}>
        {t('dashboard.delayNote')}
      </p>
      <div className="sn-grid2">
        <div className="surface">
          <div className="surface__title">
            <span>{t('dashboard.today')}</span>
            <span className="muted">
              {t('dashboard.todaySince', {
                time: account.fmt(d.todayStart, false),
                tz: tzLabel(d.tzOffsetMin === undefined ? undefined : num(d.tzOffsetMin)),
              })}
            </span>
          </div>
          <Table
            size="small"
            rowKey="key"
            pagination={false}
            dataSource={todayRows}
            columns={[
              { title: t('dashboard.item'), dataIndex: 'label' },
              {
                title: t('common.qty'),
                align: 'right',
                width: 120,
                render: (_: unknown, r) => <span className="num">{fmtNum(r.qty)}</span>,
              },
            ]}
          />
        </div>
        <div className="surface">
          <div className="surface__title">{t('dashboard.shortcuts')}</div>
          <div className="sn-shortcuts">
            {shortcuts.map((s) => (
              <Button key={s.path} icon={ICONS[s.icon]} onClick={() => open(s.path)}>
                {t(s.title)}
              </Button>
            ))}
          </div>
          {num(d.orderAlerts) > 0 ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 12 }}
              title={t('dashboard.orderAlerts', { n: str(d.orderAlerts) })}
            />
          ) : null}
        </div>
      </div>
      {account.canAct ? (
        <>
          <div className="surface">
            <div className="surface__title">
              <span>{t('dashboard.recentFailures')}</span>
              <Button type="link" onClick={() => open('/sn/query?tab=delivery')}>
                {t('dashboard.toBatches')}
              </Button>
            </div>
            <Table<Row>
              size="small"
              rowKey={(r) => str(r.batchNo)}
              pagination={false}
              dataSource={rows(d.recentFailures)}
              columns={[
                {
                  title: t('common.batchNo'),
                  render: (_, r) => (
                    <Button type="link" className="sn-mono" style={{ padding: 0 }} onClick={() => openBatch(str(r.batchNo))}>
                      {str(r.batchNo)}
                    </Button>
                  ),
                },
                {
                  title: t('dashboard.batchKind'),
                  width: 120,
                  render: (_, r) => `${batchSourceLabel(r.source)} · ${batchTypeLabel(r.batchType)}`,
                },
                { title: t('common.factory'), dataIndex: 'factoryCode', ellipsis: true },
                { title: 'PI', dataIndex: 'piNo', ellipsis: true },
                { title: t('common.qty'), align: 'right', width: 70, render: (_, r) => <span className="num">{str(r.qty)}</span> },
                {
                  title: t('common.status'),
                  width: 110,
                  render: (_, r) => (
                    <Tag color={BATCH_RESULT_COLOR[str(r.result)] ?? 'warning'}>{batchResultLabel(r.result)}</Tag>
                  ),
                },
                {
                  title: t('dashboard.failReason'),
                  ellipsis: true,
                  render: (_, r) =>
                    str(r.failReason) || (str(r.source) === 'PULL' ? t('dashboard.pullNoReceipt') : '—'),
                },
                {
                  title: t('common.time'),
                  width: 160,
                  render: (_, r) => account.fmt(r.lastPushAt || r.pulledAt || r.createdAt),
                },
              ]}
            />
          </div>
          <div className="surface">
            <div className="surface__title">
              <span>{t('dashboard.deliveryAlerts')}</span>
              <Button type="link" onClick={() => open('/sn/query?tab=alerts')}>
                {t('dashboard.toAlerts')}
              </Button>
            </div>
            <Table<Row>
              size="small"
              rowKey={(r) => str(r.id)}
              pagination={false}
              dataSource={rows(d.openDeliveryAlerts)}
              columns={[
                {
                  title: t('dashboard.alertType'),
                  width: 130,
                  render: (_, r) => <Tag color="error">{t(`dashboard.dalert.${str(r.alertType)}`)}</Tag>,
                },
                { title: t('common.factory'), dataIndex: 'factoryCode', ellipsis: true },
                {
                  title: t('common.batchNo'),
                  render: (_, r) =>
                    r.batchNo ? (
                      <Button type="link" className="sn-mono" style={{ padding: 0 }} onClick={() => openBatch(str(r.batchNo))}>
                        {str(r.batchNo)}
                      </Button>
                    ) : null,
                },
                { title: t('common.sn'), render: (_, r) => <span className="sn-mono">{str(r.sn) || '—'}</span> },
                { title: t('dashboard.alertDetail'), dataIndex: 'detail', ellipsis: true },
                { title: t('common.time'), width: 160, render: (_, r) => account.fmt(r.createdAt) },
              ]}
            />
          </div>
          <div className="surface">
            <div className="surface__title">{t('dashboard.recentJobs')}</div>
            <Table<Row>
              size="small"
              rowKey={(r) => str(r.genBatch)}
              pagination={false}
              dataSource={rows(d.recentJobs)}
              columns={[
                { title: t('dashboard.genBatch'), dataIndex: 'genBatch', ellipsis: true },
                {
                  title: 'PI',
                  render: (_, r) => (
                    <Button type="link" style={{ padding: 0 }} onClick={() => open(`/sn/generate?pi=${encodeURIComponent(str(r.piNo))}`)}>
                      {str(r.piNo)}
                    </Button>
                  ),
                },
                {
                  title: t('dashboard.progress'),
                  width: 220,
                  render: (_, r) => {
                    const s = str(r.status);
                    return (
                      <Progress
                        percent={num(r.qty) ? Math.round((num(r.doneQty) * 100) / num(r.qty)) : 0}
                        status={s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'exception' : s === 'RUNNING' ? 'active' : 'normal'}
                      />
                    );
                  },
                },
                { title: t('common.status'), width: 100, render: (_, r) => t(`dashboard.jobStatus.${str(r.status)}`) },
                { title: t('common.qty'), align: 'right', width: 80, render: (_, r) => <span className="num">{fmtNum(r.qty)}</span> },
                { title: t('common.operator'), dataIndex: 'createdBy', width: 100 },
                { title: t('common.time'), width: 160, render: (_, r) => account.fmt(r.createdAt) },
              ]}
            />
          </div>
        </>
      ) : null}
    </div>
  );
}
