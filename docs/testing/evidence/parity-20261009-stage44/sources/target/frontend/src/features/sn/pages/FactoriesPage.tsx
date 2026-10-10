import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, Modal, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { loadCatalog } from '../../../api/catalog';
import { snApi } from '../../../api/sn';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { factoryErrorText } from '../factoryErrorText';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { SnPageHead } from '../components/SnPageHead';

/** 工厂目录连续展示；取消保留草稿，成功清空，所有校验通过受控服务返回。 */
export function FactoriesPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const feedback = usePrototypeFeedback('sn-factory-feedback');
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ code: string; name: string }>();
  const key = ['sn', 'factory-directory', profile.id, profile.authorizationVersion];
  const list = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => loadCatalog((page) => snApi.factories({ page, size: 50 }), signal),
    retry: false,
  });
  const showError = feedback.error;
  useEffect(() => {
    if (list.error) showError(factoryErrorText(list.error, t));
  }, [list.error, showError, t]);
  const reload = () => cache.invalidateQueries({ queryKey: ['sn'] });
  const create = useMutation({
    mutationFn: snApi.createFactory,
    retry: false,
    onSuccess: async () => {
      feedback.success(t('prototype:common.saved'));
      setOpen(false);
      form.resetFields();
      await reload();
    },
    onError: (error) => feedback.error(factoryErrorText(error, t)),
  });
  const sync = useMutation({
    mutationFn: snApi.syncFactories,
    retry: false,
    onSuccess: async (result) => {
      feedback.success(t('prototype:factories.synced', result));
      if (result.conflicts.length)
        feedback.warning(t('prototype:factories.conflicts', { list: result.conflicts.join('、') }));
      await reload();
    },
    onError: (error) => feedback.error(factoryErrorText(error, t)),
  });
  return (
    <section className="sn-page sn-factories-page">
      {feedback.holder}
      <SnPageHead
        title={t('prototype:factories.title')}
        description={t('prototype:factories.desc')}
      >
        {profile.permissions.includes('sn:factory:create') && (
          <Button icon={<PrototypeIcon name="Plus" />} onClick={() => setOpen(true)}>
            {t('prototype:factories.create')}
          </Button>
        )}
        {profile.permissions.includes('sn:factory:sync') && (
          <Button
            type="primary"
            icon={<PrototypeIcon name="Refresh" />}
            loading={sync.isPending}
            onClick={() => sync.mutate()}
          >
            {t('prototype:factories.sync')}
          </Button>
        )}
      </SnPageHead>
      <div className="sn-surface">
        <Table
          rowKey="id"
          size="small"
          tableLayout="fixed"
          dataSource={list.data ?? []}
          pagination={false}
          locale={{ emptyText: t('prototype:common.empty') }}
          columns={[
            { title: t('prototype:factories.code'), dataIndex: 'code', width: 160 },
            { title: t('prototype:factories.name'), dataIndex: 'name' },
            {
              title: t('prototype:factories.source'),
              width: 120,
              render: (_, row) => (
                <Tag color={row.source === 'K3' ? 'success' : 'warning'}>
                  {t(`prototype:factories.src${row.source}`)}
                </Tag>
              ),
            },
            {
              title: t('prototype:common.time'),
              width: 170,
              render: (_, row) => formatSnTime(row.updatedAt),
            },
          ]}
        />
      </div>
      <Modal
        open={open}
        width={440}
        style={{ top: '15vh' }}
        rootClassName="sn-factory-modal sn-user-modal sn-rule-modal"
        title={t('prototype:factories.create')}
        closeIcon={<PrototypeIcon name="Close" />}
        onCancel={() => setOpen(false)}
        footer={
          <>
            <Button onClick={() => setOpen(false)}>{t('prototype:common.cancel')}</Button>
            <Button type="primary" onClick={() => create.mutate(form.getFieldsValue(true))}>
              {t('prototype:common.save')}
            </Button>
          </>
        }
      >
        <Form
          form={form}
          component="div"
          colon={false}
          labelCol={{ flex: '90px' }}
          wrapperCol={{ flex: '1' }}
          initialValues={{ code: '', name: '' }}
        >
          <Form.Item name="code" label={t('prototype:factories.code')} required>
            <Input />
          </Form.Item>
          <Form.Item name="name" label={t('prototype:factories.name')} required>
            <Input />
          </Form.Item>
        </Form>
        <p className="sn-factory-hint">{t('prototype:factories.nameHint')}</p>
      </Modal>
    </section>
  );
}
