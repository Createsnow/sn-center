import { useQuery } from '@tanstack/react-query';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Progress, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import type { UserProfile } from '../../../api/contract';
import { snQueryApi, type DashboardJob } from '../../../api/snQuery';
import { snNavigation } from '../../../app/snNavigation';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { useSnContext } from '../useSnContext';
import { SnPageHead } from '../components/SnPageHead';

const actionCodes: Record<string, string> = {
  GENERATE: 'SN_GENERATE',
  ALLOCATE: 'SN_ALLOCATE',
  TAKE: 'SN_ACQUIRE',
  PRINT: 'SN_PRINT',
  CALLBACK: 'SN_CALLBACK',
};
const formatCount = (value: string | null | undefined) => BigInt(value ?? '0').toLocaleString();
// 先用整数计算百分比，再转换 0..100，避免将原始流水数量转换成不精确的 JS number。
function percentage(job: DashboardJob) {
  const total = BigInt(job.quantity);
  if (total === 0n) return 0;
  const done = BigInt(job.processed);
  return Number(((done > total ? total : done) * 200n + total) / (2n * total));
}

/** 原型首页顺序与交互；统计和角色范围由服务器按当前授权装配。 */
export function DashboardPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const navigate = useNavigate();
  const context = useSnContext(profile);
  const result = useQuery({
    queryKey: ['sn', 'dashboard', profile.id, profile.authorizationVersion],
    queryFn: snQueryApi.dashboard,
    refetchInterval: 30000,
  });
  const data = result.data;
  const admin = context.data?.role === 'admin';
  const quantities = new Map(data?.states.map((s) => [s.name, s.quantity]));
  const kpis = [
    ...(admin
      ? [
          {
            key: 'PENDING_ALLOC',
            label: t('status.PENDING_ALLOC'),
            value: quantities.get('PENDING_ALLOC'),
          },
        ]
      : []),
    ...['TO_ACQUIRE', 'TO_PRINT', 'APPLYING'].map((key) => ({
      key,
      label: t('status.' + key),
      value: quantities.get(key),
    })),
    { key: 'transfers', label: t('dashboard.pendingTransferKpi'), value: data?.pendingTransfers },
    ...(context.data?.allFactories
      ? [
          {
            key: 'total',
            label: t('dashboard.totalGenerated'),
            value: (
              BigInt(data?.totalGenerated ?? '0') + BigInt(data?.totalImported ?? '0')
            ).toString(),
          },
        ]
      : []),
  ];
  const shortcuts = snNavigation(context.data, profile.permissions)
    .flatMap((group) => group.items)
    .filter((item) => item.path !== '/')
    .slice(0, 6);
  const today = [...(data?.today ?? [])].sort((a, b) =>
    (actionCodes[a.name] ?? a.name).localeCompare(actionCodes[b.name] ?? b.name),
  );
  return (
    <section className="sn-page sn-dashboard-page">
      <SnPageHead
        title={t('dashboard.title', { name: profile.displayName })}
        description={
          context.data
            ? context.data.allFactories
              ? t('dashboard.scopeAll')
              : t('dashboard.scopeFactory', {
                  f: context.data.factories.map((f) => f.name || f.code).join('、'),
                })
            : ''
        }
      />
      <ApiErrorAlert error={result.error ?? context.error} />
      <div className="sn-dashboard-kpis">
        {kpis.map((kpi) => (
          <div className="sn-dashboard-kpi" key={kpi.key}>
            <div className="value">{formatCount(kpi.value)}</div>
            <div className="label">{kpi.label}</div>
          </div>
        ))}
      </div>
      <div className="sn-dashboard-panels">
        <div className="sn-surface">
          <div className="sn-surface-title">{t('dashboard.today')}</div>
          <Table
            size="small"
            rowKey="name"
            data-testid="dashboard-today"
            dataSource={today}
            pagination={false}
            locale={{ emptyText: t('common.empty') }}
            columns={[
              {
                title: t('audit.action'),
                dataIndex: 'name',
                render: (v: string) => t('actions.' + (actionCodes[v] ?? v)),
              },
              {
                title: t('common.qty'),
                dataIndex: 'quantity',
                align: 'right',
                render: formatCount,
              },
            ]}
          />
        </div>
        <div className="sn-surface">
          <div className="sn-surface-title">{t('dashboard.shortcuts')}</div>
          <div className="sn-dashboard-shortcuts">
            {shortcuts.map((item) => (
              <Button
                key={item.path}
                icon={<PrototypeIcon name={item.icon} />}
                onClick={() => {
                  void navigate(item.path);
                }}
              >
                {t('nav.' + item.name)}
              </Button>
            ))}
          </div>
          {data?.pendingTransfers && data.pendingTransfers !== '0' && (
            <Alert
              className="sn-dashboard-warning"
              type="warning"
              showIcon
              title={t('dashboard.pendingTransfers', { n: data.pendingTransfers })}
            />
          )}
        </div>
      </div>
      {admin && (
        <div className="sn-surface">
          <div className="sn-surface-title">{t('dashboard.recentJobs')}</div>
          <Table
            size="small"
            rowKey="taskId"
            data-testid="dashboard-jobs"
            tableLayout="fixed"
            scroll={{ x: 700 }}
            dataSource={data?.jobs ?? []}
            pagination={false}
            locale={{ emptyText: t('common.empty') }}
            columns={[
              { title: '#', dataIndex: 'id', width: 70 },
              { title: t('common.billNo'), dataIndex: 'billNo' },
              { title: 'PI', dataIndex: 'pi' },
              { title: t('common.factory'), dataIndex: 'factoryCode' },
              {
                title: t('generate.progress'),
                width: 220,
                render: (_, row: DashboardJob) => (
                  <Progress
                    percent={percentage(row)}
                    format={(percent) =>
                      row.state === 'SUCCESS' ? (
                        <CheckCircleOutlined />
                      ) : row.state === 'FAILED' ? (
                        <CloseCircleOutlined />
                      ) : (
                        `${percent}%`
                      )
                    }
                    size="small"
                    status={
                      row.state === 'SUCCESS'
                        ? 'success'
                        : row.state === 'FAILED'
                          ? 'exception'
                          : 'normal'
                    }
                  />
                ),
              },
              { title: t('common.time'), dataIndex: 'createdAt', width: 170, render: formatSnTime },
            ]}
          />
        </div>
      )}
    </section>
  );
}
