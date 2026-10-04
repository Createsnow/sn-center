import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Modal, Select, Space, Table, Tabs, Typography } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { paginationTotal } from '../../../api/sn';
import { transferApi, type TransferCreate, type TransferTarget } from '../../../api/snTransfer';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { TransferHistory } from '../components/TransferHistory';
import { TransferImpactHint } from '../components/TransferImpactHint';
import { useSnOperation } from '../useSnOperation';

type Fields = Omit<TransferCreate, 'operationId'>;
/** 保留来源/目标/记录布局；候选只作提示，任务成功后才表示整批冻结或转移。 */
export function TransferPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<Fields>();
  const directAllowed = profile.permissions.includes('sn:transfer:direct'),
    applyAllowed = profile.permissions.includes('sn:transfer:apply');
  const [mode, setMode] = useState<'APPLY' | 'DIRECT'>(directAllowed ? 'DIRECT' : 'APPLY'),
    [tab, setTab] = useState(directAllowed || applyAllowed ? 'new' : 'records');
  const [picker, setPicker] = useState(false),
    [confirm, setConfirm] = useState<Fields>(),
    [chosen, setChosen] = useState<TransferTarget>();
  const [sourceType, setSourceType] = useState('MANUAL');
  const scope = Form.useWatch('scope', form);
  const candidates = useMutation({ mutationFn: transferApi.candidates });
  const apply = useSnOperation(profile, 'TRANSFER_APPLY', transferApi.apply),
    direct = useSnOperation(profile, 'TRANSFER_DIRECT', transferApi.direct);
  const work = mode === 'DIRECT' ? direct : apply,
    busy = apply.busy || direct.busy;
  const candidate = () => {
    const values = form.getFieldsValue();
    if (
      !values.factoryId ||
      !values.pi?.trim() ||
      (values.scope === 'SINGLE' && !values.sn?.trim())
    )
      return;
    candidates.mutate({
      factoryId: values.factoryId,
      pi: values.pi,
      scope: values.scope,
      material: values.material ?? '',
      sn: values.sn ?? undefined,
    });
  };
  return (
    <div className="sn-page">
      <SnPageHead title={t('snTransfer.title')} description={t('snTransfer.desc')} />
      <Alert type="warning" showIcon title={t('snTransfer.labels')} />
      <SnOperation work={apply} />
      <SnOperation work={direct} />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          ...(directAllowed || applyAllowed
            ? [
                {
                  key: 'new',
                  label: t(directAllowed ? 'snTransfer.directTab' : 'snTransfer.applyTab'),
                  children: (
                    <Form
                      form={form}
                      layout="vertical"
                      initialValues={{
                        scope: 'PI',
                        material: '',
                        toCustomer: '',
                        toMaterial: '',
                        targetSource: 'MANUAL',
                      }}
                      onValuesChange={(changed: Partial<Fields>) => {
                        if (
                          ['factoryId', 'pi', 'scope', 'material', 'sn'].some(
                            (key) => key in changed,
                          )
                        )
                          candidates.reset();
                        if (
                          ['toFactoryId', 'toPi', 'toCustomer', 'toMaterial'].some(
                            (key) => key in changed,
                          )
                        )
                          setSourceType('MANUAL');
                      }}
                      onFinish={(values) => setConfirm(values)}
                    >
                      <div className="sn-form-grid">
                        <div className="sn-section">
                          <Typography.Title level={4}>{t('snTransfer.source')}</Typography.Title>
                          <Form.Item
                            name="factoryId"
                            label={t('snTransfer.fromFactory')}
                            rules={[{ required: true }]}
                          >
                            <FactoryPicker profile={profile} />
                          </Form.Item>
                          <Form.Item name="pi" label="PI" rules={[{ required: true }]}>
                            <Input aria-label={t('snTransfer.source') + ' PI'} maxLength={128} />
                          </Form.Item>
                          <Form.Item name="scope" label={t('snTransfer.scope')}>
                            <Select
                              aria-label={t('snTransfer.scope')}
                              options={['PI', 'MATERIAL', 'SINGLE'].map((value) => ({
                                value,
                                label: t('snTransfer.scopes.' + value),
                              }))}
                            />
                          </Form.Item>
                          {scope === 'MATERIAL' && (
                            <Form.Item name="material" label={t('snPages.common.material')}>
                              <Input maxLength={128} placeholder={t('snTransfer.emptyMaterial')} />
                            </Form.Item>
                          )}
                          {scope === 'SINGLE' && (
                            <Form.Item name="sn" label="SN" rules={[{ required: true }]}>
                              <Input aria-label="SN" maxLength={64} />
                            </Form.Item>
                          )}
                          <Button
                            aria-label={t('snTransfer.checkCandidates')}
                            onClick={candidate}
                            loading={candidates.isPending}
                          >
                            {t('snTransfer.checkCandidates')}
                          </Button>
                          <ApiErrorAlert error={candidates.error} />
                          {candidates.data && (
                            <Alert
                              type="info"
                              title={t('snTransfer.candidates', {
                                ok: candidates.data.transferable,
                                total: candidates.data.total,
                              })}
                              description={
                                <Space wrap>
                                  {(['toAcquire', 'toPrint', 'printed', 'applying'] as const).map(
                                    (key, i) => (
                                      <span key={key}>
                                        {t(
                                          'snAcquire.status.' +
                                            ['TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING'][i],
                                        )}
                                        : {candidates.data![key]}
                                      </span>
                                    ),
                                  )}
                                </Space>
                              }
                            />
                          )}
                          <p>{t('snTransfer.rule')}</p>
                        </div>
                        <div className="sn-section">
                          <Typography.Title level={4}>{t('snTransfer.target')}</Typography.Title>
                          <Space wrap>
                            <Button onClick={() => setPicker(true)}>
                              {t('snTransfer.fromSnapshot')}
                            </Button>
                            <span>{t('snTransfer.src' + sourceType)}</span>
                          </Space>
                          <Form.Item
                            name="toFactoryId"
                            label={t('snTransfer.toFactory')}
                            rules={[{ required: true }]}
                          >
                            <TargetFactoryPicker profile={profile} chosen={chosen} />
                          </Form.Item>
                          <Form.Item
                            name="toPi"
                            label={t('snTransfer.toPi')}
                            rules={[{ required: true }]}
                          >
                            <Input aria-label={t('snTransfer.toPi')} maxLength={128} />
                          </Form.Item>
                          <Form.Item name="toCustomer" label={t('snTransfer.toCustomer')}>
                            <Input placeholder={t('snTransfer.keep')} maxLength={64} />
                          </Form.Item>
                          <Form.Item name="toMaterial" label={t('snTransfer.toMaterial')}>
                            <Input placeholder={t('snTransfer.keep')} maxLength={128} />
                          </Form.Item>
                        </div>
                      </div>
                      <div className="sn-section">
                        <Form.Item
                          name="reason"
                          label={t('snTransfer.reason')}
                          rules={[{ required: true, whitespace: true }]}
                        >
                          <Input.TextArea
                            aria-label={t('snTransfer.reason')}
                            maxLength={500}
                            showCount
                          />
                        </Form.Item>
                        <Space wrap>
                          {directAllowed && applyAllowed && (
                            <Select
                              aria-label={t('snTransfer.mode')}
                              value={mode}
                              onChange={setMode}
                              options={[
                                { value: 'DIRECT', label: t('snTransfer.modeDIRECT') },
                                { value: 'APPLY', label: t('snTransfer.modeAPPLY') },
                              ]}
                            />
                          )}
                          <Button
                            type="primary"
                            htmlType="submit"
                            disabled={
                              busy || !candidates.data || candidates.data.transferable === '0'
                            }
                          >
                            {t(
                              mode === 'DIRECT'
                                ? 'snTransfer.directSubmit'
                                : 'snTransfer.applySubmit',
                            )}
                          </Button>
                        </Space>
                      </div>
                    </Form>
                  ),
                },
              ]
            : []),
          {
            key: 'records',
            label: t('snTransfer.records'),
            children: <TransferHistory profile={profile} />,
          },
        ]}
      />
      <Modal
        open={!!confirm}
        title={t(mode === 'DIRECT' ? 'snTransfer.directSubmit' : 'snTransfer.applySubmit')}
        okText={t('users.confirm')}
        cancelText={t('snPages.common.cancel')}
        onCancel={() => setConfirm(undefined)}
        onOk={() => {
          if (confirm) {
            work.begin({ ...confirm, targetSource: sourceType, operationId: crypto.randomUUID() });
            setConfirm(undefined);
          }
        }}
      >
        <p>
          {confirm &&
            t(mode === 'DIRECT' ? 'snTransfer.directConfirm' : 'snTransfer.applyConfirm', {
              n: candidates.data?.transferable,
              factory: confirm.toFactoryId,
              pi: confirm.toPi,
            })}
        </p>
        <Alert type="warning" title={t('snTransfer.labels')} />
        {confirm && (
          <TransferImpactHint
            profile={profile}
            input={{
              factoryId: confirm.factoryId,
              pi: confirm.pi,
              scope: confirm.scope,
              material: confirm.material,
              sn: confirm.sn,
              toPi: confirm.toPi,
              toCustomer: confirm.toCustomer,
            }}
          />
        )}
      </Modal>
      {picker && (
        <TargetPicker
          profile={profile}
          close={() => setPicker(false)}
          choose={(row) => {
            setChosen(row);
            form.setFieldsValue({
              toFactoryId: row.factoryId,
              toPi: row.pi,
              toCustomer: row.customer,
              toMaterial: row.material,
            });
            setSourceType('SNAPSHOT');
            setPicker(false);
          }}
        />
      )}
    </div>
  );
}
function TargetFactoryPicker({
  profile,
  value,
  onChange,
  chosen,
}: {
  profile: UserProfile;
  value?: string;
  onChange?: (v: string) => void;
  chosen?: TransferTarget;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const rows = useInfiniteQuery({
    queryKey: ['sn', 'transfer-factories', profile.id, profile.authorizationVersion, search],
    initialPageParam: 1,
    queryFn: ({ pageParam }) => transferApi.targets(true, search, pageParam),
    getNextPageParam: (p) => (BigInt(p.page * p.size) < BigInt(p.total) ? p.page + 1 : undefined),
  });
  const options = new Map(
    rows.data?.pages.flatMap((p) =>
      p.items.map(
        (r) =>
          [
            r.factoryId,
            { value: r.factoryId, label: `${r.factoryCode} · ${r.factoryName}` },
          ] as const,
      ),
    ),
  );
  if (chosen)
    options.set(chosen.factoryId, {
      value: chosen.factoryId,
      label: `${chosen.factoryCode} · ${chosen.factoryName}`,
    });
  return (
    <div>
      <Select
        aria-label={t('snTransfer.toFactory')}
        value={value}
        onChange={onChange}
        options={[...options.values()]}
        showSearch={{ filterOption: false, onSearch: setSearch }}
        loading={rows.isFetching}
        style={{ width: '100%' }}
      />
      {rows.hasNextPage && (
        <Button
          onClick={() => {
            void rows.fetchNextPage();
          }}
        >
          {t('snPages.more')}
        </Button>
      )}
      <ApiErrorAlert error={rows.error} />
    </div>
  );
}
function TargetPicker({
  profile,
  close,
  choose,
}: {
  profile: UserProfile;
  close: () => void;
  choose: (row: TransferTarget) => void;
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState(''),
    [search, setSearch] = useState(''),
    [page, setPage] = useState(1);
  const rows = useQuery({
    queryKey: ['sn', 'transfer-targets', profile.id, profile.authorizationVersion, search, page],
    queryFn: () => transferApi.targets(false, search, page),
  });
  return (
    <Modal open title={t('snTransfer.fromSnapshot')} onCancel={close} footer={null} width={900}>
      <Space.Compact>
        <Input
          aria-label={t('snTransfer.targetSearch')}
          placeholder={t('snTransfer.targetSearch')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onPressEnter={() => {
            setPage(1);
            setSearch(query);
          }}
        />
        <Button
          onClick={() => {
            setPage(1);
            setSearch(query);
          }}
        >
          {t('snPages.common.search')}
        </Button>
      </Space.Compact>
      <ApiErrorAlert error={rows.error} />
      <Table
        rowKey={(r) => JSON.stringify([r.factoryId, r.pi, r.customer, r.material])}
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        scroll={{ x: 650 }}
        pagination={{
          current: page,
          pageSize: 20,
          total: paginationTotal(rows.data?.total),
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={[
          { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
          { title: 'PI', dataIndex: 'pi' },
          { title: t('snPages.common.customer'), dataIndex: 'customer' },
          { title: t('snPages.common.material'), dataIndex: 'material' },
          {
            title: t('snPages.common.actions'),
            render: (_, r) => (
              <Button type="link" onClick={() => choose(r)}>
                {t('snTransfer.select')}
              </Button>
            ),
          },
        ]}
      />
    </Modal>
  );
}
