import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T10: bell items open their entity (click or Enter), are marked read on the way, and every
 * notification type has its own icon. /notifications/* is faked in the browser.
 */

const TYPES = ['LOCK_ACQUIRED', 'LOCK_RELEASED', 'LOCK_BROKEN', 'LOCK_EXPIRED', 'LOCK_EXPIRING', 'ACCESS_REQUESTED',
  'ACCESS_GRANTED', 'ACCESS_LEVEL_CHANGED', 'ACCESS_REVOKED', 'ACCESS_REQUEST_APPROVED', 'ACCESS_REQUEST_DENIED',
  'ACCESS_EXPIRING', 'ACCESS_EXPIRED', 'OPERATION_REQUESTED', 'OPERATION_COMPLETED', 'OPERATION_FAILED',
  'STATE_DRIFT_DETECTED', 'EKS_SYNC_CHANGED', 'AUTOMATION_RULE_SKIPPED', 'WEEKLY_COST_REPORT',
  'WEEKLY_IDLE_WASTE_REPORT', 'WEEKLY_RIGHTSIZING_REPORT', 'ENVIRONMENT_STOP_NOTICE', 'IDLE_AUTO_STOPPED'];

function note(id: string, type: string, entityType: string | null, entityId: string | null) {
  return { notificationId: id, type, title: `${type} title`, message: 'm', entityType, entityId,
    read: false, createdAt: new Date().toISOString() };
}

async function withNotifications(page: Page, items: unknown[], marked: string[]) {
  await page.route(/\/api\/v1\/notifications\/unread/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ content: items, page: { size: 15, number: 0, totalElements: items.length, totalPages: 1 } }) }));
  await page.route(/\/api\/v1\/notifications\/count/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ count: items.length }) }));
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

  test('every notification type has its own icon', async ({ page }) => {
    await withNotifications(page, TYPES.map((t, i) => note(`t-${i}`, t, null, null)), []);

    await expect(page.locator('.notification-item')).toHaveCount(TYPES.length);
    await expect(page.locator('.notification-item .fa-bell')).toHaveCount(0);
  });
});
