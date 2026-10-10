import { ExclamationCircleFilled, LoadingOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Modal, Radio, Select, Table, Tabs, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { transferApi, type TransferCreate, type TransferSnapshot } from '../../../api/snTransfer';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { TransferHistory } from '../components/TransferHistory';
import { useSnOperation } from '../useSnOperation';
import { useSnContext } from '../useSnContext';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { transferJump } from '../transferJump';

type Fields = Omit<TransferCreate, 'operationId'>;
const clearControl = { clearIcon: <PrototypeIcon name="CircleClose" /> };
const states = [
  ['toAcquire', 'TO_ACQUIRE', 'primary'],
  ['toPrint', 'TO_PRINT', 'warning'],
  ['printed', 'PRINTED', 'success'],
  ['applying', 'APPLYING', 'danger'],
  ['pendingAllocation', 'PENDING_ALLOC', 'info'],
] as const;

/** 候选随来源变化自动读取；只有原任务成功且读取到转厂头才显示提交结果。 */
export function TransferPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const [form] = Form.useForm<Fields>();
  const context = useSnContext(profile);
  const bound =
    context.data?.role === 'factory_operator' && context.data.factories.length === 1
      ? context.data.factories[0]
      : undefined;
  const directAllowed = profile.permissions.includes('sn:transfer:direct');
  const applyAllowed = profile.permissions.includes('sn:transfer:apply');
  const mode = directAllowed ? 'DIRECT' : 'APPLY';
  const [tab, setTab] = useState(directAllowed || applyAllowed ? 'new' : 'records');
  const [picker, setPicker] = useState(false);
  const [chosen, setChosen] = useState<TransferSnapshot>();
  const [targetName, setTargetName] = useState('');
  const [sourceType, setSourceType] = useState('MANUAL');
  const [modal, modalHolder] = Modal.useModal();
  const feedback = usePrototypeFeedback();
  const consumed = useRef(new Set<string>());
  const values = Form.useWatch([], form) as Partial<Fields> | undefined;
  const factoryId = bound?.id ?? values?.factoryId ?? '';
  const pi = values?.pi?.trim() ?? '';
  const scope = values?.scope ?? 'PI';
  const candidateInput = {
    factoryId,
    pi,
    scope,
    material: scope === 'MATERIAL' ? (values?.material ?? '') : undefined,
    sn: scope === 'SINGLE' ? values?.sn?.trim() : undefined,
    toPi: values?.toPi?.trim() || undefined,
  };
  const validSource = !!factoryId && !!pi && (scope !== 'SINGLE' || !!candidateInput.sn);
  const candidates = useQuery({
    queryKey: [
      'sn',
      'transfer-candidates',
      profile.id,
      profile.authorizationVersion,
      candidateInput,
    ],
    queryFn: () => transferApi.candidates(candidateInput),
    enabled: validSource,
    retry: false,
  });
  const hintInput = {
    ...candidateInput,
    toPi: candidateInput.toPi ?? '',
    toCustomer: values?.toCustomer?.trim() ?? '',
  };
  const hintEnabled =
    validSource &&
    !!candidateInput.toPi &&
    candidateInput.toPi !== pi &&
    profile.permissions.includes('sn:transfer:targets');
  const hint = useQuery({
    queryKey: ['sn', 'transfer-impact', profile.id, profile.authorizationVersion, hintInput],
    queryFn: () => transferApi.hint(hintInput),
    enabled: hintEnabled,
    retry: false,
  });
  const impact = transferJump(hint.data);
  const jump = impact ? t('transfer.jumpWarn', { pi: candidateInput.toPi, ...impact }) : '';
  const apply = useSnOperation(profile, 'TRANSFER_APPLY', transferApi.apply);
  const direct = useSnOperation(profile, 'TRANSFER_DIRECT', transferApi.direct);
  const work = mode === 'DIRECT' ? direct : apply;
  const busy = apply.busy || direct.busy;
  const completed = work.task.data;
  const result = useQuery({
    queryKey: [
      'sn',
      'transfer-result',
      profile.id,
      profile.authorizationVersion,
      completed?.resultRef,
    ],
    queryFn: () => transferApi.get(completed!.resultRef!),
    enabled: completed?.state === 'SUCCESS' && !!completed.resultRef,
    retry: false,
  });
  useEffect(() => {
    if (!result.data || !completed || consumed.current.has(completed.operationId)) return;
    // 刷新后由持久操作号恢复同一结果；不要依赖当前组件是否发起过 POST。
    consumed.current.add(completed.operationId);
    feedback.success(
      t('transfer.submitted', {
        no: result.data.transferNo ?? result.data.id,
        n: result.data.quantity,
      }),
    );
    form.setFieldValue('reason', '');
    setTab('records');
    void candidates.refetch();
    work.clear();
  }, [completed, result.data, feedback, t, form, candidates, work]);
  const count = validSource ? candidates.data : undefined;
  const canSubmit =
    !busy &&
    !candidates.isFetching &&
    (!hintEnabled || (!hint.isFetching && !!hint.data && !hint.error)) &&
    !!factoryId &&
    !!pi &&
    !!values?.toFactoryId &&
    !!values.toPi?.trim() &&
    !!values.reason?.trim() &&
    !!count &&
    count.transferable !== '0' &&
    count.duplicateCount === '0';
  const confirm = () => {
    if (!canSubmit) return;
    const input: Fields = {
      factoryId,
      pi,
      scope,
      material: scope === 'MATERIAL' ? (values?.material ?? '') : undefined,
      sn: scope === 'SINGLE' ? (values?.sn?.trim() ?? '') : undefined,
      toFactoryId: values!.toFactoryId!,
      toPi: values!.toPi!.trim(),
      toCustomer: values?.toCustomer?.trim() ?? '',
      toMaterial: values?.toMaterial?.trim() ?? '',
      targetSource: sourceType,
      reason: values!.reason!.trim(),
    };
    modal.confirm({
      title: t(mode === 'DIRECT' ? 'transfer.directSubmit' : 'transfer.applySubmit'),
      icon: null,
      className: 'sn-init-confirm',
      width: 420,
      centered: true,
      closable: true,
      okText: common('snAcquire.confirmButton'),
      cancelText: common('snAcquire.cancelButton'),
      content: (
        <div className="sn-init-confirm-message">
          <ExclamationCircleFilled aria-hidden />
          <span>
            {t(mode === 'DIRECT' ? 'transfer.directConfirm' : 'transfer.applyConfirm', {
              n: count!.transferable,
              factory: targetName || chosen?.factoryName || input.toFactoryId,
              pi: input.toPi,
            })}
            {jump ? ` ${jump}` : ''}
          </span>
        </div>
      ),
      onOk: () => {
        const operationId = crypto.randomUUID();
        work.begin({ ...input, operationId });
      },
    });
  };
  return (
    <div className="sn-page sn-transfer-page">
      {feedback.holder}
      {modalHolder}
      <SnPageHead title={t('transfer.title')} description={t('transfer.desc')} />
      {[apply, direct].map((operation, index) =>
        operation.mutation.error ||
        operation.task.error ||
        operation.task.data?.state === 'FAILED' ? (
          <SnOperation key={index} work={operation} />
        ) : null,
      )}
      {result.error && (
        <>
          <ApiErrorAlert error={result.error} />
          <Button onClick={() => void result.refetch()}>{common('export.check')}</Button>
        </>
      )}
      <Tabs
        type="card"
        className="sn-transfer-tabs"
        activeKey={tab}
        onChange={setTab}
        items={[
          ...(directAllowed || applyAllowed
            ? [
                {
                  key: 'new',
                  label: t(directAllowed ? 'transfer.directTab' : 'transfer.applyTab'),
                  children: (
                    <Form
                      form={form}
                      layout="vertical"
                      initialValues={{
                        scope: 'PI',
                        material: '',
                        toCustomer: '',
                        toMaterial: '',
                        reason: '',
                      }}
                      onValuesChange={(changed: Partial<Fields>) => {
                        if (
                          ['toFactoryId', 'toPi', 'toCustomer', 'toMaterial'].some(
                            (key) => key in changed,
                          )
                        )
                          setSourceType('MANUAL');
                      }}
                      onFinish={confirm}
                    >
                      <div className="sn-transfer-grid">
                        <section>
                          <h4>{t('transfer.source')}</h4>
                          {bound ? (
                            <Form.Item label={t('transfer.fromFactory')} required>
                              <Input
                                disabled
                                value={bound.name}
                                aria-label={t('transfer.fromFactory')}
                              />
                            </Form.Item>
                          ) : (
                            <Form.Item name="factoryId" label={t('transfer.fromFactory')} required>
                              <FactoryPicker profile={profile} />
                            </Form.Item>
                          )}
                          <Form.Item name="pi" label="PI" required>
                            <Input
                              allowClear={clearControl}
                              aria-label={t('transfer.source') + ' PI'}
                              maxLength={128}
                            />
                          </Form.Item>
                          <Form.Item
                            name="scope"
                            label={t('transfer.scope')}
                            required
                            className={scope === 'PI' ? 'sn-transfer-last-field' : undefined}
                          >
                            <Radio.Group
                              optionType="button"
                              buttonStyle="solid"
                              options={['PI', 'MATERIAL', 'SINGLE'].map((value) => ({
                                value,
                                label: t('scope.' + value),
                              }))}
                            />
                          </Form.Item>
                          {scope === 'MATERIAL' && (
                            <Form.Item
                              name="material"
                              label={t('common.material')}
                              required
                              className="sn-transfer-last-field"
                            >
                              <Select
                                aria-label={t('common.material')}
                                showSearch
                                options={(count?.materials ?? []).map((value) => ({
                                  value,
                                  label: value || t('orders.noMaterial'),
                                }))}
                              />
                            </Form.Item>
                          )}
                          {scope === 'SINGLE' && (
                            <Form.Item
                              name="sn"
                              label={t('common.sn')}
                              required
                              className="sn-transfer-last-field"
                            >
                              <Input
                                allowClear={clearControl}
                                className="sn-mono"
                                aria-label="SN"
                                maxLength={64}
                              />
                            </Form.Item>
                          )}
                          <ApiErrorAlert error={validSource ? candidates.error : null} />
                          <ApiErrorAlert error={hintEnabled ? hint.error : null} />
                          {count && (
                            <Alert
                              showIcon
                              icon={
                                <span>
                                  <PrototypeIcon
                                    name={
                                      count.transferable === '0' ? 'WarningFilled' : 'SuccessFilled'
                                    }
                                  />
                                </span>
                              }
                              type={count.transferable === '0' ? 'warning' : 'success'}
                              title={t('transfer.candidates', {
                                ok: count.transferable,
                                total: count.total,
                              })}
                              description={
                                <div className="sn-transfer-counts">
                                  {states
                                    .filter(([key]) => count[key] !== '0')
                                    .map(([key, status, color]) => (
                                      <span key={key}>
                                        <Tag className={'sn-tag-' + color}>
                                          {t('status.' + status)}
                                        </Tag>{' '}
                                        {count[key]}
                                      </span>
                                    ))}
                                </div>
                              }
                            />
                          )}
                          {count && count.duplicateCount !== '0' && (
                            <Alert
                              showIcon
                              type="error"
                              icon={
                                <span>
                                  <PrototypeIcon name="CircleCloseFilled" />
                                </span>
                              }
                              className="sn-transfer-gap"
                              title={t('transfer.dupWarn', {
                                pi: candidateInput.toPi,
                                n: count.duplicateCount,
                                sns: count.duplicateSamples.join('、'),
                              })}
                            />
                          )}
                          {jump && (
                            <Alert
                              showIcon
                              type="warning"
                              icon={
                                <span>
                                  <PrototypeIcon name="WarningFilled" />
                                </span>
                              }
                              className="sn-transfer-gap"
                              title={jump}
                            />
                          )}
                          <p className="sn-muted">{t('transfer.rule')}</p>
                        </section>
                        <section>
                          <h4>
                            {t('transfer.target')}
                            <Tag
                              className={
                                'sn-tag-' + (sourceType === 'SNAPSHOT' ? 'success' : 'warning')
                              }
                            >
                              {t('transfer.src' + sourceType)}
                            </Tag>
                            <Button size="small" onClick={() => setPicker(true)}>
                              {t('transfer.fromSnapshot')}
                            </Button>
                          </h4>
                          <Form.Item name="toFactoryId" label={t('transfer.toFactory')} required>
                            <TargetFactoryPicker
                              profile={profile}
                              chosen={chosen}
                              nameChanged={setTargetName}
                            />
                          </Form.Item>
                          <Form.Item name="toPi" label={t('transfer.toPi')} required>
                            <Input
                              allowClear={clearControl}
                              aria-label={t('transfer.toPi')}
                              maxLength={128}
                            />
                          </Form.Item>
                          <Form.Item name="toCustomer" label={t('transfer.toCustomer')}>
                            <Input
                              allowClear={clearControl}
                              aria-label={t('transfer.toCustomer')}
                              placeholder={t('transfer.keep')}
                              maxLength={64}
                            />
                          </Form.Item>
                          <Form.Item name="toMaterial" label={t('transfer.toMaterial')}>
                            <Input
                              allowClear={clearControl}
                              aria-label={t('transfer.toMaterial')}
                              placeholder={t('transfer.keep')}
                              maxLength={128}
                            />
                          </Form.Item>
                          <Form.Item
                            name="reason"
                            label={t('transfer.reason')}
                            required
                            className="sn-transfer-last-field"
                          >
                            <Input.TextArea
                              aria-label={t('transfer.reason')}
                              rows={3}
                              maxLength={500}
                              showCount
                            />
                          </Form.Item>
                          <Button
                            type="primary"
                            htmlType="submit"
                            disabled={!canSubmit}
                            aria-label={t(
                              mode === 'DIRECT' ? 'transfer.directSubmit' : 'transfer.applySubmit',
                            )}
                            aria-busy={busy}
                            loading={busy ? { icon: <LoadingOutlined aria-hidden /> } : false}
                          >
                            {t(
                              mode === 'DIRECT' ? 'transfer.directSubmit' : 'transfer.applySubmit',
                            )}
                          </Button>
                        </section>
                      </div>
                    </Form>
                  ),
                },
              ]
            : []),
          {
            key: 'records',
            label: t('transfer.records'),
            children: <TransferHistory profile={profile} />,
          },
        ]}
      />
      {(directAllowed || applyAllowed) && (
        <TargetPicker
          profile={profile}
          open={picker}
          close={() => setPicker(false)}
          choose={(row) => {
            setChosen(row);
            setTargetName(row.factoryName ?? '');
            form.setFieldsValue({
              toFactoryId: row.factoryId ?? undefined,
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
  nameChanged,
}: {
  profile: UserProfile;
  value?: string;
  onChange?: (v: string) => void;
  chosen?: TransferSnapshot;
  nameChanged: (name: string) => void;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const rows = useInfiniteQuery({
    queryKey: ['sn', 'transfer-factories', profile.id, profile.authorizationVersion, search],
    initialPageParam: 1,
    queryFn: ({ pageParam }) => transferApi.factories(search, pageParam),
    getNextPageParam: (p) => (BigInt(p.page * p.size) < BigInt(p.total) ? p.page + 1 : undefined),
  });
  const options = new Map(
    rows.data?.pages.flatMap((p) =>
      p.items.map(
        (r) =>
          [
            r.factoryId,
            {
              value: r.factoryId,
              label: `${r.factoryName}（${r.factoryCode}）`,
              name: r.factoryName,
            },
          ] as const,
      ),
    ),
  );
  if (chosen?.factoryId && chosen.factoryCode !== null && chosen.factoryName !== null)
    options.set(chosen.factoryId, {
      value: chosen.factoryId,
      label: `${chosen.factoryName}（${chosen.factoryCode}）`,
      name: chosen.factoryName,
    });
  return (
    <div className="sn-transfer-factory">
      <Select
        aria-label={t('snTransfer.toFactory')}
        placeholder={t('snPages.common.factory')}
        value={value}
        onChange={(id) => {
          onChange?.(id);
          nameChanged(options.get(id)?.name ?? id);
        }}
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
/** 快照搜索在页面内保留；回车重查同一条件，关闭弹窗不丢弃已选候选。 */
function TargetPicker({
  profile,
  open,
  close,
  choose,
}: {
  profile: UserProfile;
  open: boolean;
  close: () => void;
  choose: (row: TransferSnapshot) => void;
}) {
  const { t } = useTranslation('prototype');
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const rows = useQuery({
    queryKey: ['sn', 'transfer-targets', profile.id, profile.authorizationVersion, search],
    queryFn: () => transferApi.targets(search),
    // 同一身份等待新搜索时保留已显示候选；身份或授权版本变化不能沿用旧目录。
    placeholderData: (previous, query) =>
      query?.queryKey[2] === profile.id && query.queryKey[3] === profile.authorizationVersion
        ? previous
        : undefined,
    staleTime: 0,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    retry: false,
  });
  return (
    <Modal
      open={open}
      title={t('transfer.fromSnapshot')}
      onCancel={close}
      footer={null}
      width={820}
      className="sn-transfer-snapshot"
      closeIcon={<PrototypeIcon name="Close" />}
      style={{ top: '15vh' }}
    >
      <Input
        aria-label={t('transfer.targetSearch')}
        placeholder={t('transfer.targetSearch')}
        prefix={<PrototypeIcon name="Search" />}
        allowClear={clearControl}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onPressEnter={() => {
          const next = query.trim();
          if (next === search) void rows.refetch();
          else setSearch(next);
        }}
      />
      <ApiErrorAlert error={rows.error} />
      <Table
        size="small"
        rowKey={(r) => JSON.stringify([r.factoryId, r.orgName, r.pi, r.customer, r.material])}
        dataSource={rows.data?.items}
        scroll={{ x: 660, y: 328 }}
        pagination={false}
        onRow={(row) => ({ onClick: () => choose(row) })}
        locale={{ emptyText: t('common.empty') }}
        columns={[
          {
            title: t('orders.org'),
            dataIndex: 'orgName',
            width: 182,
            render: (name: string, row) => (
              <>
                {name}
                {!row.factoryCode && (
                  <Tag className="sn-transfer-tag sn-transfer-tag-danger">
                    {t('orders.noFactory')}
                  </Tag>
                )}
              </>
            ),
          },
          { title: t('common.customer'), dataIndex: 'customer', width: 100 },
          { title: 'PI', dataIndex: 'pi', width: 206 },
          { title: t('common.material'), dataIndex: 'material', width: 120 },
          { title: t('orders.materialName'), dataIndex: 'materialName', ellipsis: true },
        ]}
      />
    </Modal>
  );
}
