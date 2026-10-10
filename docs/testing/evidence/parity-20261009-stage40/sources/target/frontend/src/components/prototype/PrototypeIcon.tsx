import icons from './icons.json';

/** 固定的 Element Plus SVG 基准，不接收服务端或用户提供的 HTML。版权见同目录许可。 */
export function PrototypeIcon({ name }: { name: keyof typeof icons }) {
  return (
    <span
      className="prototype-icon"
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: icons[name] }}
    />
  );
}
