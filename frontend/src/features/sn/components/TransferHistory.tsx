import { CheckCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Drawer, Input, Modal, Pagination, Select, Space, Table, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
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
import { useSnContext } from '../useSnContext';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { syncErrorText } from '../syncErrorText';
import { transferJump } from '../transferJump';
import { formatSnTime } from '../../../utils/snTime';

const statusColors: Record<string, string> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'info',
  TO_ACQUIRE: 'primary',
  TO_PRINT: 'warning',
  PRINTED: 'success',
  APPLYING: 'danger',
  TRANSFERRED_OUT: 'info',
};
function Status({ status, transfer = false }: { status: string; transfer?: boolean }) {
  const { t } = useTranslation('prototype');
  return (
    <Tag className={'sn-transfer-tag sn-transfer-tag-' + statusColors[status]}>
      {t((transfer ? 'transferStatus.' : 'status.') + status)}
    </Tag>
  );
}

/** 决策仍恢复原操作；成功后刷新记录和提示，不把异步确认当作业务成功。 */
function useDecision(profile: UserProfile, action: TransferAction, done: () => void) {
  const work = useSnOperation(profile, `TRANSFER_${action}`, (input: TransferDecision) =>
    transferApi.decision(action, input),
  );
  const consumed = useRef(new Set<string>());
  const task = work.task.data;
  useEffect(() => {
    if (task?.state === 'SUCCESS' && !consumed.current.has(task.operationId)) {
      consumed.current.add(task.operationId);
      done();
      work.clear();
    }
  }, [task, work, done]);
  return work;
}

