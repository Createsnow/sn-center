import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Form, Input, Pagination, Popconfirm, Typography } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import type { UserProfile } from '../../../api/contract';
import { snWorkApi } from '../../../api/snWork';
import { paginationTotal } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { useSnOperation } from '../useSnOperation';
import { SnCounter, SnRuleDetails, SnWorkTable } from '../components/SnWorkViews';
import { parseSnList, positiveDecimal } from '../snInput';

export function PiInitPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const pi = params.get('pi') ?? '';
  const [search, setSearch] = useState(pi),
    [start, setStart] = useState(''),
    [text, setText] = useState(''),
    [customer, setCustomer] = useState(''),
    [material, setMaterial] = useState(''),
    [factory, setFactory] = useState<string>(),
    [filename, setFilename] = useState(''),
    [fileError, setFileError] = useState(''),
    [page, setPage] = useState(1);
  const key = ['sn', 'pi-init', profile.id, profile.authorizationVersion, pi];
  const status = useQuery({ queryKey: key, queryFn: () => snWorkApi.status(pi), enabled: !!pi });
  const history = useQuery({
    queryKey: [...key, 'history', page],
    queryFn: () => snWorkApi.imports(pi, page),
    enabled: !!pi,
  });
  const work = useSnOperation(profile, 'PI_INIT', snWorkApi.initialize);
  const parsed = parseSnList(text);
  const value = status.data;
  const valid =
    (start === '' || positiveDecimal(start)) &&
    (!!start || parsed.sns.length > 0) &&
    parsed.duplicates.length === 0 &&
    !fileError;
  const submit = () =>
    work.begin({
      operationId: crypto.randomUUID(),
      pi,
      start: start || undefined,
      sns: parsed.sns,
      customer,
      material,
      factoryId: factory,
      fileName: filename,
    });
  return (
    <div className="sn-page">
      <SnPageHead title={t('snPages.piInit.title')} description={t('snPages.piInit.desc')} />
      <div className="sn-toolbar">
        <Input.Search
          style={{ width: 300, maxWidth: '100%' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('snPages.piInit.piPlaceholder')}
          aria-label="PI"
          enterButton={t('snPages.common.search')}
          onSearch={(v) => {
            setParams({ pi: v.trim() });
            setPage(1);
          }}
        />
      </div>
      <ApiErrorAlert error={status.error ?? history.error} />
      {value && (
        <>
          <section className="sn-surface">
            <Descriptions
              bordered
              size="small"
              column={{ xs: 1, sm: 3 }}
              items={[
                { key: 'pi', label: 'PI', children: <b>{value.pi}</b> },
                {
                  key: 'snapshot',
                  label: t('snPages.piInit.inSnapshot'),
                  children: t(value.inSnapshot ? 'snPages.common.yes' : 'snPages.common.no'),
                },
                {
                  key: 'customer',
                  label: t('snPages.common.customer'),
                  children: value.customer || '—',
                },
              ]}
            />
            <div className="sn-generation-grid">
              <SnCounter value={value.counter} />
              <SnRuleDetails rule={value.rule} details={value} singleColumn />
            </div>
          </section>
          {value.counter.startLocked ? (
            <Alert
              type="info"
              showIcon
              title={t('snPages.piInit.locked', { start: value.counter.startSeq ?? '—' })}
            />
          ) : (
            <section className="sn-surface">
              <Typography.Title level={4}>{t('snPages.piInit.formTitle')}</Typography.Title>
              <Form layout="vertical" className="sn-work-form">
                <Form.Item
                  label={t('snPages.generate.start')}
                  help={t('snPages.piInit.startHint')}
                  validateStatus={start && !positiveDecimal(start) ? 'error' : undefined}
                >
                  <Input
                    aria-label={t('snPages.generate.start')}
                    value={start}
                    onChange={(e) => setStart(e.target.value)}
                    placeholder={t('snPages.piInit.startPlaceholder')}
                    inputMode="numeric"
                  />
                </Form.Item>
                <Form.Item label={t('snPages.piInit.snList')}>
                  <Input.TextArea
                    aria-label={t('snPages.piInit.snList')}
                    rows={8}
                    value={text}
                    onChange={(e) => {
                      setText(e.target.value);
                      setFilename('');
                      setFileError('');
                    }}
                    placeholder={t('snPages.piInit.snPlaceholder')}
                  />
                  <label className="sn-file-label">
                    {t('snPages.piInit.upload')}
                    <input
                      type="file"
                      aria-label={t('snPages.piInit.upload')}
                      accept=".csv,.txt"
                      onChange={async (e) => {
                        const file = e.target.files?.[0];
                        if (!file) return;
                        try {
                          if (file.size > 8 * 1024 * 1024 || !/\.(csv|txt)$/i.test(file.name))
                            throw new Error('file');
                          const content = new TextDecoder('utf-8', { fatal: true }).decode(
                            await file.arrayBuffer(),
                          );
                          setText(content);
                          setFilename(file.name);
                          setFileError('');
                        } catch {
                          setFileError(t('snPages.work.fileInvalid'));
                        }
                      }}
                    />
                  </label>
                  <p className="sn-muted">{t('snPages.piInit.parsed', { n: parsed.sns.length })}</p>
                  {parsed.duplicates.length > 0 && (
                    <Alert
                      type="error"
                      title={t('snPages.piInit.dups', { n: parsed.duplicates.length })}
                    />
                  )}
                  {fileError && <Alert type="error" title={fileError} />}
                </Form.Item>
                <Form.Item label={t('snPages.piInit.factory')}>
                  <FactoryPicker profile={profile} value={factory} onChange={setFactory} />
                </Form.Item>
                <Form.Item label={t('snPages.piInit.customer')}>
                  <Input
                    aria-label={t('snPages.piInit.customer')}
                    value={customer}
                    maxLength={64}
                    onChange={(e) => setCustomer(e.target.value)}
                  />
                </Form.Item>
                <Form.Item label={t('snPages.piInit.material')}>
                  <Input
                    aria-label={t('snPages.piInit.material')}
                    value={material}
                    maxLength={128}
                    onChange={(e) => setMaterial(e.target.value)}
                  />
                </Form.Item>
                <Popconfirm
                  title={t('snPages.piInit.confirm', {
                    pi,
                    start: start || '—',
                    n: parsed.sns.length,
                  })}
                  onConfirm={submit}
                  okText={t('users.confirm')}
                  cancelText={t('snPages.common.cancel')}
                  disabled={!valid || work.busy}
                >
                  <Button
                    type="primary"
                    disabled={
                      !profile.permissions.includes('sn:pi-init:execute') ||
                      !valid ||
                      work.busy ||
                      !!value.counter.activeTaskId
                    }
                    loading={work.mutation.isPending}
                  >
                    {t('snPages.piInit.submit')}
                  </Button>
                </Popconfirm>
              </Form>
            </section>
          )}
        </>
      )}
      <SnOperation work={work} />
      {pi && (
        <section className="sn-surface">
          <Typography.Title level={4}>{t('snPages.piInit.history')}</Typography.Title>
          <SnWorkTable rows={history.data?.items ?? []} initialization />
          <Pagination
            current={page}
            pageSize={20}
            total={paginationTotal(history.data?.total)}
            onChange={setPage}
            showSizeChanger={false}
          />
        </section>
      )}
    </div>
  );
}
