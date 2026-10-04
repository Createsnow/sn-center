import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Form, InputNumber, Switch } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type OrderSchedule } from '../../../api/sn';
import type { UserProfile } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

function ScheduleEditor({ data }: { data: OrderSchedule }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [enabled, setEnabled] = useState(data.enabled);
  const [minutes, setMinutes] = useState<number | null>(data.intervalMinutes);
  const save = useMutation({
    mutationFn: snApi.saveSchedule,
    retry: false,
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['sn', 'order-schedule'] });
    },
  });
  return (
    <>
      <ApiErrorAlert error={save.error} />
      <Form
        layout="inline"
        onFinish={() => {
          if (minutes !== null) save.mutate({ enabled, intervalMinutes: minutes });
        }}
      >
        <Form.Item label={t('snPages.schedule.enabled')}>
          <Switch
            aria-label={t('snPages.schedule.enabled')}
            checked={enabled}
            onChange={setEnabled}
            disabled={save.isPending}
          />
        </Form.Item>
        <Form.Item label={t('snPages.schedule.interval')}>
          <InputNumber
            aria-label={t('snPages.schedule.interval')}
            min={10}
            max={10080}
            precision={0}
            value={minutes}
            onChange={setMinutes}
            disabled={save.isPending}
          />
        </Form.Item>
        <Form.Item>
          <Button
            htmlType="submit"
            type="primary"
            loading={save.isPending}
            disabled={save.isPending || minutes === null || minutes < 10 || minutes > 10080}
          >
            {t('snPages.schedule.save')}
          </Button>
        </Form.Item>
      </Form>
    </>
  );
}

/** 设置与最近结果分别展示；失败仍可显示“最近成功量”，不会把旧成功量当成本次结果。 */
export function OrderSyncSchedule({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const result = useQuery({
    queryKey: ['sn', 'order-schedule', profile.id, profile.authorizationVersion],
    queryFn: snApi.schedule,
    refetchInterval: 15000,
  });
  const data = result.data;
  const time = (value: string | null) =>
    value ? new Date(value).toLocaleString(i18n.language) : '—';
  return (
    <div className="sn-surface" aria-label={t('snPages.schedule.title')}>
      <h3>{t('snPages.schedule.title')}</h3>
      <ApiErrorAlert error={result.error} />
      {data && (
        <>
          <p>{t('snPages.schedule.policy')}</p>
          {!data.workerEnabled && (
            <Alert type="warning" title={t('snPages.schedule.workerStopped')} />
          )}
          <ScheduleEditor key={data.updatedAt ?? 'initial'} data={data} />
          <Descriptions
            size="small"
            column={{ xs: 1, sm: 2, lg: 3 }}
            items={[
              { key: 'next', label: t('snPages.schedule.next'), children: time(data.nextRunAt) },
              { key: 'last', label: t('snPages.schedule.last'), children: time(data.lastRunAt) },
              {
                key: 'state',
                label: t('snPages.syncState'),
                children: data.lastState ? t(`snPages.syncStates.${data.lastState}`) : '—',
              },
              {
                key: 'bills',
                label: t('snPages.schedule.lastBills'),
                children: data.lastBills ?? '—',
              },
              {
                key: 'rows',
                label: t('snPages.schedule.lastRows'),
                children: data.lastRows ?? '—',
              },
              {
                key: 'error',
                label: t('snPages.syncFailure'),
                children: data.lastErrorCode ? t(`errors.${data.lastErrorCode}`) : '—',
              },
            ]}
          />
        </>
      )}
    </div>
  );
}
