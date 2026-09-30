import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Input,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { acquireApi } from '../../../api/snAcquire';
import { paginationTotal } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { AcquireBatches, AcquirePrints, AcquireDetails } from '../components/AcquireHistory';
import { useSnOperation } from '../useSnOperation';

/** 物料/状态只筛展示；领取/打印命令只取汇总行的整厂+PI，不借跨厂只读模式写入。 */
export function AcquirePage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [factoryId, setFactoryId] = useState<string>(),
    [pi, setPi] = useState(''),
    [materialMode, setMaterialMode] = useState('ALL'),
    [material, setMaterial] = useState(''),
    [status, setStatus] = useState(''),
    [piAll, setPiAll] = useState(false),
    [page, setPage] = useState(1),
    [segmentPage, setSegmentPage] = useState(1),
    [tab, setTab] = useState('batches'),
    [details, setDetails] = useState(false);
  const [filter, setFilter] = useState<{ factoryId?: string; pi: string; piAll: boolean }>({
    pi: '',
    piAll: false,
  });
  const prefix = ['sn', 'acquire', profile.id, profile.authorizationVersion, filter];
  const displayFilter = { ...filter, factoryId: filter.piAll ? undefined : filter.factoryId };
  const summary = useQuery({
    queryKey: [...prefix, 'summary', page],
    queryFn: () => acquireApi.summaries({ ...displayFilter, page, size: 10 }),
  });
  const segments = useQuery({
    queryKey: [...prefix, 'segments', materialMode, material, status, segmentPage],
    queryFn: () =>
      acquireApi.segments({
        ...displayFilter,
        materialMode,
        material,
        status,
        page: segmentPage,
        size: 20,
      }),
    enabled: materialMode !== 'VALUE' || !!material,
  });
  const take = useSnOperation(profile, 'TAKE', acquireApi.take),
    print = useSnOperation(profile, 'PRINT', acquireApi.print);
  const result = useQuery({
    queryKey: [...prefix, 'result', take.task.data?.taskId, take.task.data?.state],
    queryFn: () => acquireApi.work(take.task.data!.taskId),
    enabled: take.task.data?.state === 'SUCCESS',
  });
  const actable = !filter.piAll && !take.busy && !print.busy;
  const reload = () => {
    setPage(1);
    setSegmentPage(1);
    setFilter({ factoryId, pi: pi.trim(), piAll: piAll && !!pi.trim() });
    void summary.refetch();
    void segments.refetch();
  };
  return (
    <div className="sn-page">
      <SnPageHead title={t('snAcquire.title')} description={t('snAcquire.desc')}>
        <Space wrap>
          <FactoryPicker profile={profile} value={factoryId} onChange={setFactoryId} />
          <Input
            aria-label="PI"
            placeholder="PI"
            value={pi}
            onChange={(e) => setPi(e.target.value)}
            onPressEnter={reload}
            style={{ width: 240 }}
          />
          <Switch
            checked={piAll}
            disabled={!pi.trim()}
            onChange={setPiAll}
            checkedChildren={t('snAcquire.piAll')}
            unCheckedChildren={t('snAcquire.piAll')}
          />
          <Button type="primary" onClick={reload}>
            {t('snPages.common.search')}
          </Button>
        </Space>
      </SnPageHead>
      {filter.piAll ? (
        <Alert type="info" showIcon title={t('snAcquire.piAllReadOnly')} />
      ) : (
        !profile.permissions.includes('sn:acquire:take') &&
        !profile.permissions.includes('sn:print:execute') && (
          <Alert type="info" showIcon title={t('snAcquire.queryReadOnly')} />
        )
      )}
      <div className="sn-section">
        <Typography.Title level={4}>{t('snAcquire.byPi')}</Typography.Title>
        <Alert type="info" title={t('snAcquire.rule')} showIcon />
        <ApiErrorAlert error={summary.error} />
        <Table
          rowKey={(r) => r.factoryId + ':' + r.pi}
          data-testid="acquire-summary"
          dataSource={summary.data?.items}
          loading={summary.isFetching}
          scroll={{ x: 1000 }}
          pagination={{
            current: page,
            pageSize: 10,
            total: paginationTotal(summary.data?.total),
            showSizeChanger: false,
            onChange: setPage,
          }}
          locale={{ emptyText: t('snAcquire.emptyPis') }}
          columns={[
            { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
            {
              title: 'PI',
              render: (_, row) => (
                <Button
                  type="link"
                  onClick={() => {
                    setPi(row.pi!);
                    setFilter((f) => ({ ...f, pi: row.pi! }));
                    setSegmentPage(1);
                  }}
                >
                  {row.pi}
                </Button>
              ),
            },
            { title: t('snPages.common.customer'), dataIndex: 'customer' },
            {
              title: t('snAcquire.status.TO_ACQUIRE'),
              render: (_, row) => (
                <Space>
                  <span>{row.toAcquire}</span>
                  {profile.permissions.includes('sn:acquire:take') && !filter.piAll && (
                    <Popconfirm
                      title={t('snAcquire.takeConfirm', {
                        factory: row.factoryCode,
                        pi: row.pi,
                        n: row.toAcquire,
                      })}
                      okText={t('users.confirm')}
                      cancelText={t('snPages.common.cancel')}
                      onConfirm={() =>
                        take.begin({
                          operationId: crypto.randomUUID(),
                          factoryId: row.factoryId!,
                          pi: row.pi!,
                        })
                      }
                    >
                      <Button
                        type="primary"
                        size="small"
                        disabled={!actable || row.toAcquire === '0'}
                      >
                        {t('snAcquire.take')}
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
            {
              title: t('snAcquire.status.TO_PRINT'),
              render: (_, row) => (
                <Space>
                  <span>{row.toPrint}</span>
                  {profile.permissions.includes('sn:print:execute') && !filter.piAll && (
                    <Popconfirm
                      title={t('snAcquire.printConfirm', {
                        factory: row.factoryCode,
                        pi: row.pi,
                        n: row.toPrint,
                      })}
                      okText={t('users.confirm')}
                      cancelText={t('snPages.common.cancel')}
                      onConfirm={() => {
                        setTab('prints');
                        print.begin({
                          operationId: crypto.randomUUID(),
                          factoryId: row.factoryId!,
                          pi: row.pi!,
                        });
                      }}
                    >
                      <Button size="small" disabled={!actable || row.toPrint === '0'}>
                        {t('snAcquire.print')}
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
            { title: t('snAcquire.status.APPLYING'), dataIndex: 'applying' },
          ]}
        />
        <SnOperation work={take} />
        {result.data && (
          <Alert
            type="success"
            title={t('snAcquire.result', {
              eligible: result.data.eligible,
              blocked: result.data.blocked,
            })}
            description={
              <Button onClick={() => setDetails(true)}>{t('snAcquire.resultItems')}</Button>
            }
          />
        )}
        <ApiErrorAlert error={result.error} />
        <SnOperation work={print} />
      </div>
      <div className="sn-section">
        <Typography.Title level={4}>{t('snAcquire.segments')}</Typography.Title>
        <Space wrap>
          <Select
            aria-label={t('snAcquire.materialMode')}
            value={materialMode}
            onChange={(v) => {
              setMaterialMode(v);
              setSegmentPage(1);
            }}
            options={['ALL', 'EMPTY', 'VALUE'].map((value) => ({
              value,
              label: t('snAcquire.materialModes.' + value),
            }))}
          />
          {materialMode === 'VALUE' && (
            <Input
              aria-label={t('snPages.common.material')}
              value={material}
              onChange={(e) => {
                setMaterial(e.target.value);
                setSegmentPage(1);
              }}
            />
          )}
          <Select
            aria-label={t('snPages.common.status')}
            value={status}
            onChange={(v) => {
              setStatus(v);
              setSegmentPage(1);
            }}
            options={['', 'TO_ACQUIRE', 'TO_PRINT', 'APPLYING'].map((value) => ({
              value,
              label: value ? t('snAcquire.status.' + value) : t('snPages.common.all'),
            }))}
          />
        </Space>
        <ApiErrorAlert error={segments.error} />
        <Table
          rowKey={(r) => [r.factoryId, r.pi, r.material, r.status, r.seqPi, r.groupId].join(':')}
          data-testid="acquire-segments"
          dataSource={segments.data?.items}
          loading={segments.isFetching}
          scroll={{ x: 1150 }}
          pagination={{
            current: segmentPage,
            pageSize: 20,
            total: paginationTotal(segments.data?.total),
            showSizeChanger: false,
            onChange: setSegmentPage,
          }}
          columns={[
            { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
            { title: 'PI', dataIndex: 'pi' },
            {
              title: t('snPages.common.material'),
              dataIndex: 'material',
              render: (v: string) => v || t('snPages.orders.noMaterial'),
            },
            {
              title: t('snPages.common.status'),
              dataIndex: 'status',
              render: (v: string) => t('snAcquire.status.' + v),
            },
            {
              title: t('snAcquire.range'),
              render: (_, row) => (
                <span className="sn-mono">
                  {row.startSn} ~ {row.endSn}
                </span>
              ),
            },
            {
              title: t('snAcquire.decRange'),
              render: (_, row) => (
                <span className="sn-mono">
                  {row.startSeq ?? '—'} ~ {row.endSeq ?? '—'}
                </span>
              ),
            },
            { title: t('snPages.common.qty'), dataIndex: 'quantity' },
            { title: t('snAcquire.seqOwner'), dataIndex: 'seqPi' },
          ]}
        />
      </div>
      <div className="sn-section">
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: 'batches',
              label: t('snAcquire.batches'),
              children: (
                <AcquireBatches
                  key={JSON.stringify(filter)}
                  profile={profile}
                  factoryId={filter.factoryId}
                  pi={filter.pi}
                />
              ),
            },
            {
              key: 'prints',
              label: t('snAcquire.prints'),
              children: (
                <AcquirePrints
                  key={JSON.stringify(filter)}
                  profile={profile}
                  factoryId={filter.factoryId}
                  pi={filter.pi}
                />
              ),
            },
          ]}
        />
      </div>
      {details && take.task.data && (
        <AcquireDetails
          key={take.task.data.taskId}
          profile={profile}
          id={take.task.data.taskId}
          result
          onClose={() => setDetails(false)}
        />
      )}
    </div>
  );
}
