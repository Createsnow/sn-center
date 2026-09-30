import { describe, expect, it } from 'vitest';
import { parseSnList, positiveDecimal, suggestedQuantity } from './snInput';
describe('SN旧清单解析与精确数量', () => {
  it('识别BOM标题和第一列，保留字符串精度及大小写', () => {
    expect(parseSnList('\uFEFFSN,备注\r\n"9007199254740993",x\nA\n a \n\nA;重复')).toEqual({
      sns: ['9007199254740993', 'A', 'a'],
      duplicates: ['A'],
    });
  });
  it('只在物理首行跳过标题，重复清单不能静默放行', () => {
    expect(parseSnList('\nSN\nSN')).toEqual({ sns: ['SN'], duplicates: ['SN'] });
  });
  it('数量建议不经过Number且10000可调整', () => {
    expect(suggestedQuantity('9007199254740993', '9007199254740992')).toBe('9007199254740992');
    expect(suggestedQuantity('12001', '15000')).toBe('12001');
    expect(suggestedQuantity('0', '10000')).toBe('1');
  });
  it('正整数不能科学计数、含前导零或超过数据库long', () => {
    expect(positiveDecimal('9007199254740993')).toBe(true);
    expect(positiveDecimal('9223372036854775807')).toBe(true);
    for (const value of ['9223372036854775808', '1e4', '1.5', '01', '0', '-1'])
      expect(positiveDecimal(value)).toBe(false);
  });
});
