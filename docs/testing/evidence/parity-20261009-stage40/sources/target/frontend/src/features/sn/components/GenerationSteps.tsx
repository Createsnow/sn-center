import { CheckCircleOutlined, RightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/** 与原型一致的简式两步状态；额度未用完时不能因已分配过就标记全部完成。 */
export function GenerationSteps({ current }: { current: number }) {
  const { t } = useTranslation('prototype');
  return (
    <ol className="sn-generation-steps">
      {[0, 1].map((index) => (
        <li
          key={index}
          aria-current={current === index ? 'step' : undefined}
          className={
            current > index ? 'is-finished' : current === index ? 'is-current' : 'is-waiting'
          }
        >
          {current > index ? (
            <CheckCircleOutlined aria-hidden="true" />
          ) : (
            <span className="sn-generation-step-ring" aria-hidden="true" />
          )}
          <span>{t(index === 0 ? 'generate.step1' : 'generate.step2')}</span>
          {index === 0 && <RightOutlined className="sn-generation-step-arrow" aria-hidden="true" />}
        </li>
      ))}
    </ol>
  );
}