type Filter = { factoryId?: string; pi: string; status?: string };
export function TransferHistory({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const context = useSnContext(profile);
  const bound =
    !context.data?.allFactories && context.data?.factories.length === 1
      ? context.data.factories[0]
      : undefined;
  const [draft, setDraft] = useState<Filter>({ pi: '' });
  const [filter, setFilter] = useState(draft);
  const [pagination, setPagination] = useState({ page: 1, size: 20 });
  const [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState<Transfer>();
  const [preparing, setPreparing] = useState(false);
  const [modal, modalHolder] = Modal.useModal();
  const feedback = usePrototypeFeedback();
  const done = () => feedback.success(t('common.done'));
  const approve = useDecision(profile, 'APPROVE', done),
    reject = useDecision(profile, 'REJECT', done),
    withdraw = useDecision(profile, 'WITHDRAW', done);
  const work = { APPROVE: approve, REJECT: reject, WITHDRAW: withdraw };
  const busy = preparing || approve.busy || reject.busy || withdraw.busy;
  const rows = useQuery({
    queryKey: [
      'sn',
      'transfer',
      profile.id,
      profile.authorizationVersion,
      filter,
      pagination,
      revision,
    ],
    queryFn: () => transferApi.list({ ...filter, ...pagination }),
  });
  const reload = (next = draft) => {
    setDraft(next);
    setFilter({ ...next, pi: next.pi.trim() });
    setPagination((p) => ({ ...p, page: 1 }));
    setRevision((v) => v + 1);
  };
  const decide = async (row: Transfer, action: TransferAction) => {
    let hint = '';
    setPreparing(true);
    try {
      if (action === 'APPROVE') {
        const jump = transferJump(await transferApi.pendingHint(row.id));
        if (jump) hint = ' ' + t('transfer.jumpWarn', { pi: row.toPi, ...jump });
      }
    } catch (error) {
      feedback.error(syncErrorText(error, common));
      return;
    } finally {
      setPreparing(false);
    }
    let note = '';
    modal.confirm({
      title: t('transfer.' + action.toLowerCase()),
      icon: null,
      className: 'sn-init-confirm sn-transfer-confirm',
      width: 420,
      centered: true,
      closable: true,
      okText: common('snAcquire.confirmButton'),
      cancelText: common('snAcquire.cancelButton'),
      content: (
        <>
          <div
            className={
              'sn-init-confirm-message' +
              (action === 'APPROVE' ? ' sn-transfer-approve-message' : '')
            }
          >
            {action === 'APPROVE' ? (
              <CheckCircleFilled aria-hidden />
            ) : (
              <ExclamationCircleFilled aria-hidden />
            )}
            <span>
              {t('transfer.' + action.toLowerCase() + 'Confirm', {
                no: row.transferNo ?? row.id,
                n: row.quantity,
              })}
              {hint}
            </span>
          </div>
          {action !== 'WITHDRAW' && (
            <Input
              className="sn-transfer-note"
              aria-label={t('transfer.note')}
              placeholder={t('transfer.note')}
              maxLength={500}
              onChange={(e) => {
                note = e.target.value;
              }}
            />
          )}
        </>
      ),
      onOk: () =>
        work[action].begin({
          id: row.id,
          operationId: crypto.randomUUID(),
          version: row.version,
          note,
        }),
    });
  };
  return (
    <div className="sn-transfer-history">
      {feedback.holder}
      {modalHolder}
      <div className="sn-toolbar">
        <Select
          aria-label={t('common.status')}
          allowClear
          placeholder={t('common.status')}
          value={draft.status}
          onChange={(status) => reload({ ...draft, status })}
          options={['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].map((value) => ({
            value,
            label: t('transferStatus.' + value),
          }))}
        />
        <Input
          aria-label="PI"
          placeholder="PI"
          allowClear
          value={draft.pi}
          onChange={(e) => {
            const next = { ...draft, pi: e.target.value };
            setDraft(next);
            if (!next.pi) reload(next);
          }}
          onPressEnter={() => reload()}
        />
        {!bound && (
          <FactoryPicker
            profile={profile}
            value={draft.factoryId}
            onChange={(factoryId) => reload({ ...draft, factoryId })}
          />
        )}
        <Button type="primary" onClick={() => reload()}>
          {t('common.search')}
        </Button>
      </div>
      {[approve, reject, withdraw].map((operation, index) =>
        operation.mutation.error ||
        operation.task.error ||
        operation.task.data?.state === 'FAILED' ? (
          <SnOperation key={index} work={operation} />
        ) : null,
      )}
      <ApiErrorAlert error={rows.error} />
      <Table
        rowKey="id"
        size="small"
        tableLayout="fixed"
        locale={{ emptyText: t('common.empty') }}
        data-testid="transfer-history"
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        scroll={{ x: 1370 }}
        pagination={false}
        columns={[
          {
            title: t('transfer.no'),
            width: 170,
            render: (_, r) => (
              <>
                <div className="sn-transfer-number" title={r.transferNo ?? r.id}>
                  {r.transferNo ?? r.id}
                </div>
                <Tag className="sn-transfer-tag sn-transfer-mode">
                  {t('transfer.mode' + r.mode)}
                </Tag>
                {bound && (
                  <Tag
                    className={
                      'sn-transfer-tag sn-transfer-tag-' +
                      (r.fromFactoryId === bound.id ? 'danger' : 'success')
                    }
                  >
                    {t('transfer.dir' + (r.fromFactoryId === bound.id ? 'OUT' : 'IN'))}
                  </Tag>
                )}
              </>
            ),
          },
          {
            title: t('common.status'),
            width: 90,
            render: (_, r) => <Status status={r.status} transfer />,
          },
          {
            title: t('transfer.source'),
            width: 210,
            render: (_, r) => (
              <>
                <div>
                  {r.fromFactoryName || r.fromFactoryCode} · <b>{r.fromPi}</b>
                </div>
                <div className="sn-muted">
                  {t('scope.' + r.scope)}
                  {r.material ? ' · ' + r.material : ''}
                  {r.sn ? ' · ' + r.sn : ''}
                </div>
              </>
            ),
          },
          {
            title: t('transfer.target'),
            width: 220,
            render: (_, r) => (
              <>
                <div>
                  {r.toFactoryName || r.toFactoryCode} · <b>{r.toPi}</b>
                </div>
                <div className="sn-muted">
                  {t('common.customer')} {r.toCustomer || t('transfer.keepShort')} ·{' '}
                  {t('common.material')} {r.toMaterial || t('transfer.keepShort')}
                  {r.targetSource === 'MANUAL' && (
                    <Tag className="sn-transfer-tag sn-transfer-tag-warning">
                      {t('transfer.srcMANUAL')}
                    </Tag>
                  )}
                </div>
              </>
            ),
          },
          { title: t('common.qty'), dataIndex: 'quantity', width: 70, align: 'right' },
          { title: t('transfer.reason'), dataIndex: 'reason', width: 140, ellipsis: true },
          {
            title: t('transfer.applied'),
            width: 150,
            render: (_, r) => (
              <>
                <div>{r.actorName ?? r.actorId}</div>
                <div className="sn-muted">{formatSnTime(r.createdAt)}</div>
              </>
            ),
          },
          {
            title: t('transfer.decided'),
            width: 150,
            render: (_, r) => (
              <>
                <div>{r.decidedName ?? r.decidedBy ?? ''}</div>
                <div className="sn-muted">{r.decidedAt ? formatSnTime(r.decidedAt) : ''}</div>
              </>
            ),
          },
          {
            title: t('common.actions'),
            fixed: 'right',
            width: 170,
            render: (_, r) => (
              <Space size={0}>
                <Button type="link" onClick={() => setSelected(r)}>
                  {t('common.detail')}
                </Button>
                {r.status === 'PENDING' &&
                  (['APPROVE', 'REJECT', 'WITHDRAW'] as const)
                    .filter(
                      (action) =>
                        profile.permissions.includes('sn:transfer:' + action.toLowerCase()) &&
                        (action !== 'WITHDRAW' || bound?.id === r.fromFactoryId),
                    )
                    .map((action) => (
                      <Button
                        className={'sn-transfer-action-' + action.toLowerCase()}
                        key={action}
                        type="link"
                        disabled={busy}
                        onClick={() => void decide(r, action)}
                      >
                        {t('transfer.' + action.toLowerCase())}
                      </Button>
                    ))}
              </Space>
            ),
          },
        ]}
      />
      <Pagination
        className="sn-acquire-pager"
        current={pagination.page}
        pageSize={pagination.size}
        total={paginationTotal(rows.data?.total)}
        showSizeChanger
        pageSizeOptions={[20, 50, 100]}
        showTotal={() => common('shell.paginationTotal', { total: rows.data?.total ?? '0' })}
        onChange={(page, size) => setPagination({ page, size })}
      />
      {selected && (
        <TransferDetails profile={profile} record={selected} close={() => setSelected(undefined)} />
      )}
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
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const [page, setPage] = useState(1);
  const rows = useQuery({
    queryKey: [
      'sn',
      'transfer-detail-page',
      profile.id,
      profile.authorizationVersion,
      record.id,
      page,
    ],
    queryFn: () => transferApi.itemPage(record.id, page),
  });
  return (
    <Drawer
      open
      title={t('transfer.itemsOf', { no: record.transferNo ?? record.id })}
      onClose={close}
      size={780}
      rootClassName="sn-acquire-detail sn-transfer-detail"
    >
      <ApiErrorAlert error={rows.error} />
      <Table
        rowKey="ordinal"
        size="small"
        tableLayout="auto"
        locale={{ emptyText: t('common.empty') }}
        data-testid="transfer-members"
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        pagination={false}
        scroll={{ x: 910 }}
        columns={[
          {
            title: t('common.sn'),
            dataIndex: 'sn',
            minWidth: 170,
            render: (value: string) => <span className="sn-mono">{value}</span>,
          },
          {
            title: t('common.seqText'),
            dataIndex: 'seqText',
            width: 110,
            render: (value: string | null) => <span className="sn-mono">{value}</span>,
          },
          { title: t('common.seqDec'), dataIndex: 'seqDec', width: 90 },
          {
            title: t('transfer.fromStatus'),
            width: 100,
            render: (_, r) => <Status status={r.fromStatus} />,
          },
          {
            title: t('transfer.nowStatus'),
            width: 100,
            render: (_, r) => (r.currentStatus ? <Status status={r.currentStatus} /> : null),
          },
          {
            title: t('transfer.nowAt'),
            minWidth: 170,
            render: (_, r) =>
              `${r.currentFactoryName || r.currentFactoryCode || ''} · ${r.currentPi ?? ''}`,
          },
          { title: t('acquire.batchNo'), dataIndex: 'fromBatchNo', minWidth: 170 },
        ]}
      />
      <Pagination
        className="sn-acquire-pager"
        current={page}
        pageSize={50}
        showSizeChanger={false}
        total={paginationTotal(rows.data?.total)}
        showTotal={() => common('shell.paginationTotal', { total: rows.data?.total ?? '0' })}
        onChange={setPage}
      />
    </Drawer>
  );
}
