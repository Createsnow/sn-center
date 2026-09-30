import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { auditApi, type AuditFilter, type AuditLog } from '../../../api/audit';
import type { UserProfile } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { actionKeys, moduleKeys } from '../auditLabels';
import { AuditExportPanel } from './AuditExportPanel';

function AuditDetails({
  id,
  profile,
  close,
}: {
  id: string;
  profile: UserProfile;
  close: () => void;
}) {
  const { t, i18n } = useTranslation();
  const detail = useQuery({
    queryKey: ['audit-detail', profile.id, profile.authorizationVersion, id],
    queryFn: () => auditApi.detail(id),
  });
  const event = detail.data?.event;
  return (
    <Modal
      open
      width={850}
      title={t('audit.detail')}
      onCancel={close}
      footer={<Button onClick={close}>{t('audit.close')}</Button>}
    >
      <ApiErrorAlert error={detail.error} />
      {event && (
        <>
          <Descriptions
            bordered
            column={1}
            size="small"
            items={[
              { key: 'id', label: t('audit.id'), children: event.id },
              {
                key: 'time',
                label: t('audit.time'),
                children: new Date(event.createTime).toLocaleString(i18n.language),
              },
              {
                key: 'actor',
                label: t('audit.actor'),
                children: event.actorUsername ?? event.userId ?? t('audit.anonymous'),
              },
              {
                key: 'department',
                label: t('audit.department'),
                children: event.actorDeptId ?? t('audit.unknownDepartment'),
              },
              {
                key: 'action',
                label: t('audit.action'),
                children: actionKeys[event.action]
                  ? t('audit.actions.' + actionKeys[event.action])
                  : event.action,
              },
              { key: 'object', label: t('audit.object'), children: event.objectId ?? '—' },
              {
                key: 'result',
                label: t('audit.result'),
                children: t(event.success ? 'audit.succeeded' : 'audit.failed'),
              },
              {
                key: 'type',
                label: t('audit.businessType'),
                children: t('audit.types.' + event.businessType, {
                  defaultValue: event.businessType,
                }),
              },
              {
                key: 'trace',
                label: t('audit.trace'),
                children: (
                  <Typography.Text copyable={!!event.traceId} data-testid="audit-trace">
                    {event.traceId ?? '—'}
                  </Typography.Text>
                ),
              },
              {
                key: 'http',
                label: t('audit.request'),
                children: event.requestMethod
                  ? [event.requestMethod, event.requestRoute, event.httpStatus].join(' ')
                  : '—',
              },
              {
                key: 'reason',
                label: t('audit.reason'),
                children:
                  detail.data?.reason === 'credentialsRejected'
                    ? t('audit.credentialsRejected')
                    : event.errorCode
                      ? t('errors.' + event.errorCode, { defaultValue: String(event.errorCode) })
                      : detail.data?.failureCode
                        ? t('errors.' + detail.data.failureCode, {
                            defaultValue: detail.data.failureCode,
                          })
                        : '—',
              },
            ]}
          />
          {detail.data?.redacted && (
            <Alert style={{ marginTop: 16 }} type="warning" title={t('audit.redacted')} showIcon />
          )}
          <Typography.Title level={5}>{t('audit.changes')}</Typography.Title>
          <Table
            dataSource={detail.data?.changes}
            rowKey="field"
            size="small"
            pagination={false}
            scroll={{ x: 560 }}
            columns={[
              {
                title: t('audit.field'),
                dataIndex: 'field',
                render: (field: string) => t('audit.fields.' + field, { defaultValue: field }),
              },
              {
                title: t('audit.before'),
                dataIndex: 'before',
                render: (value: string | null) => (
                  <Typography.Text style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {value ?? '—'}
                  </Typography.Text>
                ),
              },
              {
                title: t('audit.after'),
                dataIndex: 'after',
                render: (value: string | null) => (
                  <Typography.Text style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {value ?? '—'}
                  </Typography.Text>
                ),
              },
            ]}
          />
        </>
      )}
    </Modal>
  );
}
type FilterValues = Omit<AuditFilter, 'from' | 'to' | 'success'> & {
  fromLocal?: string;
  toLocal?: string;
  success?: 'true' | 'false';
};
export function AuditPage({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const [form] = Form.useForm<FilterValues>();
  const [filter, setFilter] = useState<AuditFilter>({});
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string>();
  const [timeError, setTimeError] = useState(false);
  const data = useQuery({
    queryKey: ['audit', profile.id, profile.authorizationVersion, filter, page],
    queryFn: () => auditApi.list(filter, page, 20),
  });
  const options = useQuery({
    queryKey: ['audit-options', profile.id, profile.authorizationVersion, filter.from, filter.to],
    queryFn: () => auditApi.options(filter),
  });
  const actionLabel = (action: string) =>
    actionKeys[action] ? t('audit.actions.' + actionKeys[action]) : action;
  const moduleLabel = (module: string) =>
    moduleKeys[module] ? t('audit.modules.' + moduleKeys[module]) : module;
  const apply = (values: FilterValues) => {
    const { fromLocal, toLocal, success, ...fields } = values;
    const from = fromLocal ? new Date(fromLocal) : undefined,
      to = toLocal ? new Date(toLocal) : undefined;
    if (
      !!from !== !!to ||
      (from &&
        to &&
        (!Number.isFinite(from.getTime()) ||
          !Number.isFinite(to.getTime()) ||
          from >= to ||
          to.getTime() - from.getTime() > 31 * 86400000))
    ) {
      setTimeError(true);
      return;
    }
    setTimeError(false);
    setPage(1);
    setFilter({
      ...fields,
      success: success === undefined ? undefined : success === 'true',
      from: from?.toISOString(),
      to: to?.toISOString(),
    });
  };
  const count = BigInt(data.data?.total ?? '0');
  return (
    <Card>
      <Typography.Title level={2}>{t('audit.title')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('audit.intro')}</Typography.Paragraph>
      <ApiErrorAlert error={data.error ?? options.error} />
      {timeError && <Alert type="warning" title={t('audit.timeError')} showIcon />}
      <Form<FilterValues> form={form} layout="vertical" onFinish={apply}>
        <div className="audit-filters">
          <Form.Item name="fromLocal" label={t('audit.from')}>
            <Input type="datetime-local" step={1} />
          </Form.Item>
          <Form.Item name="toLocal" label={t('audit.to')}>
            <Input type="datetime-local" step={1} />
          </Form.Item>
          <Form.Item name="actorUsername" label={t('audit.username')}>
            <Input allowClear maxLength={64} />
          </Form.Item>
          <Form.Item
            name="traceId"
            label={t('audit.trace')}
            rules={[
              {
                pattern:
                  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/,
                message: t('audit.traceError'),
              },
            ]}
          >
            <Input allowClear />
          </Form.Item>
          <Form.Item name="action" label={t('audit.action')}>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={options.data?.actions.map((value) => ({ value, label: actionLabel(value) }))}
            />
          </Form.Item>
          <Form.Item name="module" label={t('audit.module')}>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={options.data?.modules.map((value) => ({ value, label: moduleLabel(value) }))}
            />
          </Form.Item>
          <Form.Item name="success" label={t('audit.result')}>
            <Select
              allowClear
              options={[
                { value: 'true', label: t('audit.succeeded') },
                { value: 'false', label: t('audit.failed') },
              ]}
            />
          </Form.Item>
        </div>
        <Space wrap style={{ marginBottom: 16 }}>
          <Button type="primary" htmlType="submit" aria-label={t('audit.search')}>
            {t('audit.search')}
          </Button>
          <Button
            onClick={() => {
              form.resetFields();
              setFilter({});
              setTimeError(false);
              setPage(1);
            }}
          >
            {t('audit.reset')}
          </Button>
          <Button
            onClick={() => {
              void data.refetch();
              void options.refetch();
            }}
          >
            {t('audit.refresh')}
          </Button>
        </Space>
      </Form>
      <Typography.Paragraph>
        {t('audit.total', { total: data.data?.total ?? '0' })}
      </Typography.Paragraph>
      <Table<AuditLog>
        rowKey="id"
        loading={data.isPending}
        dataSource={data.data?.items}
        scroll={{ x: 1000 }}
        pagination={{
          current: page,
          pageSize: 20,
          total: Number(count > 200000n ? 200000n : count),
          showSizeChanger: false,
          onChange: setPage,
        }}
        columns={[
          {
            title: t('audit.time'),
            dataIndex: 'createTime',
            render: (value: string) => new Date(value).toLocaleString(i18n.language),
          },
          {
            title: t('audit.actor'),
            key: 'actor',
            render: (_, row) => row.actorUsername ?? row.userId ?? t('audit.anonymous'),
          },
          { title: t('audit.module'), dataIndex: 'moduleName', render: moduleLabel },
          { title: t('audit.action'), dataIndex: 'action', render: actionLabel },
          {
            title: t('audit.result'),
            key: 'result',
            render: (_, row) => (
              <Tag color={row.success ? 'green' : 'red'}>
                {t(row.success ? 'audit.succeeded' : 'audit.failed')}
              </Tag>
            ),
          },
          {
            title: t('audit.object'),
            dataIndex: 'objectId',
            render: (value: string | null) => value ?? '—',
          },
          {
            title: t('users.actions'),
            key: 'actions',
            render: (_, row) => (
              <Button
                size="small"
                aria-label={t('audit.detail')}
                onClick={() => setSelected(row.id)}
              >
                {t('audit.detail')}
              </Button>
            ),
          },
        ]}
      />
      {selected && (
        <AuditDetails id={selected} profile={profile} close={() => setSelected(undefined)} />
      )}
      {profile.permissions.includes('system:audit:export') && (
        <AuditExportPanel
          key={profile.id + ':' + profile.authorizationVersion}
          profile={profile}
          filter={filter}
        />
      )}
    </Card>
  );
}
