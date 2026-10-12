import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E29-T06: My Account > Notifications. The preferences API is faked: every type with its two
 * switches, required bells locked, email greyed out where it is not sent, Save sends only the
 * changed rows, and the bell's footer link opens the tab.
 */

declare const MyAccount: any;
declare const ContentRouter: any;

const PREFS = [
  { type: 'OPERATION_COMPLETED', label: 'Operation completed', category: 'Operations', inApp: true, email: true, inAppLocked: false, emailAvailable: false },
  { type: 'OPERATION_FAILED', label: 'Operation failed', category: 'Operations', inApp: true, email: true, inAppLocked: true, emailAvailable: true },
  { type: 'ACCESS_EXPIRING', label: 'Access expiring', category: 'Access', inApp: true, email: true, inAppLocked: false, emailAvailable: true },
];

async function ready(page: Page) {
  const puts: any[] = [];
  await page.route(/\/users\/me\/notification-preferences$/, r => {
    if (r.request().method() === 'PUT') {
      const changes = r.request().postDataJSON();
      puts.push(changes);
      return r.fulfill({ json: PREFS.map(p => {
        const c = changes.find((x: any) => x.type === p.type);
        return c ? { ...p, ...c } : p;
      }) });
    }
    return r.fulfill({ json: PREFS });
  });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof MyAccount !== 'undefined' && typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  return puts;
}

test.describe('Notification preferences', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('lists every type, locks required bells and saves only what changed', async ({ page }) => {
    const puts = await ready(page);
    await page.evaluate(() => MyAccount.open('notifications'));

    const table = page.locator('.ma-pref-table');
    await expect(table.locator('tr[data-pref-row]')).toHaveCount(3);
    await expect(page.locator('#ma-pref-OPERATION_FAILED-inApp')).toBeDisabled();
    await expect(table.locator('tr[data-pref-row="OPERATION_FAILED"]')).toContainText('Required');
    await expect(page.locator('#ma-pref-OPERATION_COMPLETED-email')).toBeDisabled();
    await expect(page.locator('#ma-prefs-save')).toBeDisabled();

    await page.locator('#ma-pref-ACCESS_EXPIRING-email').uncheck();
    await page.locator('#ma-prefs-save').click();
    await expect(page.locator('.ma-pref-actions')).toContainText('Saved');
    expect(puts).toEqual([[{ type: 'ACCESS_EXPIRING', email: false }]]);
    await expect(page.locator('#ma-pref-ACCESS_EXPIRING-email')).not.toBeChecked();
  });

  test('the bell footer opens the Notifications tab', async ({ page }) => {
    await ready(page);
    await page.locator('#notification-bell-btn').click();
    await page.locator('#notification-settings-link').click();
    await expect(page.locator('#ma-tab-notifications')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.ma-pref-table')).toBeVisible();
  });

  test('on a 390px screen the switches fit without sideways scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await ready(page);
    await page.evaluate(() => MyAccount.open('notifications'));
    await expect(page.locator('.ma-pref-table tr[data-pref-row]')).toHaveCount(3);
    const overflow = await page.evaluate(() => {
      const body = document.querySelector('#dynamicSlideoutPanel .ma-body') as HTMLElement;
      return body.scrollWidth - body.clientWidth;
    });
    expect(overflow).toBeLessThanOrEqual(1);
  });
});
