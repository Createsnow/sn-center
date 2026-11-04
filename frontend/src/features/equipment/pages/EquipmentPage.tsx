import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useTranslation } from 'react-i18next';
import {
  equipmentApi,
  type Equipment,
  type EquipmentCreate,
  type EquipmentFilter,
} from '../../../api/equipment';
import type { UserProfile } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

function EquipmentEditor({
  row,
  close,
  done,
}: {
  row?: Equipment;
  close: () => void;
  done: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<EquipmentCreate>();
  const save = useMutation({
    mutationFn: (values: EquipmentCreate) =>
      row
        ? equipmentApi.update(row.id, {
            name: values.name,
            enabled: values.enabled,
            version: row.version,
          })
        : equipmentApi.create(values),
    onSuccess: async () => {
      await done();
      close();
    },
  });
  const required = { required: true, message: t('required') };
  return (
    <Modal
      open
      title={t(row ? 'equipment.edit' : 'equipment.create')}
      onCancel={close}
      footer={null}
      maskClosable={false}
      closable={!save.isPending}
      destroyOnHidden
    >
      <ApiErrorAlert error={save.error} />
      <Form<EquipmentCreate>
        form={form}
        layout="vertical"
        initialValues={row ?? { enabled: true }}
        disabled={save.isPending}
        onFinish={(values) => save.mutate(values)}
        requiredMark={false}
      >
        <Form.Item
          name="code"
          label={t('equipment.code')}
          extra={t('equipment.codeHelp')}
          rules={[
            required,
            { pattern: /^[A-Z0-9][A-Z0-9_-]{0,63}$/, message: t('equipment.codeRule') },
          ]}
        >
          <Input maxLength={64} disabled={!!row} />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('equipment.name')}
          rules={[required, { whitespace: true, message: t('required') }]}
        >
          <Input maxLength={128} />
        </Form.Item>
        <Form.Item name="enabled" label={t('equipment.status')} valuePropName="checked">
          <Switch
            checkedChildren={t('equipment.enabled')}
            unCheckedChildren={t('equipment.disabled')}
          />
        </Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={save.isPending}
            aria-label={t('equipment.save')}
          >
            {t('equipment.save')}
          </Button>
          <Button aria-label={t('equipment.cancel')} onClick={close} disabled={save.isPending}>
            {t('equipment.cancel')}
          </Button>
        </Space>
      </Form>
    </Modal>
  );
}
function EquipmentDetails({
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
    queryKey: ['equipment-detail', profile.id, profile.authorizationVersion, id],
    queryFn: () => equipmentApi.detail(id),
  });
  const row = detail.data;
  return (
    <Modal
      open
      title={t('equipment.detail')}
      onCancel={close}
      footer={<Button onClick={close}>{t('equipment.close')}</Button>}
    >
      <ApiErrorAlert error={detail.error} />
      <Card loading={detail.isPending}>
        {row && (
          <Descriptions
            column={1}
            bordered
            items={[
              { key: 'code', label: t('equipment.code'), children: row.code },
              { key: 'name', label: t('equipment.name'), children: row.name },
              {
                key: 'status',
                label: t('equipment.status'),
                children: t(row.enabled ? 'equipment.enabled' : 'equipment.disabled'),
              },
              { key: 'department', label: t('equipment.department'), children: row.deptId },
              { key: 'owner', label: t('equipment.owner'), children: row.createBy },
              { key: 'version', label: t('equipment.version'), children: row.version },
              {
                key: 'created',
                label: t('equipment.created'),
                children: new Date(row.createTime).toLocaleString(i18n.language),
              },
              {
                key: 'updated',
                label: t('equipment.updated'),
                children: new Date(row.updateTime).toLocaleString(i18n.language),
              },
            ]}
          />
        )}
      </Card>
    </Modal>
  );
}
export function EquipmentPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [filter, setFilter] = useState<EquipmentFilter>({ page: 1, size: 20, keyword: '' });
  const [editor, setEditor] = useState<{ row?: Equipment }>();
  const [detail, setDetail] = useState<string>();
  const [saved, setSaved] = useState(false);
  const key = ['equipment', profile.id, profile.authorizationVersion];
  const list = useQuery({ queryKey: [...key, filter], queryFn: () => equipmentApi.list(filter) });
  const can = (action: string) => profile.permissions.includes('equipment:asset:' + action);
  const total = list.data?.total ?? '0';
  const pageTotal =
    BigInt(total) > BigInt(Number.MAX_SAFE_INTEGER) ? Number.MAX_SAFE_INTEGER : Number(total);
  const edit = (row?: Equipment) => {
    setSaved(false);
    setEditor({ row });
  };
  const done = async () => {
    setSaved(true);
    await cache.invalidateQueries({ queryKey: key });
    await cache.invalidateQueries({ queryKey: ['equipment-detail'] });
  };
  return (
    <section className="equipment-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>{t('equipment.title')}</Typography.Title>
          <Typography.Paragraph type="secondary">{t('equipment.intro')}</Typography.Paragraph>
        </div>
        {can('create') && (
          <Button type="primary" onClick={() => edit()}>
            {t('equipment.create')}
          </Button>
        )}
      </div>
      <ApiErrorAlert error={list.error} />
      {saved && <Alert type="success" showIcon title={t('equipment.saved')} closable />}
      <div className="equipment-toolbar">
        <Input.Search
          aria-label={t('equipment.search')}
          placeholder={t('equipment.search')}
          maxLength={64}
          allowClear
          onSearch={(keyword) => setFilter({ ...filter, keyword, page: 1 })}
        />
        <Select
          aria-label={t('equipment.status')}
          value={filter.enabled === undefined ? 'all' : String(filter.enabled)}
          options={[
            { value: 'all', label: t('equipment.all') },
            { value: 'true', label: t('equipment.enabled') },
            { value: 'false', label: t('equipment.disabled') },
          ]}
          onChange={(value) =>
            setFilter({
              ...filter,
              page: 1,
              enabled: value === 'all' ? undefined : value === 'true',
            })
          }
        />
        <Button onClick={() => void list.refetch()}>{t('audit.refresh')}</Button>
      </div>
      <Table<Equipment>
        rowKey="id"
        loading={list.isFetching}
        dataSource={list.data?.items ?? []}
        scroll={{ x: 700 }}
        locale={{ emptyText: t('equipment.empty') }}
        columns={[
          { title: t('equipment.code'), dataIndex: 'code', width: 180 },
          { title: t('equipment.name'), dataIndex: 'name' },
          {
            title: t('equipment.status'),
            render: (_, row) => (
              <Tag color={row.enabled ? 'green' : 'default'}>
                {t(row.enabled ? 'equipment.enabled' : 'equipment.disabled')}
              </Tag>
            ),
          },
          { title: t('equipment.version'), dataIndex: 'version', width: 90 },
          {
            title: t('equipment.actions'),
            render: (_, row) => (
              <Space>
                {can('detail') && (
                  <Button size="small" onClick={() => setDetail(row.id)}>
                    {t('equipment.detail')}
                  </Button>
                )}
                {can('update') && (
                  <Button size="small" onClick={() => edit(row)}>
                    {t('equipment.edit')}
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
        pagination={{
          current: filter.page,
          pageSize: filter.size,
          total: pageTotal,
          showSizeChanger: true,
          showTotal: () => t('equipment.total', { count: total }),
          onChange: (page, size) => setFilter({ ...filter, page, size }),
        }}
      />
      {editor && (
        <EquipmentEditor
          key={editor.row?.id ?? 'create'}
          row={editor.row}
          close={() => setEditor(undefined)}
          done={done}
        />
      )}
      {detail && (
        <EquipmentDetails id={detail} profile={profile} close={() => setDetail(undefined)} />
      )}
    </section>
  );
}
