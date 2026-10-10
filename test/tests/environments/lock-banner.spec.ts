import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E07-T05: the lock banner on the environment page. The lock API is faked in the browser and the
 * banner is mounted with Locks.buildLockBanner/bindLockEvents, so no real lock is taken: buttons
 * follow the server's per-viewer flags, someone else's lock is styled and explained, my lock shows
 * a countdown and Extend, and history rows show their own reason.
 */

declare const Locks: any;

const ENV_ID = 'env-lock-e2e';

function status(overrides: Record<string, unknown>) {
  return {
    isLocked: false, environmentId: ENV_ID, lockedByUserId: null, lockedByDisplayName: null, lockedAt: null,
    lockReason: null, expiresAt: null, canAcquire: false, canRelease: false, canExtend: false, canBreak: false,
    lockExpiryEnabled: true, ...overrides,
  };
}

function minutesFromNow(minutes: number) {
  return new Date(Date.now() + minutes * 60000).toISOString();
}

async function mount(page: Page, lockStatus: Record<string, unknown>) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof Locks !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(({ envId, lockStatus }) => {
    document.getElementById('lock-e2e')?.remove();
    // Outside #content-area (the router cannot replace it), as a fixed overlay above the shell.
    document.body.insertAdjacentHTML('beforeend',
      `<div id="lock-e2e" style="position:fixed;top:0;left:0;right:0;z-index:20000;background:#fff">${
        Locks.buildLockBanner(lockStatus, envId)}</div>`);
    Locks.bindLockEvents(envId, 'Lock E2E', () => { (window as any).__lockUpdated = true; });
  }, { envId: ENV_ID, lockStatus });
}

