import { describe, expect, it } from 'vitest';
import { transferJump } from './transferJump';
const before = {
  ownLastSeq: '0',
  startSeq: '1',
  startSn: 'G0001',
  adjusted: false,
  bySeq: null,
  bySn: null,
};
const impact = {
  available: true,
  ruleCode: 'G',
  ruleSource: 'GENERAL',
  before,
  after: { ...before, startSeq: '14', startSn: 'G0014' },
  unused: '0',
};
describe('transfer jump hint', () => {
  it('shows a changed start even when every skipped position is filled by incoming SNs', () => {
    expect(transferJump(impact)).toEqual({ before: 'G0001', after: 'G0014', unused: '0' });
  });
  it('does not reveal restricted or unchanged target counters', () => {
    expect(transferJump({ ...impact, available: false })).toBeUndefined();
    expect(transferJump({ ...impact, after: before })).toBeUndefined();
    expect(transferJump({ ...impact, before: null })).toBeUndefined();
  });
});
