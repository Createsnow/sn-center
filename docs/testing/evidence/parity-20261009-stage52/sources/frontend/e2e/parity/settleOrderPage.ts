import type { Page } from '@playwright/test';

/** 等待字体、有限动画和连续帧几何稳定，避免把浮层进入动画当作最终坐标。 */
export async function settleOrderPage(page: Page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    let previous = '',
      stable = 0;
    for (let frame = 0; frame < 180; frame++) {
      await new Promise<void>((done) => requestAnimationFrame(() => done()));
      const active = document
        .getAnimations()
        .some(
          (animation) =>
            (animation.playState === 'running' || animation.pending) &&
            Number.isFinite(animation.effect?.getComputedTiming().endTime),
        );
      const boxes = [
        ...document.querySelectorAll(
          'main,.el-dialog,.ant-modal-container,.el-message,.ant-message-notice,[role=tooltip]',
        ),
      ].map((el) => {
        const box = el.getBoundingClientRect();
        return [box.x, box.y, box.width, box.height, el.scrollTop];
      });
      const current = JSON.stringify(boxes);
      stable = !active && current === previous ? stable + 1 : 0;
      previous = current;
      if (stable >= 8) return;
    }
    throw new Error('Order page geometry did not settle');
  });
}
