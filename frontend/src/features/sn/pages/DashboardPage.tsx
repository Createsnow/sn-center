import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Space, Statistic, Table, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { UserProfile } from '../../../api/contract';
import { snQueryApi } from '../../../api/snQuery';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';

/** 汇总由服务器按当前权限装配；没有全局资格时不显示全局总量或后台任务。 */
export function DashboardPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const result = useQuery({
    queryKey: ['sn', 'dashboard', profile.id, profile.authorizationVersion],
    queryFn: snQueryApi.dashboard,
    refetchInterval: 30000,
  });
  const data = result.data;
  const quantities = new Map(data?.states.map((s) => [s.name, s.quantity]));
  const shortcuts = [
    ['sn:generation:view', '/generate', 'menu.generate'],
    ['sn:acquire:view', '/acquire', 'menu.acquire'],
    ['sn:transfer:view', '/transfer', 'menu.transfer'],
    ['sn:query:view', '/query', 'menu.query'],
    ['sn:order:list', '/orders', 'menu.orders'],
    ['sn:rule:list', '/rules', 'menu.rules'],
  ];
  return (
    <div className="sn-page">
      <SnPageHead
        title={t('snDashboard.title')}
        description={t('welcome', { name: profile.displayName })}
      >
        <Space wrap>
          <span>{data && `${data.date} · ${data.zone}`}</span>
          <Button
            onClick={() => {
              void result.refetch();
            }}
            loading={result.isFetching}
          >
            {t('snDashboard.refresh')}
          </Button>
        </Space>
      </SnPageHead>
      <ApiErrorAlert error={result.error} />
      {data && (
        <>
          <Typography.Paragraph>
            {t(data.allFactories ? 'snDashboard.allFactories' : 'snDashboard.ownFactories')}
          </Typography.Paragraph>
          <div className="sn-dashboard-grid">
            {['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'APPLYING']
              .filter((s) => s !== 'PENDING_ALLOC' || data.allFactories)
              .map((status) => (
                <Card key={status}>
                  <Statistic
                    title={t('snAcquire.status.' + status)}
                    value={quantities.get(status) ?? '0'}
                    formatter={() => quantities.get(status) ?? '0'}
                  />
                </Card>
              ))}
            {data.pendingTransfers !== null && (
              <Card>
                <Statistic
                  title={t('snDashboard.pendingTransfers')}
                  value={data.pendingTransfers}
                  formatter={() => data.pendingTransfers}
                />
              </Card>
            )}
            {data.totalGenerated !== null && (
              <Card>
                <Statistic
                  title={t('snDashboard.totalGenerated')}
                  value={data.totalGenerated}
                  formatter={() => data.totalGenerated}
                />
              </Card>
            )}
            {data.totalImported !== null && (
              <Card>
                <Statistic
                  title={t('snDashboard.totalImported')}
                  value={data.totalImported}
                  formatter={() => data.totalImported}
                />
              </Card>
            )}
          </div>
          {data.pendingTransfers !== null && data.pendingTransfers !== '0' && (
            <Alert
              type="warning"
              showIcon
              title={t('snDashboard.pending', { n: data.pendingTransfers })}
              action={<Link to="/transfer">{t('snTransfer.records')}</Link>}
            />
          )}
          <div className="sn-form-grid">
            <div className="sn-section">
              <Typography.Title level={4}>{t('snDashboard.today')}</Typography.Title>
              <Table
                rowKey="name"
                data-testid="dashboard-today"
                dataSource={data.today}
                pagination={false}
                columns={[
                  {
                    title: t('snPages.common.actions'),
                    dataIndex: 'name',
                    render: (v: string) => t('snDashboard.actions.' + v),
                  },
                  { title: t('snPages.common.qty'), dataIndex: 'quantity' },
                ]}
              />
            </div>
            <div className="sn-section">
              <Typography.Title level={4}>{t('snDashboard.shortcuts')}</Typography.Title>
              <Space wrap>
                {shortcuts
                  .filter(([permission]) => profile.permissions.includes(permission!))
                  .map(([, path, label]) => (
                    <Link key={path} to={path!}>
                      <Button>{t(label!)}</Button>
                    </Link>
                  ))}
              </Space>
            </div>
          </div>
          {data.recentJobs.length > 0 && (
            <div className="sn-section">
              <Typography.Title level={4}>{t('snDashboard.recentJobs')}</Typography.Title>
              <Table
                rowKey="taskId"
                data-testid="dashboard-jobs"
                dataSource={data.recentJobs}
                pagination={false}
                scroll={{ x: 1100 }}
                columns={[
                  { title: t('snQuery.exportId'), dataIndex: 'taskId' },
                  { title: 'PI', dataIndex: 'pi' },
                  { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
                  { title: t('snPages.common.qty'), dataIndex: 'quantity' },
                  {
                    title: t('snPages.common.status'),
                    dataIndex: 'state',
                    render: (v: string) => t('snDashboard.states.' + v),
                  },
                  {
                    title: t('snPages.common.time'),
                    dataIndex: 'createdAt',
                    render: (v: string) => new Date(v!).toLocaleString(),
                  },
                ]}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
