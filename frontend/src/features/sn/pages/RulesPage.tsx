import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  AutoComplete,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
} from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import {
  snApi,
  paginationTotal,
  type SnRule,
  type RuleCommand,
  type PreviewCommand,
} from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';

const scopes = ['GENERAL', 'CUSTOMER', 'PI'];
const alphabets: Record<number, string> = {
  10: '0123456789',
  16: '0123456789ABCDEF',
  32: '0123456789ABCDEFGHIJKLMNOPQRSTUV',
  36: '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',
};

function VersionDrawer({
  rule,
  profile,
  close,
}: {
  rule: SnRule;
  profile: UserProfile;
  close: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const list = useQuery({
    queryKey: ['sn', 'rule-versions', profile.id, profile.authorizationVersion, rule.id, page],
    queryFn: () => snApi.versions(rule.id, { page, size: 20 }),
  });
  return (
    <Drawer
      open
      title={t('snPages.rules.versionsOf', { code: rule.code })}
      onClose={close}
      width={560}
    >
      <ApiErrorAlert error={list.error} />
      <Timeline
        items={list.data?.items.map((version) => ({
          color: version.version === rule.currentVersion ? 'blue' : 'gray',
          content: (
            <>
              <b>v{version.version}</b>
              <span className="sn-mono">
                {' '}
                {version.prefix}
                {'#'.repeat(version.length)}
                {version.suffix}
              </span>
              <p className="sn-muted">
                {new Date(version.createdAt).toLocaleString(i18n.language)} ·{' '}
                {t('snPages.rules.base')} {version.base} ·{' '}
                {version.createdBy ?? t('snPages.rules.creatorUnknown')}
              </p>
              {version.used && <Tag color="success">{t('snPages.rules.used')}</Tag>}
            </>
          ),
        }))}
      />
      <Pagination
        current={page}
        pageSize={20}
        total={paginationTotal(list.data?.total)}
        onChange={setPage}
      />
    </Drawer>
  );
}

/** 表单预览仅计算，250ms合并输入；Query按完整参数区分响应，旧预览不会覆盖新表单。 */
function RuleDialog({
  editing,
  profile,
  close,
  done,
}: {
  editing?: SnRule;
  profile: UserProfile;
  close: () => void;
  done: (action: string) => void;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [form] = Form.useForm<RuleCommand>();
  const defaults: RuleCommand = editing
    ? {
        code: editing.code,
        name: editing.name,
        bindScope: editing.bindScope,
        bindValue: editing.bindValue,
        prefix: editing.current.prefix,
        suffix: editing.current.suffix,
        base: editing.current.base,
        length: editing.current.length,
        alphabet:
          editing.current.alphabet === alphabets[editing.current.base]
            ? ''
            : editing.current.alphabet,
        revision: editing.revision,
      }
    : {
        code: '',
        name: '',
        bindScope: 'GENERAL',
        bindValue: '',
        prefix: '',
        suffix: '',
        base: 10,
        length: 5,
        alphabet: '',
      };
  const values = Form.useWatch([], form) ?? defaults;
  const [previewInput, setPreviewInput] = useState<PreviewCommand>({
    prefix: defaults.prefix,
    suffix: defaults.suffix,
    base: defaults.base,
    length: defaults.length,
    alphabet: defaults.alphabet,
    start: '1',
  });
  useEffect(() => {
    const timer = window.setTimeout(
      () =>
        setPreviewInput({
          prefix: values.prefix ?? '',
          suffix: values.suffix ?? '',
          base: values.base ?? 10,
          length: values.length ?? 5,
          alphabet: values.alphabet ?? '',
          start: '1',
        }),
      250,
    );
    return () => window.clearTimeout(timer);
  }, [values.prefix, values.suffix, values.base, values.length, values.alphabet]);
  const preview = useQuery({
    queryKey: ['sn', 'rule-preview', profile.id, profile.authorizationVersion, previewInput],
    queryFn: () => snApi.preview(previewInput),
    retry: false,
    enabled: profile.permissions.includes('sn:rule:preview'),
  });
  const [customerText, setCustomerText] = useState('');
  const [customerPage, setCustomerPage] = useState(1);
  const customers = useQuery({
    queryKey: [
      'sn',
      'customer-options',
      profile.id,
      profile.authorizationVersion,
      customerText,
      customerPage,
    ],
    queryFn: () => snApi.customers({ q: customerText, page: customerPage, size: 50 }),
    enabled: values.bindScope === 'CUSTOMER' && profile.permissions.includes('sn:order:list'),
  });
  const save = useMutation({
    mutationFn: async (value: RuleCommand) => {
      const command = {
        ...value,
        revision: editing?.revision,
        bindValue: value.bindScope === 'GENERAL' ? '' : value.bindValue,
      };
      if (editing) return (await snApi.updateRule(editing.id, command)).action;
      await snApi.createRule(command);
      return 'CREATED';
    },
    onSuccess: async (action) => {
      await cache.invalidateQueries({ queryKey: ['sn'] });
      done(action);
      close();
    },
  });
  return (
    <Modal
      open
      width={640}
      title={
        editing ? t('snPages.rules.editTitle', { code: editing.code }) : t('snPages.rules.create')
      }
      onCancel={close}
      onOk={() => form.submit()}
      confirmLoading={save.isPending}
      okText={t('snPages.common.save')}
      cancelText={t('snPages.common.cancel')}
    >
      <ApiErrorAlert error={save.error} />
      {editing && (
        <Alert
          type="info"
          title={t(editing.current.used ? 'snPages.rules.editUsed' : 'snPages.rules.editUnused')}
        />
      )}
      <Form
        form={form}
        initialValues={defaults}
        layout="vertical"
        onFinish={(value) => save.mutate(value)}
      >
        <Form.Item
          name="code"
          label={t('snPages.rules.code')}
          rules={[{ required: true, pattern: /^[A-Za-z0-9_-]{1,64}$/ }]}
        >
          <Input disabled={!!editing} />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('snPages.rules.name')}
          rules={[{ required: true, whitespace: true, max: 128 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="bindScope" label={t('snPages.rules.bind')}>
          <Radio.Group
            disabled={!!editing}
            optionType="button"
            options={scopes.map((scope) => ({
              value: scope,
              label: t(`snPages.rules.scope${scope}`),
            }))}
          />
        </Form.Item>
        {values.bindScope !== 'GENERAL' && (
          <Form.Item
            name="bindValue"
            label={values.bindScope === 'CUSTOMER' ? t('snPages.common.customer') : 'PI'}
            rules={[{ required: true, whitespace: true, max: 128 }]}
          >
            {values.bindScope === 'CUSTOMER' ? (
              <AutoComplete
                disabled={!!editing}
                options={customers.data?.items.map((customer) => ({ value: customer }))}
                onSearch={(text) => {
                  setCustomerText(text);
                  setCustomerPage(1);
                }}
              />
            ) : (
              <Input disabled={!!editing} />
            )}
          </Form.Item>
        )}
        {values.bindScope === 'CUSTOMER' && !editing && (
          <>
            <ApiErrorAlert error={customers.error} />
            {!!customers.data?.items.length && (
              <Pagination
                size="small"
                current={customerPage}
                pageSize={50}
                total={paginationTotal(customers.data.total)}
                onChange={setCustomerPage}
              />
            )}
          </>
        )}
        <Form.Item name="prefix" label={t('snPages.rules.prefix')}>
          <Input />
        </Form.Item>
        <Form.Item name="suffix" label={t('snPages.rules.suffix')}>
          <Input />
        </Form.Item>
        <Form.Item name="base" label={t('snPages.rules.base')}>
          <Radio.Group
            optionType="button"
            options={[10, 16, 32, 36]}
            onChange={() => form.setFieldValue('alphabet', '')}
          />
        </Form.Item>
        <Form.Item name="length" label={t('snPages.rules.seqLen')} rules={[{ required: true }]}>
          <InputNumber min={1} max={12} precision={0} />
        </Form.Item>
        <Form.Item
          name="alphabet"
          label={t('snPages.rules.charset')}
          extra={t('snPages.rules.charsetHint')}
        >
          <Input className="sn-mono" placeholder={alphabets[values.base ?? 10]} />
        </Form.Item>
        <Form.Item label={t('snPages.rules.sample')}>
          <ApiErrorAlert error={preview.error} />
          {preview.data && (
            <div className="sn-rule-samples">
              {preview.data.samples.map((sample) => (
                <code key={sample.seqDecimal}>{sample.sn}</code>
              ))}
              <span className="sn-muted">
                {t('snPages.rules.maxSeq')} {preview.data.maxSequence}
              </span>
            </div>
          )}
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 独立规则页面，保留旧绑定筛选/编码预览/编辑提示及版本抽屉，不改变规则优先级。 */
export function RulesPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const [scope, setScope] = useState<string>();
  const [q, setQ] = useState('');
  const [dialog, setDialog] = useState<{ editing?: SnRule }>();
  const [versions, setVersions] = useState<SnRule>();
  const [result, setResult] = useState('');
  const list = useQuery({
    queryKey: ['sn', 'rules', profile.id, profile.authorizationVersion, page, scope, q],
    queryFn: () => snApi.rules({ page, size: 20, scope, q }),
  });
  return (
    <section className="sn-page">
      <SnPageHead title={t('snPages.rules.title')} description={t('snPages.rules.desc')}>
        {profile.permissions.includes('sn:rule:create') && (
          <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setDialog({})}>
            {t('snPages.rules.create')}
          </Button>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error} />
      {result && (
        <Alert
          type="success"
          title={
            result === 'CREATED' ? t('snPages.common.saved') : t(`snPages.rules.result${result}`)
          }
        />
      )}
      <div className="sn-surface">
        <div className="sn-toolbar">
          <Select
            aria-label={t('snPages.rules.bind')}
            allowClear
            placeholder={t('snPages.rules.bind')}
            value={scope}
            options={scopes.map((value) => ({ value, label: t(`snPages.rules.scope${value}`) }))}
            onChange={(value) => {
              setScope(value);
              setPage(1);
            }}
          />
          <Input.Search
            aria-label={t('snPages.rules.search')}
            placeholder={t('snPages.rules.search')}
            allowClear
            onSearch={(value) => {
              setQ(value);
              setPage(1);
            }}
          />
        </div>
        <Table
          rowKey="id"
          size="small"
          loading={list.isFetching}
          dataSource={list.data?.items ?? []}
          scroll={{ x: 1100 }}
          columns={[
            {
              title: t('snPages.rules.code'),
              dataIndex: 'code',
              width: 130,
              render: (value: string) => <b>{value}</b>,
            },
            { title: t('snPages.rules.name'), dataIndex: 'name', width: 140 },
            {
              title: t('snPages.rules.bind'),
              width: 170,
              render: (_, row) => (
                <>
                  <Tag
                    color={
                      row.bindScope === 'PI'
                        ? 'error'
                        : row.bindScope === 'CUSTOMER'
                          ? 'warning'
                          : 'default'
                    }
                  >
                    {t(`snPages.rules.scope${row.bindScope}`)}
                  </Tag>
                  {row.bindValue}
                </>
              ),
            },
            {
              title: t('snPages.rules.version'),
              width: 70,
              render: (_, row) => `v${row.currentVersion}`,
            },
            {
              title: t('snPages.rules.format'),
              width: 170,
              render: (_, row) => (
                <span className="sn-mono">
                  {row.current.prefix}
                  <i>{'#'.repeat(row.current.length)}</i>
                  {row.current.suffix}
                </span>
              ),
            },
            { title: t('snPages.rules.base'), width: 70, render: (_, row) => row.current.base },
            {
              title: t('snPages.rules.charset'),
              width: 200,
              render: (_, row) => <span className="sn-mono">{row.current.alphabet}</span>,
            },
            {
              title: t('snPages.rules.used'),
              width: 80,
              render: (_, row) => (
                <Tag color={row.current.used ? 'success' : 'default'}>
                  {t(row.current.used ? 'snPages.common.yes' : 'snPages.common.no')}
                </Tag>
              ),
            },
            {
              title: t('snPages.common.actions'),
              fixed: 'right',
              width: 145,
              render: (_, row) => (
                <Space>
                  {profile.permissions.includes('sn:rule:update') && (
                    <Button type="link" onClick={() => setDialog({ editing: row })}>
                      {t('snPages.common.edit')}
                    </Button>
                  )}
                  <Button type="link" onClick={() => setVersions(row)}>
                    {t('snPages.rules.versions')}
                  </Button>
                </Space>
              ),
            },
          ]}
          pagination={{
            current: page,
            pageSize: 20,
            total: paginationTotal(list.data?.total),
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </div>
      {dialog && (
        <RuleDialog
          editing={dialog.editing}
          profile={profile}
          close={() => setDialog(undefined)}
          done={setResult}
        />
      )}
      {versions && (
        <VersionDrawer rule={versions} profile={profile} close={() => setVersions(undefined)} />
      )}
    </section>
  );
}
