import { Tooltip } from 'antd';
import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
} from 'react';

/** 订单表格按实际排版检测溢出；浮层以整个单元格定位，并保留分录与标签的完整文本。 */
export const OrderOverflowCell = forwardRef<HTMLTableCellElement, ComponentPropsWithoutRef<'td'>>(
  function OrderOverflowCell({ children, className, onMouseEnter, title, ...props }, forwardedRef) {
    const cell = useRef<HTMLTableCellElement>(null);
    const [content, setContent] = useState('');
    const [open, setOpen] = useState(false);
    const enabled = className?.split(' ').includes('sn-order-overflow');
    useImperativeHandle(forwardedRef, () => cell.current!);
    useEffect(() => {
      if (!open) return;
      const scroller = cell.current?.closest('.ant-table-body,.ant-table-content');
      const close = () => setOpen(false);
      scroller?.addEventListener('scroll', close);
      return () => scroller?.removeEventListener('scroll', close);
    }, [open]);
    const element = (
      <td
        {...props}
        ref={cell}
        className={className}
        title={enabled ? undefined : title}
        onMouseEnter={(event) => {
          onMouseEnter?.(event);
          if (!enabled) return;
          const child = event.currentTarget.querySelector<HTMLElement>(
            '.sn-order-overflow-content',
          );
          if (!child) return;
          const range = document.createRange();
          range.selectNodeContents(child);
          const text = range.getBoundingClientRect(),
            box = child.getBoundingClientRect(),
            style = getComputedStyle(child);
          const padding = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
          const overflow =
            text.width + padding > box.width + 0.01 ||
            text.height > box.height + 0.01 ||
            child.scrollWidth > box.width + 0.01;
          setContent(overflow ? (title ?? event.currentTarget.innerText) : '');
          setOpen(overflow);
        }}
      >
        {enabled ? <div className="sn-order-overflow-content">{children}</div> : children}
      </td>
    );
    return enabled ? (
      <Tooltip
        title={content}
        open={open && !!content}
        onOpenChange={setOpen}
        placement="top"
        // 定位库向下取整；半像素补偿使文本提示按原型取最近的CSS像素。
        align={{ offset: [0.5, 0] }}
        mouseEnterDelay={0}
        mouseLeaveDelay={0}
        classNames={{ root: 'sn-order-overflow-tooltip' }}
        destroyOnHidden
      >
        {element}
      </Tooltip>
    ) : (
      element
    );
  },
);
