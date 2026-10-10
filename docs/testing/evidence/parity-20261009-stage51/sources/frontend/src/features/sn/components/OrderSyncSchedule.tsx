import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Form, InputNumber, Modal, Select, Switch, Tag, Tooltip } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type OrderSchedule } from '../../../api/sn';
import { ApiError, type UserProfile } from '../../../api/contract';
import { syncErrorText } from '../syncErrorText';
import { useSyncFeedback } from '../useSyncFeedback';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { formatSnTime } from '../../../utils/snTime';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';

const units = [
  { key: 'minute', minutes: 1 },
  { key: 'hour', minutes: 60 },
  { key: 'day', minutes: 1440 },
] as const;
type Unit = (typeof units)[number]['key'];
function splitInterval(minutes: number) {
  const unit = [...units].reverse().find((candidate) => minutes % candidate.minutes === 0)!;
  return { amount: minutes / unit.minutes, unit: unit.key };
}

function ScheduleEditor({ data, close }: { data: OrderSchedule; close: () => void }) {
  const { t, i18n } = useTranslation();
  const cache = useQueryClient();
  const [form] = Form.useForm<{ enabled: boolean; amount: number; unit: Unit }>();
  const unit: Unit = Form.useWatch('unit', form) ?? splitInterval(data.intervalMinutes).unit;
  const scale = units.find((candidate) => candidate.key === unit)!.minutes;
  const { feedback, holder, error: showError } = useSyncFeedback();
  const save = useMutation({
    mutationFn: snApi.saveSchedule,
    retry: false,
    onError: showError,
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['sn', 'order-schedule'] });
      void feedback.success({
        className: 'sn-prototype-success-message sn-order-sync-success',
        content: t('prototype:common.saved'),
      });
      close();
    },
  });
  return (
    <Modal
      open
      width={480}
      className="sn-schedule-modal"
      style={{ top: '15vh' }}
      title={t('prototype:orders.sched.dialogTitle')}
      onCancel={close}
      okText={t('prototype:common.save')}
      cancelText={t('prototype:common.cancel')}
      confirmLoading={save.isPending}
      onOk={() => form.submit()}
    >
      {holder}
      <Form
        form={form}
        colon={false}
        labelCol={{ flex: '110px' }}
        initialValues={{ enabled: data.enabled, ...splitInterval(data.intervalMinutes) }}
        onFinish={(values) => {
          const intervalMinutes =
            values.amount * units.find((candidate) => candidate.key === values.unit)!.minutes;
          if (
            !Number.isInteger(intervalMinutes) ||
            intervalMinutes < 10 ||
            intervalMinutes > 10080
          ) {
            void feedback.error({
              className: 'sn-schedule-validation-message',
              content: t('prototype:orders.sched.intervalHint', { min: 10 }),
            });
            return;
          }
          save.mutate({ enabled: values.enabled, intervalMinutes });
        }}
      >
        <Form.Item
          name="enabled"
          label={t('prototype:orders.sched.enabled')}
          valuePropName="checked"
        >
          <Switch aria-label={t('prototype:orders.sched.enabled')} />
        </Form.Item>
        <Form.Item label={t('prototype:orders.sched.interval')}>
          <div className="sn-schedule-interval">
            <Form.Item name="amount" noStyle>
              <InputNumber
                aria-label={t('prototype:orders.sched.interval')}
                min={1}
                max={Math.floor(10080 / scale)}
                precision={0}
              />
            </Form.Item>
            <Form.Item name="unit" noStyle>
              <Select
                virtual={false}
                aria-label={t('shell.intervalUnit')}
                options={units.map((candidate) => ({
                  value: candidate.key,
                  label: t('prototype:orders.sched.unit.' + candidate.key),
                }))}
              />
            </Form.Item>
          </div>
          <div className="sn-muted">{t('prototype:orders.sched.intervalHint', { min: 10 })}</div>
        </Form.Item>
        {data.lastRunAt && (
          <Form.Item label={t('prototype:orders.sched.last')}>
            <span className={data.lastOk === true ? 'sn-schedule-ok' : 'sn-schedule-bad'}>
              {data.lastOk === true
                ? t('prototype:orders.sched.lastOk', {
                    time: formatSnTime(data.lastRunAt),
                    bills: data.lastBills ?? '0',
                    rows: data.lastRows ?? '0',
                  })
                : t('prototype:orders.sched.lastFail', {
                    time: formatSnTime(data.lastRunAt),
                    msg: data.lastErrorCode
                      ? syncErrorText(
                          new ApiError(data.lastErrorCode, 502, '', {
                            message: data.lastErrorMessage,
                          }),
                          i18n.getFixedT('zh-CN'),
                        )
                      : '',
                  })}
            </span>
          </Form.Item>
        )}
        {data.updatedAt && (
          <Form.Item label={t('prototype:orders.sched.updated')}>
            <span className="sn-muted">
              {data.ownerName ?? '—'} · {formatSnTime(data.updatedAt)}
            </span>
          </Form.Item>
        )}
      </Form>
      <Alert type="info" title={t('prototype:orders.sched.note')} />
      {!data.workerEnabled && <Alert type="warning" title={t('snPages.schedule.workerStopped')} />}
    </Modal>
  );
}

