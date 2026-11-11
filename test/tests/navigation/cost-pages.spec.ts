import { test, expect } from '@playwright/test';
import { signIn, canSignIn } from '../../fixtures/auth';

/**
 * U21-T07: Cost is one entry in the side menu. Its four pages (Overview, Savings, Waste, Chargeback)
 * switch on the breadcrumb line, and each keeps Cost management marked in the menu.
 */
test.describe('Cost pages', () => {
  test.skip(!canSignIn('admin'), 'needs TEST_DEV_ADMIN_ID');

  test('one Cost entry in the menu; the pages switch on the breadcrumb line', async ({ page }) => {
    await signIn(page, 'admin');
    const costMenu = page.locator('#cost-menu .sidebar-menu-link');
    await expect(costMenu).toHaveCount(1);
    await expect(costMenu).toHaveText('Cost management');

    const pages: [string, string][] = [['cost-management', 'Spend'], ['savings', 'Savings'], ['waste', 'Waste'], ['chargeback', 'Chargeback']];
    for (const [key, label] of pages) {
      await page.goto(`/home#/${key}`);
      const nav = page.getByRole('navigation', { name: 'Cost pages' });
      await expect(nav).toBeVisible();
      await expect(nav.locator('[aria-current="page"]')).toHaveText(label);
      await expect(nav.getByRole('link')).toHaveText(pages.filter(p => p[0] !== key).map(p => p[1]));
      await expect(page.locator('.sidebar-menu-link[data-content="cost-management"]')).toHaveClass(/active/);
    }

    // A link moves between the pages.
    await page.getByRole('navigation', { name: 'Cost pages' }).getByRole('link', { name: 'Savings' }).click();
    await expect(page).toHaveURL(/#\/savings$/);
  });

  test('U21-T17: on a phone the four items line up, one height, inside the screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, 'admin');
    await page.goto('/home#/savings');
    const nav = page.getByRole('navigation', { name: 'Cost pages' });
    await expect(nav).toBeVisible({ timeout: 15_000 });
    const boxes = await nav.locator('.page-section-item').evaluateAll(els => els.map(e => { const b = e.getBoundingClientRect();
      return { top: Math.round(b.top), height: Math.round(b.height), right: Math.round(b.right) }; }));
    expect(boxes).toHaveLength(4);
    expect(new Set(boxes.map(b => b.top)).size).toBe(1);
    expect(new Set(boxes.map(b => b.height)).size).toBe(1);
    expect(Math.max(...boxes.map(b => b.right))).toBeLessThanOrEqual(390);
  });
});
