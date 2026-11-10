import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Table } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import { rows, str } from '../lib/row';

/** 生产部门（只读）：订单快照里出现过的生产车间，按生产组织列出；部门主数据仍在金蝶维护。 */
export default function Departments() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const [factory, setFactory] = useState('');
  const [kw, setKw] = useState('');
  const q = useQuery({
    queryKey: ['sn', 'departments', factory],
    queryFn: () => snApi.orderDepartments({ factoryCode: factory }),
  });
  const k = kw.trim().toLowerCase();
  const shown = rows(q.data).filter(
    (r) =>
      !k ||
      `${str(r.deptCode)} ${str(r.deptName)} ${str(r.orgCode)} ${str(r.orgName)}`.toLowerCase().includes(k),
  );
  return (
    <div className="page">
      <PageHead title={t('departments.title')} desc={t('departments.desc')}>
        <Button icon={<ReloadOutlined />} loading={q.isFetching} onClick={() => void q.refetch()}>
          {t('common.refresh')}
        </Button>
      </PageHead>
      <div className="surface">
        <div className="toolbar">
          {account.multiScope ? <FactorySelect value={factory} onChange={setFactory} scoped clearable /> : null}
          <Input
            style={{ width: 260 }}
            allowClear
            prefix={<SearchOutlined />}
            placeholder={t('departments.search')}
            value={kw}
            onChange={(e) => setKw(e.target.value)}
          />
        </div>
        <Table<Row>
          size="small"
          loading={q.isFetching}
          rowKey={(r) => `${str(r.orgCode)}|${str(r.deptCode)}`}
          dataSource={shown}
          locale={{ emptyText: t('departments.empty') }}
          columns={[
            {
              title: t('common.factory'),
              ellipsis: true,
              render: (_, r) => (
                <>
                  {str(r.orgName) || str(r.orgCode)}
                  <span className="muted">（{str(r.orgCode)}）</span>
                </>
              ),
            },
            { title: t('departments.code'), dataIndex: 'deptCode' },
            { title: t('departments.name'), dataIndex: 'deptName', ellipsis: true },
            { title: t('orders.bills'), align: 'right', width: 100, render: (_, r) => <span className="num">{str(r.bills)}</span> },
            { title: t('orders.rows'), align: 'right', width: 100, render: (_, r) => <span className="num">{str(r.lineCount)}</span> },
            { title: t('orders.modifyDate'), width: 170, render: (_, r) => <span className="num">{str(r.modifyTime) || '—'}</span> },
          ]}
        />
      </div>
    </div>
  );
}
