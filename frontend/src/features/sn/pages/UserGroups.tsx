import { CheckOutlined, UserOutlined } from '@ant-design/icons';
import { useQueries } from '@tanstack/react-query';
import { Button, Table, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { snApi } from '../../../api/sn';
import { PageHead } from '../components/PageHead';
import { roleLabel } from '../lib/labels';
import { MENU } from '../lib/menu';
import { usePage } from '../lib/page';
import { str } from '../lib/row';

const GROUPS = ['admin', 'operator', 'auditor', 'mes'];

/** 用户组（功能模块分权）：按固定角色列出各组能用的功能模块与账户数；只读，分权仍按账户的角色。 */
export default function UserGroups() {
  const { t } = useTranslation('sn');
  const { open } = usePage();
  const counts = useQueries({
    queries: GROUPS.map((g) => ({
      queryKey: ['sn', 'users', 'count', g],
      queryFn: () =>
        snApi.users({
          ...(g === 'mes' ? { kind: 'mes' } : { kind: 'page', role: g }),
          status: 'ACTIVE',
          page: 1,
          pageSize: 1,
        }),
    })),
  });
  const data = MENU.filter((g) => g.group !== 'nav.groupWork').flatMap((g) =>
    g.items.map((m, i) => ({ ...m, group: g.group, first: i === 0, span: g.items.length })),
  );
  return (
    <div className="page">
      <PageHead title={t('groups.title')} desc={t('groups.desc')}>
        <Button icon={<UserOutlined />} onClick={() => open('/sn/users')}>
          {t('groups.toUsers')}
        </Button>
      </PageHead>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 16 }}>
        {GROUPS.map((g, i) => (
          <div key={g} className="surface" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
              <b>{roleLabel(g)}</b>
              <span className="muted num">
                {t('groups.accounts', { n: counts[i]?.data ? str(counts[i]?.data?.total) : '—' })}
              </span>
            </div>
            <p className="muted" style={{ margin: 0, lineHeight: 1.6 }}>
              {t(`users.roleHint.${g}`)}
            </p>
          </div>
        ))}
      </div>
      <div className="surface">
        <div className="surface__title">
          <span>{t('groups.matrix')}</span>
        </div>
        <Table
          size="small"
          bordered
          pagination={false}
          rowKey="path"
          dataSource={data}
          columns={[
            {
              title: t('groups.module'),
              width: 140,
              onCell: (r) => ({ rowSpan: r.first ? r.span : 0 }),
              render: (_, r) => <b>{t(r.group)}</b>,
            },
            {
              title: t('groups.function'),
              render: (_, r) => (
                <>
                  {t(r.title)}
                  {r.soon ? <Tag style={{ marginLeft: 6 }}>{t('nav.soon')}</Tag> : null}
                </>
              ),
            },
            ...GROUPS.map((g) => ({
              key: g,
              title: roleLabel(g),
              align: 'center' as const,
              render: (_: unknown, r: (typeof data)[number]) =>
                r.roles.includes(g) ? (
                  <CheckOutlined style={{ color: '#3f7d5a', fontSize: 16 }} />
                ) : (
                  <span className="muted">—</span>
                ),
            })),
          ]}
        />
        <p className="muted" style={{ margin: '12px 0 0' }}>
          {t('groups.note')}
        </p>
      </div>
    </div>
  );
}
