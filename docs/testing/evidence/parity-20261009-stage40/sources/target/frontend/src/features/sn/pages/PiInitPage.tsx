import { ExclamationCircleFilled } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Form, Input, Modal, Table, Tag } from 'antd';
import { useEffect, useEffectEvent, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type UserProfile } from '../../../api/contract';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { loadCatalog } from '../../../api/catalog';
import { snWorkApi, type PiStatus, type SnWork } from '../../../api/snWork';
import type { Task } from '../../../api/tasks';
import { formatSnTime } from '../../../utils/snTime';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { useSnOperation } from '../useSnOperation';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { syncErrorText } from '../syncErrorText';
import { initializationErrorText } from '../initializationErrorText';
import { normalizeStartSequence, parseSnList } from '../snInput';

const imports = (pi: string) =>
  loadCatalog((page) => snWorkApi.imports(pi, page), new AbortController().signal);
const readInitialization = async (pi: string) => {
  const [status, history] = await Promise.all([snWorkApi.status(pi), imports(pi)]);
  return { status, history: history.filter((row) => row.state === 'SUCCESS') };
};

/** 按原型保留上次查询和表单；完成后刷新当前查询，持久操作仅在未知结果时提供恢复入口。 */
export function PiInitPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const feedback = usePrototypeFeedback();
  const [modal, modalHolder] = Modal.useModal();
  const [search, setSearch] = useState(''),
    [start, setStart] = useState('');
  const [text, setText] = useState(''),
    [filename, setFilename] = useState('');
  const [customer, setCustomer] = useState(''),
    [material, setMaterial] = useState('');
  const [factory, setFactory] = useState<string>();
  const [current, setCurrent] = useState<{ status: PiStatus; history: SnWork[] }>();
  const upload = useRef<HTMLInputElement>(null);
  const submitted = useRef<{ id: string; pi: string } | null>(null);
  const handled = useRef(new Set<string>()),
    errors = useRef(new WeakSet<object>());
  const cleared = useRef(new Set<string>()),
    notified = useRef(new Set<string>());
  const errorText = (error: unknown) =>
    error instanceof ApiError && error.status === 0
      ? common('snPages.piInit.networkError')
      : initializationErrorText(error, common);
  const work = useSnOperation(profile, 'PI_INIT', snWorkApi.initialize);
  const lookup = useMutation({
    mutationFn: readInitialization,
    onSuccess: setCurrent,
    onError: (error) => feedback.error(errorText(error)),
  });
  const value = current?.status;
  const parsed = useMemo(() => parseSnList(text), [text]);
  const valid = (start.trim() !== '' || parsed.sns.length > 0) && !parsed.duplicates.length;
  const retryable =
    work.canRetry &&
    !work.mutation.isPending &&
    !work.task.data &&
    work.task.error instanceof ApiError &&
    work.task.error.status === 404 &&
    !!work.mutation.variables;
  // 任务终态与结果读取分别处理；查询失败仍可读取原结果，禁止创建另一个导入意图。
  const completion = useMutation({
    onMutate: (task: Task) => {
      if (
        submitted.current?.id === task.operationId &&
        task.state === 'SUCCESS' &&
        !cleared.current.has(task.taskId)
      ) {
        cleared.current.add(task.taskId);
        setText('');
        setStart('');
      }
    },
    mutationFn: async (task: Task) => {
      const submission = submitted.current;
      if (!submission || submission.id !== task.operationId) return null;
      if (task.state === 'SUCCESS' || task.state === 'FAILED') {
        const result = (await imports(submission.pi)).find((row) => row.taskId === task.taskId);
        if (!result || result.state !== task.state)
          throw new Error('Initialization result unavailable');
        return { result };
      }
      return { result: null };
    },
    onSuccess: async (outcome, task) => {
      if (outcome?.result && task.state === 'SUCCESS') {
        const result = outcome.result;
        if (!notified.current.has(task.taskId)) {
          notified.current.add(task.taskId);
          feedback.success(
            t('piInit.done', {
              n: result.quantity,
              decoded: result.decodedQty,
              last: result.lastSeq,
            }),
          );
        }
        if (search.trim()) setCurrent(await readInitialization(search.trim()));
      } else if (outcome && task.state === 'FAILED') {
        feedback.error(
          errorText(
            new ApiError(
              outcome.result?.errorCode ?? task.errorCode ?? 1000000000,
              409,
              '',
              outcome.result?.errorParams ?? {},
            ),
          ),
        );
      }
      work.clear();
      submitted.current = null;
    },
    onError: (error) => feedback.error(errorText(error)),
  });
  const searchPi = () => {
    if (completion.isError && completion.variables) completion.mutate(completion.variables);
    else if (search.trim()) lookup.mutate(search.trim());
  };
  const finished = useEffectEvent((task: Task) => completion.mutate(task));
  useEffect(() => {
    const task = work.task.data;
    if (
      task &&
      ['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.state) &&
      !handled.current.has(task.taskId)
    ) {
      handled.current.add(task.taskId);
      void finished(task);
    }
  }, [work.task.data]);
  const rejected = useEffectEvent((error: Error) => {
    feedback.error(errorText(error));
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      work.clear();
      submitted.current = null;
    }
  });
  useEffect(() => {
    const error = work.mutation.error;
    if (error && !errors.current.has(error)) {
      errors.current.add(error);
      rejected(error);
    }
  }, [work.mutation.error]);
  const confirm = () => {
    if (
      !value ||
      (!valid && !retryable) ||
      (work.busy && !retryable) ||
      completion.isPending ||
      completion.isError
    )
      return;
    const normalized = normalizeStartSequence(start);
    if (!retryable && start.trim() && normalized === undefined) {
      feedback.error(t('generate.startInvalid'));
      return;
    }
    // 未知响应只允许重发原意图；确认框使用原载荷，不能误报用户后来编辑的值。
    const command = retryable
      ? work.mutation.variables!
      : {
          operationId: crypto.randomUUID(),
          pi: value.pi,
          start: normalized,
          sns: parsed.sns,
          customer,
          material,
          factoryId: factory,
          fileName: filename,
        };
    modal.confirm({
      title: t('piInit.submit'),
      content: (
        <div className="sn-init-confirm-message">
          <ExclamationCircleFilled aria-hidden="true" />
          <span>
            {t('piInit.confirm', {
              pi: command.pi,
              start: command.start ?? '—',
              n: command.sns.length,
            })}
          </span>
        </div>
      ),
      icon: null,
      width: 420,
      centered: true,
      closable: true,
      maskClosable: true,
      className: 'sn-generation-confirm sn-init-confirm',
      okText: common('snPages.piInit.confirmButton'),
      cancelText: t('common.cancel'),
      onOk: () => {
        if (retryable) work.retry();
        else {
          submitted.current = { id: command.operationId, pi: command.pi };
          work.begin(command);
        }
      },
    });
  };
  const rule = value?.rule;
  const sample = rule
    ? rule.prefix +
      Array.from(rule.alphabet)[0]!.repeat(rule.length - 1) +
      Array.from(rule.alphabet)[1]! +
      rule.suffix
    : '';
  return (
    <div className="sn-page sn-init-page">
      {feedback.holder}
      {modalHolder}
      <SnPageHead title={t('piInit.title')} description={t('piInit.desc')} />
      <section className="sn-surface">
        <div className="sn-toolbar">
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onPressEnter={searchPi}
            allowClear
            aria-label="PI"
            placeholder={t('piInit.piPlaceholder')}
            className="sn-init-search"
          />
          <Button
            type="primary"
            aria-label={t('common.search')}
            loading={lookup.isPending || completion.isPending}
            onClick={searchPi}
          >
            {t('common.search')}
          </Button>
        </div>
        {value && (
          <>
            <Descriptions
              bordered
              size="small"
              column={3}
              items={[
                { key: 'pi', label: 'PI', children: <b>{value.pi}</b> },
                {
                  key: 'snapshot',
                  label: t('piInit.inSnapshot'),
                  children: (
                    <Tag color={value.inSnapshot ? 'green' : 'orange'}>
                      {t(value.inSnapshot ? 'common.yes' : 'common.no')}
                    </Tag>
                  ),
                },
                { key: 'customer', label: t('common.customer'), children: value.customer || '—' },
                { key: 'last', label: t('generate.lastSeq'), children: value.counter.lastSeq },
                {
                  key: 'generated',
                  label: t('generate.piGenerated'),
                  children: value.counter.generatedQty,
                },
                {
                  key: 'imported',
                  label: t('generate.piImported'),
                  children: value.counter.importedQty,
                },
                {
                  key: 'rule',
                  label: t('generate.rule'),
                  span: 3,
                  children: rule ? (
                    <span className="sn-mono">
                      {value.ruleCode} v{rule.version} · {sample}
                    </span>
                  ) : (
                    <span className="sn-muted">{t('piInit.noRule')}</span>
                  ),
                },
              ]}
            />
            {value.counter.startLocked && (
              <Alert
                className="sn-init-locked"
                type="info"
                showIcon
                title={t('piInit.locked', { start: value.counter.startSeq ?? '—' })}
              />
            )}
          </>
        )}
      </section>
      {value && !value.counter.startLocked && (
        <section className="sn-surface">
          <div className="sn-surface-title">{t('piInit.formTitle')}</div>
          <Form layout="vertical" className="sn-init-form">
            <Form.Item label={t('generate.start')}>
              <Input
                value={start}
                onChange={(event) => setStart(event.target.value)}
                allowClear
                aria-label={t('generate.start')}
                placeholder={t('piInit.startPlaceholder')}
              />
              <div className="sn-muted">{t('piInit.startHint')}</div>
            </Form.Item>
            <Form.Item label={t('piInit.snList')}>
              <Input.TextArea
                value={text}
                onChange={(event) => setText(event.target.value)}
                rows={8}
                aria-label={t('piInit.snList')}
                placeholder={t('piInit.snPlaceholder')}
              />
              <div className="sn-init-file-row">
                <Button
                  size="small"
                  aria-label={t('piInit.upload')}
                  icon={<PrototypeIcon name="Upload" />}
                  onClick={() => upload.current?.click()}
                >
                  {t('piInit.upload')}
                </Button>
                <input
                  ref={upload}
                  hidden
                  type="file"
                  accept=".txt,.csv"
                  aria-label={t('piInit.upload')}
                  onChange={async (event) => {
                    const file = event.target.files?.[0];
                    if (!file) return;
                    setFilename(file.name);
                    try {
                      setText(await file.text());
                    } catch (error) {
                      feedback.error(syncErrorText(error, common));
                    }
                    event.target.value = '';
                  }}
                />
                <span className="sn-muted">{t('piInit.parsed', { n: parsed.sns.length })}</span>
                {parsed.duplicates.length > 0 && (
                  <Tag color="red">{t('piInit.dups', { n: parsed.duplicates.length })}</Tag>
                )}
              </div>
            </Form.Item>
            <Form.Item label={t('piInit.factory')}>
              <FactoryPicker profile={profile} value={factory} onChange={setFactory} />
            </Form.Item>
            <Form.Item label={t('piInit.customer')}>
              <Input
                value={customer}
                onChange={(event) => setCustomer(event.target.value)}
                allowClear
                aria-label={t('piInit.customer')}
                placeholder={value.customer}
              />
            </Form.Item>
            <Form.Item label={t('piInit.material')}>
              <Input
                value={material}
                onChange={(event) => setMaterial(event.target.value)}
                allowClear
                aria-label={t('piInit.material')}
              />
            </Form.Item>
          </Form>
          <Button
            type="primary"
            aria-label={t('piInit.submit')}
            disabled={
              (!valid && !retryable) ||
              completion.isError ||
              !profile.permissions.includes('sn:pi-init:execute')
            }
            loading={(work.busy && !retryable) || completion.isPending}
            onClick={confirm}
          >
            {t('piInit.submit')}
          </Button>
        </section>
      )}
      {work.operation && !retryable && (work.mutation.error || work.task.error) && (
        <SnOperation work={work} />
      )}
      {!!current?.history.length && (
        <section className="sn-surface sn-init-history">
          <div className="sn-surface-title">{t('piInit.history')}</div>
          <Table<SnWork>
            size="small"
            rowKey="taskId"
            dataSource={current.history}
            pagination={false}
            tableLayout="fixed"
            columns={[
              { title: t('generate.start'), dataIndex: 'startSeq' },
              { title: t('piInit.importQty'), dataIndex: 'quantity' },
              { title: t('piInit.maxSeq'), dataIndex: 'maxSeq' },
              { title: t('common.factory'), dataIndex: 'factoryCode' },
              { title: t('piInit.file'), dataIndex: 'fileName' },
              { title: t('common.operator'), dataIndex: 'actorName' },
              { title: t('common.time'), dataIndex: 'createdAt', width: 170, render: formatSnTime },
            ]}
          />
        </section>
      )}
    </div>
  );
}
