import { Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { OrderLine } from '../../../api/sn';
import { OrderOverflowCell } from './OrderOverflowCell';

/** 序号和数量保持固定宽度；容器余量按物料两列最小宽度分配，舍入余量归第一列。 */
export function OrderLineTable({ rows, loading }: { rows: OrderLine[]; loading: boolean }) {
  const { t } = useTranslation();
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(338);
  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setWidth(Math.max(338, entry.target.clientWidth));
    });
    if (container.current) observer.observe(container.current);
    return () => observer.disconnect();
  }, []);
  const extra = width - 338;
  const nameExtra = Math.floor((extra * 120) / 220);
  return (
    <div ref={container}>
      <Table
        rowKey="id"
        size="small"
        data-testid="order-lines"
        components={{ body: { cell: OrderOverflowCell } }}
        loading={loading}
        dataSource={rows}
        scroll={{ x: width }}
        columns={[
          { title: '#', dataIndex: 'ordinal', width: 48 },
          {
            title: t('snPages.common.material'),
            render: (_, row) =>
              row.material || <span className="sn-muted">{t('snPages.orders.noMaterial')}</span>,
            width: 100 + extra - nameExtra,
          },
          {
            title: t('snPages.orders.materialName'),
            onCell: () => ({ className: 'sn-order-overflow' }),
            width: 120 + nameExtra,
            ellipsis: true,
            render: (_, row) => (
              <>
                <div>{row.materialName}</div>
                {row.demandBillNo && (
                  <div className="sn-muted">
                    {t('snPages.orders.demand')} {row.demandBillNo}
                  </div>
                )}
              </>
            ),
          },
          {
            title: t('snPages.common.qty'),
            dataIndex: 'qty',
            align: 'right',
            width: 70,
            render: (qty: string) => qty.replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1'),
          },
        ]}
        pagination={false}
      />
    </div>
  );
}
