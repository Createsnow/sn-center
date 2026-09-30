import { Descriptions, Empty, Table, Tag, Pagination } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { UserProfile } from '../../../api/contract';
import { snWorkApi } from '../../../api/snWork';
import { paginationTotal } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { useTranslation } from 'react-i18next';
import type { PiStatus, SnWork } from '../../../api/snWork';

export function SnCounter({ value }: { value: PiStatus['counter'] }) {
  const { t } = useTranslation();
  return (
    <Descriptions
      size="small"
      bordered
      column={{ xs: 1, sm: 2 }}
      items={[
        {
          key: 'last',
          label: t('snPages.generate.lastSeq'),
          children: (
            <b className="sn-mono" data-testid="sn-watermark">
              {value.lastSeq}
            </b>
          ),
        },
        {
          key: 'generated',
          label: t('snPages.generate.piGenerated'),
          children: value.generatedQty,
        },
        { key: 'imported', label: t('snPages.generate.piImported'), children: value.importedQty },
        {
          key: 'start',
          label: t('snPages.generate.startState'),
          children: (
            <Tag color={value.startLocked ? 'default' : 'green'}>
              {t(value.startLocked ? 'snPages.generate.startLocked' : 'snPages.generate.startOpen')}
            </Tag>
          ),
        },
      ]}
    />
  );
}
export function SnRuleDetails({
  rule,
  details,
  singleColumn = false,
}: {
  rule: PiStatus['rule'];
  details: Pick<PiStatus, 'ruleCode' | 'ruleScope' | 'ruleBinding'>;
  singleColumn?: boolean;
}) {
  const { t } = useTranslation();
  return rule ? (
    <Descriptions
      size="small"
      bordered
      className="sn-rule-details"
      column={singleColumn ? 1 : { xs: 1, sm: 1, md: 2 }}
      items={[
        {
          key: 'version',
          label: t('snPages.rules.version'),
          children: `${details.ruleCode} · v${rule.version}`,
        },
        {
          key: 'binding',
          label: t('snPages.rules.bind'),
          children: `${t('snPages.rules.scope' + details.ruleScope)} ${details.ruleBinding}`,
        },
        {
          key: 'sample',
          label: t('snPages.rules.sample'),
          children: (
            <span className="sn-mono">
              {rule.prefix +
                Array.from(rule.alphabet)[0]!.repeat(rule.length - 1) +
                Array.from(rule.alphabet)[1]! +
                rule.suffix}
            </span>
          ),
        },
        {
          key: 'format',
          label: t('snPages.rules.format'),
          children: (
            <span className="sn-mono">
              {rule.prefix}
              <i>{'#'.repeat(rule.length)}</i>
              {rule.suffix}
            </span>
          ),
        },
        { key: 'base', label: t('snPages.rules.base'), children: `${rule.base} / ${rule.length}` },
        {
          key: 'max',
          label: t('snPages.rules.maxSeq'),
          children: (BigInt(rule.base) ** BigInt(rule.length) - 1n).toString(),
        },
      ]}
    />
  ) : (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('snPages.piInit.noRule')} />
  );
}
export function SnBillHistory({
  profile,
  billId,
  kind,
}: {
  profile: UserProfile;
  billId: string;
  kind: 'GENERATE' | 'ALLOCATE';
}) {
  const [page, setPage] = useState(1);
  const history = useQuery({
    queryKey: ['sn', 'work-history', profile.id, profile.authorizationVersion, billId, kind, page],
    queryFn: () => snWorkApi.history(billId, kind, page),
  });
  return (
    <>
      <ApiErrorAlert error={history.error} />
      <SnWorkTable rows={history.data?.items ?? []} />
      <Pagination
        current={page}
        pageSize={20}
        total={paginationTotal(history.data?.total)}
        onChange={setPage}
        showSizeChanger={false}
      />
    </>
  );
}
export function SnWorkTable({
  rows,
  initialization = false,
}: {
  rows: SnWork[];
  initialization?: boolean;
}) {
  const { t, i18n } = useTranslation();
  return (
    <Table<SnWork>
      size="small"
      rowKey="taskId"
      dataSource={rows}
      pagination={false}
      scroll={{ x: 900 }}
      columns={[
        {
          title: t('snPages.common.status'),
          dataIndex: 'state',
          render: (s: string) => t('export.states.' + (s === 'PENDING' ? 'QUEUED' : s)),
        },
        ...(initialization
          ? [
              { title: t('snPages.generate.start'), dataIndex: 'startSeq' },
              { title: t('snPages.piInit.maxSeq'), dataIndex: 'maxSeq' },
              { title: t('snPages.piInit.file'), dataIndex: 'fileName' },
            ]
          : [
              {
                title: t('snPages.generate.segment'),
                key: 'range',
                render: (_: unknown, r: SnWork) => (
                  <span className="sn-mono">
                    {r.firstSn ?? '—'} ~ {r.lastSn ?? '—'}
                  </span>
                ),
              },
            ]),
        { title: t('snPages.common.qty'), dataIndex: 'quantity' },
        { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
        { title: t('snPages.common.operator'), dataIndex: 'actorId' },
        {
          title: t('snPages.generate.error'),
          dataIndex: 'errorCode',
          render: (code: number | null) => (code === null ? '—' : t(`errors.${code}`)),
        },
        {
          title: t('snPages.common.time'),
          dataIndex: 'createdAt',
          render: (v: string) => new Date(v).toLocaleString(i18n.language),
        },
      ]}
    />
  );
}
