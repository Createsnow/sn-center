import { describe, expect, it } from 'vitest';
import i18next from 'i18next';
import { ApiError } from '../../api/contract';
import { normalizeRuleForm, ruleErrorText } from './ruleErrorText';
import zh from '../../i18n/prototype/errors.zh-CN.json';

describe('prototype rule form errors', () => {
  const valid = {
    code: ' CMP_UI ',
    name: ' 测试 ',
    bindScope: 'GENERAL',
    bindValue: 'discard',
    prefix: ' 前 ',
    suffix: ' 后 ',
    base: 10,
    length: 5,
    alphabet: '',
  };
  it('normalizes surrounding whitespace and preserves Unicode content', () => {
    expect(normalizeRuleForm(valid)).toMatchObject({
      code: 'CMP_UI',
      name: '测试',
      bindValue: '',
      prefix: '前',
      suffix: '后',
    });
    expect(
      normalizeRuleForm({ ...valid, bindScope: 'CUSTOMER', bindValue: ' 客户甲 ' }).bindValue,
    ).toBe('客户甲');
  });
  it('keeps required-field reasons distinct without replacing server uniqueness checks', () => {
    for (const [value, reason] of [
      [{ ...valid, code: '' }, 'RULE_CODE_INVALID'],
      [{ ...valid, name: ' ' }, 'RULE_NAME_REQUIRED'],
      [{ ...valid, bindScope: 'PI', bindValue: '' }, 'RULE_BIND_REQUIRED'],
    ] as const) {
      expect(() => normalizeRuleForm(value)).toThrow(
        expect.objectContaining({ params: { reason } }),
      );
    }
  });
  it('translates only known rule reasons from the rule error codes', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'zh-CN',
      resources: { 'zh-CN': { prototypeErrors: zh, translation: { errors: { unknown: '未知' } } } },
    });
    expect(
      ruleErrorText(
        new ApiError(1004000006, 409, '', { reason: 'RULE_BIND_EXISTS', rule_code: 'CMP_MAIN' }),
        i18n.t,
      ),
    ).toBe('该绑定对象已有规则：CMP_MAIN');
    expect(
      ruleErrorText(new ApiError(1004000001, 400, '', { reason: 'RULE_CHARSET_LENGTH' }), i18n.t),
    ).toBe('字符表长度必须等于进制');
    expect(
      ruleErrorText(new ApiError(123, 400, '', { reason: 'RULE_CHARSET_LENGTH' }), i18n.t),
    ).toBe('未知');
  });
});