/** 原型的摘要与设置弹窗；分钟值仅在提交时换算，关闭弹窗放弃尚未保存的编辑。 */
export function OrderSyncSchedule({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const canSync = profile.permissions.includes('sn:order:sync');
  const result = useQuery({
    queryKey: ['sn', 'order-schedule', profile.id, profile.authorizationVersion],
    queryFn: snApi.schedule,
    enabled: profile.permissions.includes('sn:order:list'),
    refetchInterval: 15000,
  });
  const data = result.data;
  if (!data) return result.error ? <ApiErrorAlert error={result.error} /> : null;
  const interval = splitInterval(data.intervalMinutes);
  return (
    <span className="sn-schedule-summary">
      <span>{t('prototype:orders.sched.title')}：</span>
      <Tag color={data.enabled ? 'success' : 'default'}>
        {data.enabled
          ? t('prototype:orders.sched.every', {
              every: `${interval.amount} ${t('prototype:orders.sched.unit.' + interval.unit)}`,
            })
          : t('prototype:orders.sched.off')}
      </Tag>
      {data.enabled && (
        <span className="sn-muted">
          {t('prototype:orders.sched.next')} {formatSnTime(data.nextRunAt)}
        </span>
      )}
      {data.lastRunAt && (
        <Tooltip
          placement="bottom"
          title={
            data.lastOk === true
              ? t('prototype:orders.sched.lastOk', {
                  time: formatSnTime(data.lastRunAt),
                  bills: data.lastBills ?? '0',
                  rows: data.lastRows ?? '0',
                })
              : t('prototype:orders.sched.lastFail', {
                  time: formatSnTime(data.lastRunAt),
                  msg: data.lastErrorCode
                    ? syncErrorText(
                        new ApiError(data.lastErrorCode, 502, '', {
                          message: data.lastErrorMessage,
                        }),
                        i18n.getFixedT('zh-CN'),
                      )
                    : '',
                })
          }
        >
          <span className={data.lastOk === true ? 'sn-schedule-ok' : 'sn-schedule-bad'}>
            <PrototypeIcon name={data.lastOk === true ? 'CircleCheckFilled' : 'WarningFilled'} />
          </span>
        </Tooltip>
      )}
      {canSync && (
        <Button type="link" icon={<PrototypeIcon name="Setting" />} onClick={() => setOpen(true)}>
          {t('prototype:orders.sched.settings')}
        </Button>
      )}
      {open && <ScheduleEditor data={data} close={() => setOpen(false)} />}
    </span>
  );
}
