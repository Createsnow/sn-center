import { Descriptions, Empty, Tag, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { GenerateContext } from '../../../api/snWork';
import type { SnRule } from '../../../api/sn';

/** 生成页自己的规则和计数器排版；初始化页继续使用其独立流程。 */
export function GenerationRuleDetails({
  context,
  chosen,
}: {
  context: GenerateContext;
  chosen?: SnRule;
}) {
  const { t } = useTranslation('prototype');
  const rule = context.rule ?? chosen?.current;
  const code = context.rule ? context.ruleCode : chosen?.code;
  const scope = context.rule ? context.ruleScope : chosen?.bindScope;
  const binding = context.rule ? context.ruleBinding : chosen?.bindValue;
  if (!rule)
    return (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('generate.rulePickRequired')} />
    );
  return (
    <Descriptions
      bordered
      size="small"
      column={2}
      items={[
        { key: 'code', label: t('rules.code'), children: code + ' · v' + rule.version },
        { key: 'bind', label: t('rules.bind'), children: t('rules.scope' + scope) + ' ' + binding },
        ...(context.rule
          ? [
              {
                key: 'source',
                label: t('generate.ruleSource'),
                span: 2,
                children: (
                  <Tag color={context.ruleSource === 'CHOSEN' ? 'orange' : 'green'}>
                    {t('generate.ruleSrc' + context.ruleSource)}
                  </Tag>
                ),
              },
            ]
          : []),
        {
          key: 'format',
          label: t('rules.format'),
          children: (
            <span className="sn-mono">
              {rule.prefix}
              <i>{'#'.repeat(rule.length)}</i>
              {rule.suffix}
            </span>
          ),
        },
        { key: 'base', label: t('rules.base'), children: rule.base + ' / ' + rule.length },
        {
          key: 'max',
          label: t('rules.maxSeq'),
          children: (BigInt(rule.base) ** BigInt(rule.length) - 1n).toLocaleString(),
        },
        {
          key: 'sample',
          label: t('rules.sample'),
          children: (
            <span className="sn-mono">
              {rule.prefix +
                Array.from(rule.alphabet)[0]!.repeat(rule.length - 1) +
                Array.from(rule.alphabet)[1]! +
                rule.suffix}
            </span>
          ),
        },
      ]}
    />
  );
}

export function GenerationCounter({ context }: { context: GenerateContext }) {
  const { t } = useTranslation('prototype');
  const { counter, nextStart } = context;
  return (
    <Descriptions
      bordered
      size="small"
      column={2}
      items={[
        {
          key: 'last',
          label: t('generate.lastSeq'),
          children: <b data-testid="sn-watermark">{counter.lastSeq}</b>,
        },
        ...(nextStart
          ? [
              {
                key: 'next',
                label: t('generate.nextStart'),
                children: (
                  <>
                    <span className="sn-mono" data-testid="sn-next-start">
                      {nextStart.startSn ?? '—'}
                    </span>
                    {nextStart.adjusted && (
                      <Tooltip
                        title={t('generate.startAdjusted', {
                          sn: nextStart.bySn,
                          start: nextStart.startSn,
                        })}
                      >
                        <Tag color="orange">{t('generate.adjustedTag')}</Tag>
                      </Tooltip>
                    )}
                  </>
                ),
              },
            ]
          : []),
        { key: 'generated', label: t('generate.piGenerated'), children: counter.generatedQty },
        { key: 'imported', label: t('generate.piImported'), children: counter.importedQty },
        {
          key: 'lock',
          label: t('generate.startState'),
          children: (
            <Tag color={counter.startLocked ? 'default' : 'green'}>
              {t(counter.startLocked ? 'generate.startLocked' : 'generate.startOpen')}
            </Tag>
          ),
        },
      ]}
    />
  );
}
