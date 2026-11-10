import { Pagination } from 'antd';
import { useTranslation } from 'react-i18next';

/** 列表底部：封顶提示 + 分页（与原系统 total, sizes, prev, pager, next 相同）。 */
export function Pager(props: {
  total: number;
  page: number;
  pageSize: number;
  capped?: boolean;
  sizes?: number[];
  onPage: (p: number) => void;
  onSize?: (n: number) => void;
}) {
  const { t } = useTranslation('sn');
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
      {props.capped ? <span className="muted">{t('common.capped', { n: props.total })}</span> : <span />}
      <Pagination
        size="small"
        current={props.page}
        pageSize={props.pageSize}
        total={props.total}
        showTotal={(n) => t('common.total', { n })}
        showSizeChanger={!!props.onSize}
        pageSizeOptions={props.sizes ?? [20, 50, 100]}
        onChange={(p, s) => {
          if (s !== props.pageSize && props.onSize) props.onSize(s);
          else props.onPage(p);
        }}
      />
    </div>
  );
}
