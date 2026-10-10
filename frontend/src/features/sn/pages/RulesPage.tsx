import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Table,
  Tag,
  Timeline,
} from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { snApi, type SnRule, type RuleCommand, type PreviewCommand } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { formatSnTime } from '../../../utils/snTime';
import { SnPageHead } from '../components/SnPageHead';
import { normalizeRuleForm, ruleErrorText } from '../ruleErrorText';
import { usePrototypeFeedback } from '../usePrototypeFeedback';

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
  const { t } = useTranslation();
  const list = useQuery({
    queryKey: ['sn', 'rule-versions', profile.id, profile.authorizationVersion, rule.id],
    queryFn: ({ signal }) => snApi.allVersions(rule.id, signal),
  });
  return (
    <Drawer
      open
      title={t('prototype:rules.versionsOf', { code: rule.code })}
      onClose={close}
      width={560}
      rootClassName="sn-rule-versions"
    >
      <ApiErrorAlert error={list.error} />
      <Timeline
        items={list.data?.map((version) => ({
          icon: (
            <span
              className={`sn-rule-version-dot${version.version === rule.currentVersion ? ' current' : ''}`}
            />
          ),
          content: (
            <>
              <div className="sn-rule-version-line">
                <b>v{version.version}</b>
                <span className="sn-mono">
                  {version.prefix}
                  <i>{'#'.repeat(version.length)}</i>
                  {version.suffix}
                </span>
                <span className="sn-muted">
                  {t('prototype:rules.base')} {version.base} · {version.createdByName ?? '—'}
                </span>
                {version.used && <Tag color="success">{t('prototype:rules.used')}</Tag>}
              </div>
              <div className="sn-rule-version-time">{formatSnTime(version.createdAt)}</div>
            </>
          ),
        }))}
      />
    </Drawer>
  );
}