test.describe('Lock banner', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('an unlocked environment shows Acquire only when the viewer may acquire', async ({ page }) => {
    await mount(page, status({ canAcquire: false }));
    await expect(page.locator('#lock-e2e .lock-banner.unlocked')).toBeVisible();
    await expect(page.locator('#lock-e2e [data-lock-action="acquire"]')).toHaveCount(0);

    await mount(page, status({ canAcquire: true }));
    await expect(page.locator('#lock-e2e [data-lock-action="acquire"]')).toBeVisible();
  });

  test('with expiry on, the acquire dialog promises an automatic release and the warnings', async ({ page }) => {
    await mount(page, status({ canAcquire: true, lockExpiryEnabled: true }));
    await page.locator('#lock-e2e [data-lock-action="acquire"]').click();
    await expect(page.locator('#lockDurationHelp')).toContainText('warned 15 and 5 minutes before');
  });

  test('with expiry off, the acquire dialog says the lock stays until released', async ({ page }) => {
    await mount(page, status({ canAcquire: true, lockExpiryEnabled: false }));
    await page.locator('#lock-e2e [data-lock-action="acquire"]').click();
    await expect(page.locator('#lockDurationHelp')).toContainText('stays until you release it');
  });

  test("someone else's lock is styled as blocked, with Break only for those who may break it", async ({ page }) => {
    const alice = { isLocked: true, lockedByUserId: 'alice', lockedByDisplayName: 'Alice <b>A</b>',
      lockedAt: minutesFromNow(-5), expiresAt: minutesFromNow(42) };
    await mount(page, status(alice));

    const banner = page.locator('#lock-e2e .lock-banner');
    await expect(banner).toHaveClass(/other/);
    await expect(banner).toContainText('Held by Alice <b>A</b>');
    await expect(banner).toContainText('operations are blocked for you');
    await expect(page.locator('#lock-e2e [data-lock-action="release"], #lock-e2e [data-lock-action="extend"], '
      + '#lock-e2e [data-lock-action="break"]')).toHaveCount(0);

    await mount(page, status({ ...alice, canBreak: true }));
    await expect(page.locator('#lock-e2e [data-lock-action="break"]')).toBeVisible();
  });

  test('my lock shows the countdown and Extend, which sends the chosen minutes', async ({ page }) => {
    const extendBodies: unknown[] = [];
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/lock/extend$`), async route => {
      extendBodies.push(route.request().postDataJSON());
      if (extendBodies.length === 1) {
        return route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ message: 'A lock can last at most 1440 minutes from when it was taken' }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(status({ isLocked: true, canRelease: true, canExtend: true, expiresAt: minutesFromNow(72) })) });
    });
    await mount(page, status({ isLocked: true, lockedByUserId: devIds.user, lockedByDisplayName: 'Me',
      lockedAt: minutesFromNow(-10), expiresAt: minutesFromNow(42), canRelease: true, canExtend: true }));

    const banner = page.locator('#lock-e2e .lock-banner');
    await expect(banner).toHaveClass(/mine/);
    await expect(banner).not.toHaveClass(/expiring/);
    await expect(banner).toContainText('Locked by you');
    await expect(page.locator('#lock-e2e .lock-countdown')).toHaveText(/Auto-releases in 42 min/);
    await expect(page.locator('#lock-e2e [data-lock-action="release"]')).toBeVisible();

    await page.locator('#lock-e2e [data-lock-action="extend"]').click();
    await page.locator('#extendLockMinutes').selectOption('30');
    await page.locator('#confirmExtendLock').click();
    await expect(page.locator('body')).toContainText('A lock can last at most 1440 minutes');
    await expect(page.locator('#extendLockModal')).toBeVisible();

    await page.locator('#confirmExtendLock').click();
    await expect(page.locator('#extendLockModal')).toBeHidden();
    expect(extendBodies).toEqual([{ minutes: 30 }, { minutes: 30 }]);
    expect(await page.evaluate(() => (window as any).__lockUpdated)).toBe(true);
  });

  test('a lock expiring within 15 minutes is marked as expiring', async ({ page }) => {
    await mount(page, status({ isLocked: true, lockedByUserId: devIds.user, lockedByDisplayName: 'Me',
      lockedAt: minutesFromNow(-50), expiresAt: minutesFromNow(9), canRelease: true, canExtend: true }));

    await expect(page.locator('#lock-e2e .lock-banner')).toHaveClass(/expiring/);
    await expect(page.locator('#lock-e2e .lock-countdown')).toHaveText(/Auto-releases in (9|10) min/);
  });

  test('an open-ended lock has no countdown and no Extend', async ({ page }) => {
    await mount(page, status({ isLocked: true, lockedByUserId: devIds.user, lockedByDisplayName: 'Me',
      lockedAt: minutesFromNow(-5), expiresAt: null, canRelease: true, canExtend: false }));

    await expect(page.locator('#lock-e2e .lock-countdown')).toHaveCount(0);
    await expect(page.locator('#lock-e2e [data-lock-action="extend"]')).toHaveCount(0);
  });

  test('history rows show their own reason, and a duration only where the lock ended', async ({ page }) => {
    const acquired = minutesFromNow(-60);
    const broken = new Date().toISOString();
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/lock/history$`), route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify([
        { action: 'BROKEN', performedAt: broken, timestamp: broken, userDisplayName: 'Admin', reason: 'stuck deploy',
          acquiredAt: acquired, releasedAt: broken },
        { action: 'EXTENDED', performedAt: minutesFromNow(-20), timestamp: minutesFromNow(-20), userDisplayName: 'Alice',
          reason: 'Extended by 30 minutes', acquiredAt: acquired, releasedAt: broken },
        { action: 'ACQUIRED', performedAt: acquired, timestamp: acquired, userDisplayName: 'Alice', reason: 'deploy',
          acquiredAt: acquired, releasedAt: broken },
      ]),
    }));
    await mount(page, status({ canAcquire: true }));

    await page.locator('#lock-e2e [data-lock-action="history"]').click();
    const rows = page.locator('#lockHistoryModal tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText('stuck deploy');
    await expect(rows.nth(0)).toContainText('just now');
    await expect(rows.nth(0).locator('td').nth(4)).not.toHaveText('-');
    await expect(rows.nth(1)).toContainText('Extended');
    await expect(rows.nth(1)).toContainText('20m ago');
    await expect(rows.nth(1).locator('td').nth(4)).toHaveText('-');
    await expect(rows.nth(2)).toContainText('deploy');
    await expect(rows.nth(2)).toContainText('1h ago');
  });
});
