import { Tag } from 'antd';
import { STATUS_COLOR, statusLabel } from '../lib/labels';

export function StatusTag({ status }: { status: unknown }) {
  const s = typeof status === 'string' ? status : String(status ?? '');
  return <Tag color={STATUS_COLOR[s] ?? 'default'}>{statusLabel(s)}</Tag>;
}
