import { useEffect, useRef, useState, type RefObject } from 'react';

/** 每页条数按容器高度算（定高行 36px + 表头 40px），正好放满、不出上下滚动条；最少 5 条。 */
export function useFitRows(box: RefObject<HTMLElement | null>, row = 36, head = 40): number {
  const [n, setN] = useState(15);
  const last = useRef(15);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => {
      if (el.clientHeight <= 0) return;
      const v = Math.max(5, Math.floor((el.clientHeight - head) / row));
      if (v !== last.current) {
        last.current = v;
        setN(v);
      }
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [box, row, head]);
  return n;
}
