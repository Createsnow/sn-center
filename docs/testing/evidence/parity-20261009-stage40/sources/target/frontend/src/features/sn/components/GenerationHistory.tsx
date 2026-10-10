import { Progress, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SnWork } from '../../../api/snWork';
import { formatSnTime } from '../../../utils/snTime';
import { generationPercent } from '../generationProgress';
import { generationFailureText } from '../generationErrorText';

/** 原型生成最近二十条、分配全部记录；后台 UUID 不作为用户任务编号。 */
export function GenerationHistory({
  rows,
  allocations = false,
}: {
  rows: SnWork[];
  allocations?: boolean;
}) {
  const { t } = useTranslation('prototype');
  const { i18n } = useTranslation();
  return (
    <Table<SnWork>
      size="small"
      rowKey="taskId"
      dataSource={rows}
      pagination={false}
      locale={{ emptyText: t('common.empty') }}
      scroll={{ x: 790 }}
      columns={[
        allocations
          ? { title: t('common.factory'), dataIndex: 'factoryCode', width: 110 }
          : {
              title: '#',
              dataIndex: 'generationNumber',
              width: 60,
              render: (v: string | null) => v ?? '—',
            },
        {
          title: t('generate.segment'),
          key: 'range',
          minWidth: 220,
          render: (_, row) => (
            <span className="sn-mono">
              {row.firstSn ?? '—'} ~ {row.lastSn ?? '—'}
            </span>
          ),
        },
        { title: t('common.qty'), dataIndex: 'quantity', width: 80, align: 'right' },
        ...(!allocations
          ? [
              {
                title: t('generate.progress'),
                key: 'progress',
                width: 200,
                render: (_: unknown, row: SnWork) => (
                  <Progress
                    percent={generationPercent(row)}
                    status={
                      row.state === 'SUCCESS'
                        ? 'success'
                        : row.state === 'FAILED'
                          ? 'exception'
                          : 'normal'
                    }
                  />
                ),
              },
              {
                title: t('generate.error'),
                dataIndex: 'errorCode',
                ellipsis: true,
                // 原型历史保存服务端中文原因；切换页面语言不改写已经发生的失败记录。
                render: (code: number | null, row: SnWork) =>
                  generationFailureText(code, row.errorParams, i18n.getFixedT('zh-CN')),
              },
            ]
          : []),
        {
          title: t('common.operator'),
          dataIndex: 'actorName',
          width: 90,
          render: (name: string | null) => name ?? '—',
        },
        { title: t('common.time'), dataIndex: 'createdAt', width: 160, render: formatSnTime },
      ]}
    />
  );
}
