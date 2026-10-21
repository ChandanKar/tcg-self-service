import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T08: 'danger' toasts look like errors, toasts made in the same millisecond are dismissed
 * one at a time, escapeHtml(0) is '0', and re-opening a modal never stacks backdrops.
 */

declare const ContentRouter: any;
declare const Notifications: any;
declare const Utils: any;
declare const VmRegistry: any;

test.describe('Toasts and modals', () => {
  test('danger toasts use the error style; same-millisecond toasts dismiss separately', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof Notifications !== 'undefined');

    const [a, b] = await page.evaluate(() => [
      Notifications.show('First', 'danger', 0),
      Notifications.show('Second', 'info', 0),
    ]);
    expect(a).not.toBe(b);
    const first = page.locator(`#${a}`);
    await expect(first).toHaveClass(/toast-error/);
    await expect(first).toHaveAttribute('role', 'alert');
    await expect(first.locator('.toast-icon')).toHaveClass(/fa-exclamation-circle/);
    await expect(page.locator(`#${b}`)).toHaveAttribute('role', 'status');

    await first.locator('.toast-close').click();
    await expect(first).toHaveCount(0);
    await expect(page.locator(`#${b}`)).toBeVisible();

    expect(await page.evaluate(() => Utils.escapeHtml(0))).toBe('0');
  });

  test('toasts: bottom right, 6 s unless hovered, errors stay until closed, one action (U17-T03)', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof Notifications !== 'undefined');

    const [held, quick, broke] = await page.evaluate(() => [
      Notifications.success('Access given to 4 people on 2 environments.'),
      Notifications.success('Payments Dev is starting.'),
      Notifications.error("Couldn't stop CRM UAT: Rahul Verma holds the lock until 14:30."),
    ]);
    const viewport = page.viewportSize()!;
    const box = await page.locator('#notification-container').boundingBox();
    expect(box!.x + box!.width).toBeGreaterThan(viewport.width - 40);
    expect(box!.y + box!.height).toBeGreaterThan(viewport.height - 60);

    await page.locator(`#${held}`).hover();
    await expect(page.locator(`#${broke}`)).toHaveAttribute('role', 'alert');
    await page.waitForTimeout(6_800);
    await expect(page.locator(`#${quick}`)).toHaveCount(0);
    await expect(page.locator(`#${held}`)).toBeVisible();          // paused while hovered
    await expect(page.locator(`#${broke}`)).toBeVisible();         // errors stay until closed
    await page.locator(`#${broke} .toast-close`).click();
    await expect(page.locator(`#${broke}`)).toHaveCount(0);

    const watch = await page.evaluate(() => {
      (window as any).watched = 0;
      return Notifications.info('Payments Dev is starting. Database first.', undefined,
        { action: { label: 'Watch', onClick: () => { (window as any).watched++; } } });
    });
    await page.locator(`#${watch}`).getByRole('button', { name: 'Watch' }).click();
    await expect(page.locator(`#${watch}`)).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).watched)).toBe(1);
  });

  test('opening and closing the group dialog five times leaves no stray backdrop', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await expect(page.locator('#content-area table').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => {
      (window as any).VmRegistryState.currentGroups = [{ groupId: 'g-e2e', displayName: 'E2E group', vmCount: 0 }];
    });

    // U11-T04: VMs are added on their own page; the group dialog is the registry's Bootstrap modal now.
    const modal = page.locator('#registryGroupModal'); // U11-T05
    for (let i = 0; i < 5; i++) {
      await page.evaluate(() => VmRegistry.openGroupForm());
      await expect(modal).toBeVisible();
      await expect(page.locator('.modal-backdrop')).toHaveCount(1);
      await modal.locator('.btn-close').click();
      await expect(modal).toBeHidden();
      await expect(page.locator('.modal-backdrop')).toHaveCount(0);
    }
  });
});
