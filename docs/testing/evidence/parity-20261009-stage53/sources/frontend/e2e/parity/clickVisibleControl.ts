import { expect, type Locator, type Page } from '@playwright/test';

/** 点击已在视口内的控件中心，避免自动定位在路由动画中额外滚动父容器。 */
export async function clickVisibleControl(page: Page, control: Locator) {
  await expect(control).toBeVisible();
  await expect(control).toBeEnabled();
  const box = await control.boundingBox();
  expect(box).not.toBeNull();
  const point = { x: box!.x + box!.width / 2, y: box!.y + box!.height / 2 };
  expect(
    await control.evaluate((element, { x, y }) => {
      const hit = document.elementFromPoint(x, y);
      return hit === element || (hit !== null && element.contains(hit));
    }, point),
  ).toBe(true);
  await page.mouse.click(point.x, point.y);
}