/** 预览仅计算；250ms 合并输入，Query 按完整参数隔离响应，错误内联显示。 */
function RuleDialog({
  editing,
  profile,
  close,
  done,
  failed,
}: {
  editing?: SnRule;
  profile: UserProfile;
  close: () => void;
  done: (action: string) => void;
  failed: (text: string) => void;
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
    placeholderData: (previous) => previous,
    enabled: profile.permissions.includes('sn:rule:preview'),
  });
  const [customerText, setCustomerText] = useState<string>();
  const customers = useQuery({
    queryKey: ['sn', 'rule-customers', profile.id, profile.authorizationVersion, customerText],
    queryFn: ({ signal }) => snApi.ruleCustomers(customerText ?? '', signal),
    enabled:
      !editing &&
      values.bindScope === 'CUSTOMER' &&
      customerText !== undefined &&
      profile.permissions.includes('sn:order:list'),
  });
  const save = useMutation({
    mutationFn: async (value: RuleCommand) => {
      const command = normalizeRuleForm({ ...defaults, ...value });
      if (editing) return (await snApi.updateRule(editing.id, command)).action;
      await snApi.createRule(command);
      return 'CREATED';
    },
    retry: false,
    onError: (error) => failed(ruleErrorText(error, t)),
    onSuccess: (action) => {
      done(action);
      close();
      void cache.invalidateQueries({ queryKey: ['sn'] });
    },
  });
  return (
    <Modal
      open
      width={640}
      style={{ top: '15vh' }}
      className="sn-rule-modal"
      title={
        editing
          ? t('prototype:rules.editTitle', { code: editing.code })
          : t('prototype:rules.create')
      }
      onCancel={close}
      onOk={() => form.submit()}
      confirmLoading={save.isPending}
      okText={t('prototype:common.save')}
      cancelText={t('prototype:common.cancel')}
    >
      {editing && (
        <Alert
          type="info"
          showIcon
          className="sn-rule-edit-hint"
          title={t(
            editing.current.used ? 'prototype:rules.editUsed' : 'prototype:rules.editUnused',
          )}
        />
      )}
      <Form
        form={form}
        initialValues={defaults}
        layout="horizontal"
        labelCol={{ flex: '110px' }}
        wrapperCol={{ flex: '1 1 0' }}
        colon={false}
        onFinish={(value) => save.mutate(value)}
      >
        {!editing && (
          <Form.Item name="code" label={t('prototype:rules.code')} required>
            <Input />
          </Form.Item>
        )}
        <Form.Item name="name" label={t('prototype:rules.name')} required>
          <Input />
        </Form.Item>
        {!editing && (
          <Form.Item name="bindScope" label={t('prototype:rules.bind')} required>
            <Radio.Group
              optionType="button"
              buttonStyle="solid"
              options={scopes.map((scope) => ({
                value: scope,
                label: t(`prototype:rules.scope${scope}`),
              }))}
            />
          </Form.Item>
        )}
        {!editing && values.bindScope !== 'GENERAL' && (
          <Form.Item
            name="bindValue"
            label={values.bindScope === 'CUSTOMER' ? t('prototype:common.customer') : 'PI'}
            required
          >
            {values.bindScope === 'CUSTOMER' ? (
              <Select
                virtual={false}
                showSearch={{ filterOption: false, onSearch: setCustomerText }}
                options={[
                  ...new Set([...(customerText ? [customerText] : []), ...(customers.data ?? [])]),
                ].map((customer) => ({ value: customer }))}
              />
            ) : (
              <Input />
            )}
          </Form.Item>
        )}
        <Form.Item name="prefix" label={t('prototype:rules.prefix')}>
          <Input />
        </Form.Item>
        <Form.Item name="suffix" label={t('prototype:rules.suffix')}>
          <Input />
        </Form.Item>
        <Form.Item name="base" label={t('prototype:rules.base')}>
          <Radio.Group
            optionType="button"
            buttonStyle="solid"
            options={[10, 16, 32, 36]}
            onChange={() => form.setFieldValue('alphabet', '')}
          />
        </Form.Item>
        <Form.Item name="length" label={t('prototype:rules.seqLen')}>
          <InputNumber mode="spinner" min={1} max={12} precision={0} />
        </Form.Item>
        <Form.Item label={t('prototype:rules.charset')}>
          <Form.Item name="alphabet" noStyle>
            <Input className="sn-mono" placeholder={alphabets[values.base ?? 10]} />
          </Form.Item>
          <div className="sn-muted">{t('prototype:rules.charsetHint')}</div>
        </Form.Item>
        <Form.Item label={t('prototype:rules.sample')}>
          {preview.error ? (
            <span className="sn-muted">{ruleErrorText(preview.error, t)}</span>
          ) : (
            preview.data && (
              <div className="sn-rule-samples">
                {preview.data.samples.map((sample) => (
                  <span className="sn-mono" key={sample.seqDecimal}>
                    {sample.sn}
                  </span>
                ))}
                <span className="sn-muted">
                  {t('prototype:rules.maxSeq')}{' '}
                  {new Intl.NumberFormat().format(BigInt(preview.data.maxSequence))}
                </span>
              </div>
            )
          )}
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 全部规则连续展示，筛选与编辑沿用原型；后台分页和规则版本约束保持独立。 */
export function RulesPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const feedback = usePrototypeFeedback();
  const [scope, setScope] = useState<string>();
  const [q, setQ] = useState('');
  const [draft, setDraft] = useState('');
  const [dialog, setDialog] = useState<{ editing?: SnRule }>();
  const [versions, setVersions] = useState<SnRule>();
  const list = useQuery({
    queryKey: ['sn', 'rules', profile.id, profile.authorizationVersion, scope, q],
    queryFn: ({ signal }) => snApi.allRules({ scope, q }, signal),
  });
  return (
    <section className="sn-page sn-rules-page">
      {feedback.holder}
      <SnPageHead title={t('prototype:rules.title')} description={t('prototype:rules.desc')}>
        {profile.permissions.includes('sn:rule:create') && (
          <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setDialog({})}>
            {t('prototype:rules.create')}
          </Button>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error} />
      <div className="sn-surface">
        <div className="sn-toolbar">
          <Select
            aria-label={t('prototype:rules.bind')}
            allowClear
            virtual={false}
            placeholder={t('prototype:rules.bind')}
            value={scope}
            options={scopes.map((value) => ({ value, label: t(`prototype:rules.scope${value}`) }))}
            onChange={(value) => {
              setScope(value);
              setQ(draft);
            }}
          />
          <Input
            aria-label={t('prototype:rules.search')}
            placeholder={t('prototype:rules.search')}
            allowClear
            prefix={<SearchOutlined />}
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value);
              if (!event.target.value) setQ('');
            }}
            onPressEnter={() => {
              if (draft === q) void list.refetch();
              else setQ(draft);
            }}
          />
        </div>
        <Table
          rowKey="id"
          size="small"
          loading={list.isFetching}
          dataSource={list.data ?? []}
          pagination={false}
          scroll={{ x: 1140 }}
          locale={{ emptyText: t('prototype:common.empty') }}
          columns={[
            {
              title: t('prototype:rules.code'),
              dataIndex: 'code',
              width: 120,
              render: (value: string) => <b>{value}</b>,
            },
            { title: t('prototype:rules.name'), dataIndex: 'name', width: 140 },
            {
              title: t('prototype:rules.bind'),
              width: 160,
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
                    {t(`prototype:rules.scope${row.bindScope}`)}
                  </Tag>
                  <span className="sn-rule-binding">{row.bindValue}</span>
                </>
              ),
            },
            {
              title: t('prototype:rules.version'),
              width: 70,
              render: (_, row) => `v${row.currentVersion}`,
            },
            {
              title: t('prototype:rules.format'),
              width: 170,
              render: (_, row) => (
                <span className="sn-mono">
                  {row.current.prefix}
                  <i>{'#'.repeat(row.current.length)}</i>
                  {row.current.suffix}
                </span>
              ),
            },
            { title: t('prototype:rules.base'), width: 70, render: (_, row) => row.current.base },
            {
              title: t('prototype:rules.charset'),
              width: 180,
              ellipsis: true,
              render: (_, row) => (
                <span className="sn-mono" title={row.current.alphabet}>
                  {row.current.alphabet}
                </span>
              ),
            },
            {
              title: t('prototype:rules.used'),
              width: 80,
              render: (_, row) => (
                <Tag color={row.current.used ? 'success' : 'default'}>
                  {t(row.current.used ? 'prototype:common.yes' : 'prototype:common.no')}
                </Tag>
              ),
            },
            {
              title: t('prototype:common.actions'),
              width: 150,
              fixed: 'right',
              render: (_, row) => (
                <div className="sn-rule-actions">
                  {profile.permissions.includes('sn:rule:update') && (
                    <Button type="link" onClick={() => setDialog({ editing: row })}>
                      {t('prototype:common.edit')}
                    </Button>
                  )}
                  <Button type="text" onClick={() => setVersions(row)}>
                    {t('prototype:rules.versions')}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </div>
      {dialog && (
        <RuleDialog
          editing={dialog.editing}
          profile={profile}
          close={() => setDialog(undefined)}
          failed={feedback.error}
          done={(action) =>
            feedback.success(
              t(
                action === 'CREATED' ? 'prototype:common.saved' : `prototype:rules.result${action}`,
              ),
            )
          }
        />
      )}
      {versions && (
        <VersionDrawer rule={versions} profile={profile} close={() => setVersions(undefined)} />
      )}
    </section>
  );
}
