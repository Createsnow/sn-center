const formatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** 原型统一展示业务时区的年月日时分秒，不随访问者的系统时区改变。 */
export function formatSnTime(value?: string | null) {
  return value ? formatter.format(new Date(value)) : '—';
}
