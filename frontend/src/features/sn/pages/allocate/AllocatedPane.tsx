import { useQuery } from '@tanstack/react-query';
import { Button, Input, Select, Table, Tabs, Tag, Tooltip } from 'antd';
import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../../api/sn';
import { Pager } from '../../components/Pager';
import { colWidth, textWidth } from '../../lib/colWidth';
import { showError } from '../../lib/notify';
import { rows, str } from '../../lib/row';
import { useFitRows } from '../../lib/useFitRows';
import { usePaged } from '../../lib/usePaged';

const dash = (v: unknown) => (v === null || v === undefined || v === '' ? '—' : String(v));

/**
 * SN 分配页「已分配」页签的右侧（F-14 SN 导出）：左侧点一张单据后，逐枚列出该单据记载的已分配号，按附件项次从小到大；
 * 头部按物料编码、SN 起止号筛选，分页；可按同一筛选下载范围内的号（xlsx），只读、可反复下载。
 * 与分配后的推送、拉取相互独立：推送成败都不影响列出和下载。父组件用 key={pi|bill} 切换单据。
 */
export function AllocatedPane({ pi, bill }: { pi: string; bill: string }) {
  const { t } = useTranslation('sn');
  const box = useRef<HTMLDivElement>(null);
  const fit = useFitRows(box);
  const [material, setMaterial] = useState('');
  const [snFrom, setSnFrom] = useState('');
  const [snTo, setSnTo] = useState('');
  const [downloading, setDownloading] = useState(false);
  const filter = (m = material, a = snFrom, b = snTo) => ({ material: m || undefined, snFrom: a.trim(), snTo: b.trim() });
  const sns = usePaged('allocItems', (q) => snApi.exportItems({ ...q, pi, billNo: bill }), filter(), 20, true, fit);
  const lines = useQuery({ queryKey: ['sn', 'allocLines', pi, bill], queryFn: () => snApi.exportLines({ pi, billNo: bill }) });
  const materials = useMemo(
    () => [...new Set(rows(lines.data).map((l) => str(l.materialCode)).filter(Boolean))],
    [lines.data],
  );
  const piW = colWidth('PI', [pi, ...sns.items.map((r) => str(r.piNo))].map((v) => textWidth(v)));
  const search = () => sns.submit(filter());
  const reset = () => {
    setMaterial('');
    setSnFrom('');
    setSnTo('');
    sns.submit(filter('', '', ''));
  };

  async function download() {
    setDownloading(true);
    try {
      // 与明细同一组筛选：只下载物料编码、SN 起止号范围内的号（不筛选时为整张单据）
      await snDownload.exportFile({
        pi,
        billNo: bill,
        material: material || undefined,
        snFrom: snFrom.trim() || undefined,
        snTo: snTo.trim() || undefined,
      });
    } catch (e) {
      showError(e);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <Tabs
      className="sn-tabs-fill"
      activeKey="sn"
      items={[
        {
          key: 'sn',
          label: t('allocate.snDetail', { bill }),
          children: (
            <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
              <div className="toolbar" style={{ flexWrap: 'nowrap' }}>
                <Select
                  size="small"
                  allowClear
                  style={{ width: 160, flex: 'none' }}
                  placeholder={t('common.material')}
                  value={material || undefined}
                  options={materials.map((m) => ({ value: m, label: m }))}
                  onChange={(v?: string) => {
                    setMaterial(v ?? '');
                    sns.submit(filter(v ?? ''));
                  }}
                />
                <Input
                  size="small"
                  allowClear
                  style={{ width: 180, flex: 'none' }}
                  placeholder={t('export.snFrom')}
                  value={snFrom}
                  onChange={(e) => {
                    setSnFrom(e.target.value);
                    if (!e.target.value) sns.submit(filter(material, ''));
                  }}
                  onPressEnter={search}
                />
                <span className="muted">~</span>
                <Input
                  size="small"
                  allowClear
                  style={{ width: 180, flex: 'none' }}
                  placeholder={t('export.snTo')}
                  value={snTo}
                  onChange={(e) => {
                    setSnTo(e.target.value);
                    if (!e.target.value) sns.submit(filter(material, snFrom, ''));
                  }}
                  onPressEnter={search}
                />
                <Button size="small" type="primary" onClick={search}>
                  {t('common.search')}
                </Button>
                <Button size="small" onClick={reset}>
                  {t('common.reset')}
                </Button>
                <span style={{ flex: 1 }} />
                <Tooltip title={t('export.rangeHint')} placement="top">
                  <Button size="small" loading={downloading} disabled={!sns.total} onClick={() => void download()}>
                    xlsx
                  </Button>
                </Tooltip>
              </div>
              <div ref={box} style={{ flex: 1, minHeight: 240, overflow: 'hidden' }}>
                <Table<Row>
                  size="small"
                  className="fit-table"
                  loading={sns.loading}
                  rowKey={(r) => `${str(r.lineNo)}|${str(r.sn)}`}
                  pagination={false}
                  dataSource={sns.items}
                  locale={{ emptyText: t('common.empty') }}
                  scroll={{ x: 'max-content' }}
                  columns={[
                    { title: 'PI', dataIndex: 'piNo', width: piW },
                    { title: t('common.attachNo'), width: 66, ellipsis: true, render: (_, r) => dash(r.attachNo) },
                    { title: t('common.material'), dataIndex: 'materialCode', width: 86, ellipsis: true },
                    { title: t('common.lineNo'), dataIndex: 'billSeq', width: 72 },
                    {
                      title: t('common.sn'),
                      width: 170,
                      ellipsis: true,
                      render: (_, r) => (
                        <>
                          <span className="sn-mono">{str(r.sn)}</span>
                          {r.transferredIn ? (
                            <Tag variant="outlined" style={{ marginLeft: 6 }}>
                              {t('export.seqOf', { pi: str(r.seqPiNo) })}
                            </Tag>
                          ) : null}
                        </>
                      ),
                    },
                    { title: t('rules.seqDigits'), width: 66, align: 'right', render: (_, r) => dash(r.seqLen) },
                    { title: t('common.seqBase'), width: 66, align: 'right', render: (_, r) => dash(r.base) },
                    { title: t('rules.seqNo'), width: 76, render: (_, r) => <span className="sn-mono">{str(r.seqText)}</span> },
                    {
                      title: t('generate.seqDec'),
                      width: 96,
                      align: 'right',
                      render: (_, r) => <span className="sn-mono">{dash(r.seqDec)}</span>,
                    },
                    { title: t('common.length'), width: 56, ellipsis: true, render: (_, r) => dash(r.length) },
                    { title: t('common.unit'), width: 66, ellipsis: true, render: (_, r) => dash(r.unit) },
                    { title: t('common.factoryCode'), dataIndex: 'factoryCode', width: 90, ellipsis: true },
                    { title: t('common.factory'), dataIndex: 'factoryName', width: 100, ellipsis: true },
                  ]}
                />
              </div>
              <Pager total={sns.total} page={sns.page} pageSize={sns.pageSize} onPage={sns.setPage} />
            </div>
          ),
        },
      ]}
    />
  );
}
