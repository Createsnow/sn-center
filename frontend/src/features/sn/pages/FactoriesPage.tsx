import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Modal, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { snApi, paginationTotal, type Factory } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';

function OrganizationDialog({
  factory,
  profile,
  close,
}: {
  factory: Factory;
  profile: UserProfile;
  close: () => void;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [id, setId] = useState<string>();
  const orgs = useQuery({
    queryKey: ['sn', 'organizations', profile.id, profile.authorizationVersion, page, search],
    queryFn: () => snApi.organizations({ page, size: 20, q: search }),
    retry: false,
  });
  const save = useMutation({
    mutationFn: () => snApi.bindOrganization(factory, id ?? ''),
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['sn'] });
      close();
    },
  });
  return (
    <Modal
      open
      width={800}
      title={t('snPages.mapOrganization')}
      onCancel={close}
      onOk={() => save.mutate()}
      okText={t('snPages.common.save')}
      cancelText={t('snPages.common.cancel')}
      okButtonProps={{ disabled: !id }}
      confirmLoading={save.isPending}
    >
      <Alert type="info" title={t('snPages.mapHint')} />
      <p>
        {factory.code} · {factory.name}
      </p>
      <ApiErrorAlert error={orgs.error ?? save.error} />
      <Input.Search
        aria-label={t('snPages.organizationSearch')}
        placeholder={t('snPages.organizationSearch')}
        onSearch={(value) => {
          setSearch(value);
          setPage(1);
        }}
      />
      <Table
        size="small"
        rowKey="id"
        loading={orgs.isFetching}
        dataSource={orgs.data?.items ?? []}
        scroll={{ x: 480 }}
        rowSelection={{
          type: 'radio',
          selectedRowKeys: id ? [id] : [],
          onChange: (keys) => setId(typeof keys[0] === 'string' ? keys[0] : undefined),
          getCheckboxProps: (row) => ({
            disabled: row.factoryId !== null && row.factoryId !== factory.id,
          }),
        }}
        columns={[
          { title: t('snPages.factories.code'), dataIndex: 'code' },
          { title: t('snPages.factories.name'), dataIndex: 'name' },
          {
            title: t('snPages.mapping'),
            render: (_, row) => (row.factoryId ? t('snPages.mapped') : t('snPages.unmapped')),
          },
        ]}
        pagination={{
          current: page,
          pageSize: 20,
          total: paginationTotal(orgs.data?.total),
          onChange: setPage,
        }}
      />
    </Modal>
  );
}

/** 保留旧工厂表、手工弹窗和同步反馈；稳定映射是基线要求的显式追加操作。 */
export function FactoriesPage({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const cache = useQueryClient();
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState(false);
  const [mapping, setMapping] = useState<Factory>();
  const [form] = Form.useForm<{ code: string; name: string }>();
  const list = useQuery({
    queryKey: ['sn', 'factories', profile.id, profile.authorizationVersion, page],
    queryFn: () => snApi.factories({ page, size: 50 }),
  });
  const create = useMutation({
    mutationFn: snApi.createFactory,
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['sn'] });
      setOpen(false);
      form.resetFields();
    },
  });
  const sync = useMutation({
    mutationFn: snApi.syncFactories,
    retry: false,
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['sn'] });
    },
  });
  return (
    <section className="sn-page">
      <SnPageHead title={t('snPages.factories.title')} description={t('snPages.factories.desc')}>
        {profile.permissions.includes('sn:factory:create') && (
          <Button
            icon={<PlusOutlined aria-hidden />}
            onClick={() => {
              create.reset();
              setOpen(true);
            }}
          >
            {t('snPages.factories.create')}
          </Button>
        )}
        {profile.permissions.includes('sn:factory:sync') && (
          <Button
            type="primary"
            icon={<ReloadOutlined aria-hidden />}
            loading={sync.isPending}
            onClick={() => sync.mutate()}
          >
            {t('snPages.factories.sync')}
          </Button>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error ?? sync.error} />
      {sync.data && <Alert type="success" title={t('snPages.factories.synced', sync.data)} />}
      {!!sync.data?.conflicts.length && (
        <Alert
          type="warning"
          title={t('snPages.factories.conflicts', { list: sync.data.conflicts.join('、') })}
        />
      )}
      <div className="sn-surface">
        <Table
          rowKey="id"
          size="small"
          loading={list.isFetching}
          dataSource={list.data?.items ?? []}
          scroll={{ x: 700 }}
          columns={[
            { title: t('snPages.factories.code'), dataIndex: 'code', width: 160 },
            { title: t('snPages.factories.name'), dataIndex: 'name' },
            {
              title: t('snPages.factories.source'),
              render: (_, row) => (
                <Tag color={row.source === 'K3' ? 'success' : 'warning'}>
                  {t(`snPages.factories.src${row.source}`)}
                </Tag>
              ),
              width: 120,
            },
            {
              title: t('snPages.common.time'),
              render: (_, row) => new Date(row.updatedAt).toLocaleString(i18n.language),
              width: 190,
            },
            ...(profile.permissions.includes('sn:factory:map')
              ? [
                  {
                    title: t('snPages.common.actions'),
                    key: 'mapping',
                    render: (_: unknown, row: Factory) => (
                      <Button type="link" onClick={() => setMapping(row)}>
                        {t('snPages.mapOrganization')}
                      </Button>
                    ),
                  },
                ]
              : []),
          ]}
          pagination={{
            current: page,
            pageSize: 50,
            total: paginationTotal(list.data?.total),
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </div>
      <Modal
        open={open}
        title={t('snPages.factories.create')}
        width={440}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={create.isPending}
        okText={t('snPages.common.save')}
        cancelText={t('snPages.common.cancel')}
      >
        <ApiErrorAlert error={create.error} />
        <Form form={form} layout="vertical" onFinish={(value) => create.mutate(value)}>
          <Form.Item
            name="code"
            label={t('snPages.factories.code')}
            rules={[{ required: true, whitespace: true, max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('snPages.factories.name')}
            rules={[{ required: true, whitespace: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
        </Form>
        <p className="sn-muted">{t('snPages.factories.nameHint')}</p>
      </Modal>
      {mapping && (
        <OrganizationDialog
          factory={mapping}
          profile={profile}
          close={() => setMapping(undefined)}
        />
      )}
    </section>
  );
}
