import type { Row } from '../../../../api/sn';
import { str } from '../../lib/row';

/** 转移类型：BILL 转单 / ORG 转组织。 */
export type Kind = 'BILL' | 'ORG';

/** 号段显示：单枚只显示一枚。 */
export function snRange(d: Row): string {
  return !str(d.fromEndSn) || str(d.fromEndSn) === str(d.fromSn) ? str(d.fromSn) : `${str(d.fromSn)} ~ ${str(d.fromEndSn)}`;
}
