import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T10: bell items open their entity (click or Enter) and are marked read on the way.
 * U09-T06 (Notifications.dc.html): the popover is a dialog labelled Notifications listing the
 * newest items, read and unread; unread ones are bold with a coloured dot (red failure, gold
 * something ending, grey otherwise); Mark all as read while something is unread; Notification
 * settings and See all activity below; Escape closes and gives focus back to the bell.
 * /notifications/* is faked in the browser.
 */

const TYPES = ['LOCK_ACQUIRED', 'LOCK_RELEASED', 'LOCK_BROKEN', 'LOCK_EXPIRED', 'LOCK_EXPIRING', 'ACCESS_REQUESTED',
  'ACCESS_GRANTED', 'ACCESS_LEVEL_CHANGED', 'ACCESS_REVOKED', 'ACCESS_REQUEST_APPROVED', 'ACCESS_REQUEST_DENIED',
  'ACCESS_EXPIRING', 'ACCESS_EXPIRED', 'OPERATION_REQUESTED', 'OPERATION_COMPLETED', 'OPERATION_FAILED',
  'STATE_DRIFT_DETECTED', 'EKS_SYNC_CHANGED', 'AUTOMATION_RULE_SKIPPED', 'WEEKLY_COST_REPORT',
  'WEEKLY_IDLE_WASTE_REPORT', 'WEEKLY_RIGHTSIZING_REPORT', 'ENVIRONMENT_STOP_NOTICE', 'IDLE_AUTO_STOPPED'];

function note(id: string, type: string, entityType: string | null, entityId: string | null, read = false) {
  return { notificationId: id, type, title: `${type} title`, message: 'm', entityType, entityId,
    read, createdAt: new Date().toISOString() };
}

async function withNotifications(page: Page, items: any[], marked: string[], listed: string[] = []) {
  await page.route(/\/api\/v1\/notifications(\?|$)/, route => {
    listed.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: items, page: { size: 8, number: 0, totalElements: items.length, totalPages: 1 } }) });
  });
  await page.route(/\/api\/v1\/notifications\/count/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ count: items.filter(n => !n.read).length }) }));
  await page.route(/\/api\/v1\/notifications\/read-all$/, route => {
    marked.push('all');
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.route(/\/api\/v1\/notifications\/[^/]+\/read$/, route => {
    marked.push(new URL(route.request().url()).pathname.split('/').slice(-2)[0]);
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await signIn(page, 'user');
  await page.waitForLoadState('networkidle');
  await page.locator('#notification-bell-btn').click();
  await expect(page.locator('#notification-dropdown')).toBeVisible();
}

test.describe('Notification bell', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('clicking an environment notification marks it read and opens the environment', async ({ page }) => {
    const marked: string[] = [];
    await withNotifications(page, [note('n-1', 'OPERATION_COMPLETED', 'ENVIRONMENT', 'env-e11')], marked);

    await page.locator('.notification-item[data-id="n-1"]').click();

    await expect(page).toHaveURL(/#\/environments\/env-e11$/);
    expect(marked).toEqual(['n-1']);
    await expect(page.locator('#notification-dropdown')).toBeHidden();
  });

  test('Enter on a focused linked item navigates', async ({ page }) => {
    await withNotifications(page, [note('n-2', 'ACCESS_GRANTED', 'ACCESS', 'a-1')], []);

    await page.locator('.notification-item[data-id="n-2"]').focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/#\/my-environments/);
  });

  test('every notification type gets a dot colour', async ({ page }) => {
    await withNotifications(page, TYPES.map((t, i) => note(`t-${i}`, t, null, null)), []);

    await expect(page.locator('.notification-item')).toHaveCount(TYPES.length);
    const tones = await page.locator('.notification-item').evaluateAll(els => els.map(e => e.getAttribute('data-tone')));
    expect(tones.every(t => ['red', 'gold', 'grey'].includes(t || ''))).toBe(true);
    await expect(page.locator('.notification-item[data-id="t-15"]')).toHaveAttribute('data-tone', 'red'); // OPERATION_FAILED
  });

  test('read and unread are listed in a dialog; unread bold with a dot; Mark all as read', async ({ page }) => {
    const marked: string[] = [];
    const listed: string[] = [];
    await withNotifications(page, [
      note('u-1', 'LEASE_EXPIRING', 'ENVIRONMENT', 'env-1'),
      note('r-1', 'ACCESS_REQUEST_APPROVED', 'ACCESS', null, true),
      note('r-2', 'WEEKLY_COST_REPORT', null, null, true),
      note('r-3', 'OPERATION_COMPLETED', 'ENVIRONMENT', 'env-2', true)], marked, listed);

    expect(new URL(listed[0]).searchParams.get('size')).toBe('8');
    expect(listed[0]).not.toContain('/unread');
    const dialog = page.getByRole('dialog', { name: 'Notifications' });
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.notification-item')).toHaveCount(4);
    const unread = dialog.locator('.notification-item[data-id="u-1"]');
    await expect(unread).toHaveClass(/unread/);
    await expect(unread).toHaveAttribute('data-tone', 'gold');
    expect(await unread.locator('.notification-item-title').evaluate(el => getComputedStyle(el).fontWeight)).toBe('600');
    await expect(dialog.locator('.notification-item[data-id="r-2"]')).toHaveAttribute('data-tone', 'read');
    await expect(page.locator('#notification-bell-btn')).toHaveAttribute('aria-label', 'Notifications, 1 unread');

    await dialog.getByRole('button', { name: 'Mark all as read' }).click();
    await expect(dialog.locator('.notification-item.unread')).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: 'Mark all as read' })).toBeHidden();
    expect(marked).toEqual(['all']);
  });

  test('Escape closes and gives focus back to the bell; See all activity opens My activity', async ({ page }) => {
    await withNotifications(page, [note('n-3', 'LOCK_RELEASED', null, null, true)], []);
    await expect(page.locator('#notification-dropdown')).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');
    await expect(page.locator('#notification-dropdown')).toBeHidden();
    await expect(page.locator('#notification-bell-btn')).toBeFocused();

    await page.locator('#notification-bell-btn').click();
    const footer = page.locator('.notification-dropdown-footer');
    await expect(footer.getByRole('button', { name: 'Notification settings' })).toBeVisible();
    await footer.getByRole('link', { name: 'See all activity' }).click();
    await expect(page).toHaveURL(/#\/activity-logs$/);
    await expect(page.locator('#notification-dropdown')).toBeHidden();
  });

  test('nothing yet', async ({ page }) => {
    await withNotifications(page, [], []);
    await expect(page.locator('#notification-empty')).toHaveText('No notifications yet.');
    await expect(page.locator('#notification-mark-all')).toBeHidden();
  });
});
