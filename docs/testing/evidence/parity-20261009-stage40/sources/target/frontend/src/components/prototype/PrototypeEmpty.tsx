import { useId } from 'react';
import asset from './empty.json';

/** 原型的静态空状态插图；替换定义 ID，避免同页多个实例的 SVG 引用串用。 */
export function PrototypeEmpty({ description }: { description: string }) {
  const id = useId();
  return (
    <div className="prototype-empty">
      <div
        className="prototype-empty-image"
        aria-hidden
        dangerouslySetInnerHTML={{ __html: asset.svg.replaceAll('__EMPTY_ID__', id) }}
      />
      <p>{description}</p>
    </div>
  );
}
