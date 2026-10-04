import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { TransferImpactHint } from './TransferImpactHint';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { paginationTotal } from '../../../api/sn';
import {
  transferApi,
  type Transfer,
  type TransferAction,
  type TransferDecision,
} from '../../../api/snTransfer';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { FactoryPicker } from './FactoryPicker';
import { SnOperation } from './SnOperation';
import { useSnOperation } from '../useSnOperation';

export function TransferHistory({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [factoryId, setFactory] = useState<string>(),
    [pi, setPi] = useState(''),
    [status, setStatus] = useState<string>(),
    [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Transfer>(),
    [decision, setDecision] = useState<{ row: Transfer; action: TransferAction }>(),
    [note, setNote] = useState('');
  const [filter, setFilter] = useState<{ factoryId?: string; pi: string; status?: string }>({
    pi: '',
  });
  const rows = useQuery({
    queryKey: ['sn', 'transfer', profile.id, profile.authorizationVersion, filter, page],
    queryFn: () => transferApi.list({ ...filter, page }),
  });
  const approve = useSnOperation(profile, 'TRANSFER_APPROVE', (input: TransferDecision) =>
    transferApi.decision('APPROVE', input),
  );
  const reject = useSnOperation(profile, 'TRANSFER_REJECT', (input: TransferDecision) =>
    transferApi.decision('REJECT', input),
  );
  const withdraw = useSnOperation(profile, 'TRANSFER_WITHDRAW', (input: TransferDecision) =>
    transferApi.decision('WITHDRAW', input),
  );
  const work = { APPROVE: approve, REJECT: reject, WITHDRAW: withdraw };
  const busy = approve.busy || reject.busy || withdraw.busy;
  return (
    <div>
      <Space wrap className="sn-toolbar">
        <FactoryPicker profile={profile} value={factoryId} onChange={setFactory} />
        <Input
          aria-label="PI"
          placeholder="PI"
          value={pi}
          onChange={(e) => setPi(e.target.value)}
          style={{ width: 200 }}
        />
        <Select
          aria-label={t('snPages.common.status')}
          allowClear
          placeholder={t('snPages.common.status')}
          style={{ width: 160 }}
          value={status}
          onChange={setStatus}
          options={['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].map((value) => ({
            value,
            label: t('snTransfer.states.' + value),
          }))}
        />
        <Button
          onClick={() => {
            setPage(1);
            setFilter({ factoryId, pi: pi.trim(), status });
            void rows.refetch();
          }}
        >
          {t('snPages.common.search')}
        </Button>
      </Space>
      <SnOperation work={approve} />
      <SnOperation work={reject} />
      <SnOperation work={withdraw} />
      <ApiErrorAlert error={rows.error} />
      <Table
        rowKey="id"
        data-testid="transfer-history"
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        scroll={{ x: 1550 }}
        pagination={{
          current: page,
          pageSize: 20,
          total: paginationTotal(rows.data?.total),
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={[
          {
            title: t('snTransfer.no'),
            dataIndex: 'id',
            width: 250,
            render: (id: string) => (
              <Typography.Text copyable className="sn-mono">
                {id}
              </Typography.Text>
            ),
          },
          {
            title: t('snPages.common.status'),
            render: (_, r) => (
              <Space>
                <Tag>{t('snTransfer.states.' + r.status)}</Tag>
                {t('snTransfer.dir' + r.direction)}
              </Space>
            ),
          },
          { title: t('snTransfer.source'), render: (_, r) => `${r.fromFactoryCode} · ${r.fromPi}` },
          { title: t('snTransfer.target'), render: (_, r) => `${r.toFactoryCode} · ${r.toPi}` },
          { title: t('snPages.common.qty'), dataIndex: 'quantity' },
          { title: t('snTransfer.reason'), dataIndex: 'reason' },
          {
            title: t('snTransfer.applied'),
            dataIndex: 'createdAt',
            render: (v: string) => new Date(v).toLocaleString(),
          },
          {
            title: t('snPages.common.actions'),
            fixed: 'right',
            width: 210,
            render: (_, r) => (
              <Space wrap>
                <Button type="link" onClick={() => setSelected(r)}>
                  {t('snPages.common.detail')}
                </Button>
                {r.status === 'PENDING' &&
                  (['APPROVE', 'REJECT', 'WITHDRAW'] as const)
                    .filter((action) =>
                      profile.permissions.includes('sn:transfer:' + action.toLowerCase()),
                    )
                    .map((action) => (
                      <Button
                        key={action}
                        type="link"
                        disabled={busy}
                        onClick={() => {
                          setNote('');
                          setDecision({ row: r, action });
                        }}
                      >
                        {t('snTransfer.' + action.toLowerCase())}
                      </Button>
                    ))}
              </Space>
            ),
          },
        ]}
      />
      {selected && (
        <TransferDetails profile={profile} record={selected} close={() => setSelected(undefined)} />
      )}
      <Modal
        open={!!decision}
        title={decision ? t('snTransfer.' + decision.action.toLowerCase()) : ''}
        onCancel={() => setDecision(undefined)}
        okText={t('users.confirm')}
        cancelText={t('snPages.common.cancel')}
        onOk={() => {
          if (decision) {
            work[decision.action].begin({
              id: decision.row.id,
              operationId: crypto.randomUUID(),
              version: decision.row.version,
              note,
            });
            setDecision(undefined);
          }
        }}
      >
        <p>
          {decision &&
            t('snTransfer.' + decision.action.toLowerCase() + 'Confirm', {
              no: decision.row.id,
              n: decision.row.quantity,
            })}
        </p>
        <Input.TextArea
          aria-label={t('snTransfer.note')}
          placeholder={t('snTransfer.note')}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          maxLength={500}
        />
        {decision?.action === 'APPROVE' && (
          <>
            <Alert type="warning" showIcon title={t('snTransfer.labels')} />
            <TransferImpactHint profile={profile} transferId={decision.row.id} />
          </>
        )}
      </Modal>
    </div>
  );
}
function TransferDetails({
  profile,
  record,
  close,
}: {
  profile: UserProfile;
  record: Transfer;
  close: () => void;
}) {
  const { t } = useTranslation();
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const rows = useQuery({
    queryKey: ['sn', 'transfer-items', profile.id, profile.authorizationVersion, record.id, cursor],
    queryFn: () => transferApi.items(record.id, cursor),
  });
  const detail = useQuery({
    queryKey: ['sn', 'transfer-detail', profile.id, profile.authorizationVersion, record.id],
    queryFn: () => transferApi.get(record.id),
  });
  const value = detail.data ?? record;
  return (
    <Drawer
      title={t('snTransfer.itemsOf', { no: record.id })}
      open
      onClose={close}
      size="large"
      styles={{ wrapper: { width: 'min(1100px,100vw)' } }}
    >
      <Alert type="info" title={t('snTransfer.fixed')} showIcon />
      <Descriptions
        column={1}
        size="small"
        items={[
          {
            key: 'scope',
            label: t('snTransfer.scope'),
            children: t('snTransfer.scopes.' + value.scope),
          },
          {
            key: 'mode',
            label: t('snTransfer.target'),
            children: `${t('snTransfer.src' + value.targetSource)} · ${value.toFactoryCode} · ${value.toPi}`,
          },
          {
            key: 'customer',
            label: t('snTransfer.toCustomer'),
            children: value.toCustomer || t('snTransfer.keepShort'),
          },
          {
            key: 'material',
            label: t('snTransfer.toMaterial'),
            children: value.toMaterial || t('snTransfer.keepShort'),
          },
          { key: 'reason', label: t('snTransfer.reason'), children: value.reason },
          { key: 'note', label: t('snTransfer.note'), children: value.note },
        ]}
      />
      <ApiErrorAlert error={rows.error ?? detail.error} />
      {value.status === 'PENDING' && <TransferImpactHint profile={profile} transferId={value.id} />}
      <Table
        rowKey="ordinal"
        data-testid="transfer-members"
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        pagination={false}
        scroll={{ x: 1300 }}
        columns={[
          { title: '#', dataIndex: 'ordinal', width: 60 },
          { title: 'SN', render: (_, r) => <span className="sn-mono">{r.snapshot.sn}</span> },
          { title: t('snPages.common.seqText'), render: (_, r) => r.snapshot.seqText ?? '—' },
          { title: t('snPages.common.seqDec'), render: (_, r) => r.snapshot.seqDec ?? '—' },
          {
            title: t('snTransfer.fromStatus'),
            render: (_, r) => t('snAcquire.status.' + r.fromStatus),
          },
          {
            title: t('snTransfer.nowStatus'),
            render: (_, r) => (r.currentStatus ? t('snAcquire.status.' + r.currentStatus) : '—'),
          },
          {
            title: t('snTransfer.nowAt'),
            render: (_, r) => `${r.currentFactoryId ?? '—'} · ${r.currentPi ?? '—'}`,
          },
          { title: t('snAcquire.batchNo'), dataIndex: 'fromBatchId' },
        ]}
      />
      <Space className="sn-toolbar">
        <Button disabled={cursors.length <= 1} onClick={() => setCursors((v) => v.slice(0, -1))}>
          {t('snTransfer.previous')}
        </Button>
        <span>{cursors.length}</span>
        <Button
          disabled={!rows.data?.nextCursor}
          onClick={() => setCursors((v) => [...v, rows.data!.nextCursor!])}
        >
          {t('snTransfer.next')}
        </Button>
      </Space>
    </Drawer>
  );
}
