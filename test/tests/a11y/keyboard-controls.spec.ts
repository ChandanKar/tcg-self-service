import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E13-T09: environment group headers, automation day pills and trigger cards work from the
 * keyboard and announce their state.
 */

declare const ContentRouter: any;

let seededEnvId = '';
test.beforeAll(async ({ playwright }) => {
  // Never depend on environments left by other runs (E13 a11y specs).
  seededEnvId = await seedEnvironment(playwright, 'Keys E2E');
});

test.describe('Keyboard controls', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('a group header expands with Enter and reports aria-expanded', async ({ page }) => {
    // The test schema has no VMs, so the environment's groups are faked.
    const vm = (id: string) => ({ vmId: id, name: `vm-${id}`, provider: 'AWS', region: 'ap-south-1',
      status: 'STOPPED', sequencePosition: 1 });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web tier', sequencePosition: 1 }, vms: [vm('a1')] },
      { group: { groupId: 'g2', name: 'db', displayName: 'Database tier', sequencePosition: 2 }, vms: [vm('b1')] },
    ] }));
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);

    const toggles = page.locator('.group-card-toggle');
    await expect(toggles).toHaveCount(2, { timeout: 15_000 });
    const second = toggles.nth(1);
    await expect(second).toHaveAttribute('aria-expanded', 'false');
    await second.focus();
    await page.keyboard.press('Enter');
    await expect(second).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator(`#${await second.getAttribute('aria-controls')}`)).not.toHaveClass(/group-collapsed/);
    await page.keyboard.press(' ');
    await expect(second).toHaveAttribute('aria-expanded', 'false');
  });

  test('day pills toggle with Space; trigger type moves with the arrow keys', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/automation-rules'; });
    await page.locator('#ar-new-rule-btn').click();
    await expect(page.locator('#automationRuleModal')).toBeVisible();

    const monday = page.locator('.ar-day-pill[data-day="MON"]');
    const before = await monday.getAttribute('aria-pressed');
    await monday.focus();
    await page.keyboard.press('Space');
    const after = before === 'true' ? 'false' : 'true';
    await expect(monday).toHaveAttribute('aria-pressed', after);
    const onDays = await page.locator('.ar-day-pill.on').evaluateAll(els => els.map(e => e.getAttribute('data-day')));
    expect(onDays.includes('MON')).toBe(after === 'true');

    const schedule = page.locator('.ar-choice-card[data-trigger-type="SCHEDULE"]');
    const grant = page.locator('.ar-choice-card[data-trigger-type="ACCESS_GRANT"]');
    await expect(page.locator('[role="radiogroup"]')).toHaveCount(1);
    await expect(schedule).toHaveAttribute('aria-checked', 'true');
    await schedule.focus();
    await page.keyboard.press('ArrowRight');
    await expect(grant).toHaveAttribute('aria-checked', 'true');
    await expect(grant).toBeFocused();
    await expect(page.locator('#ar-access-grant-fields')).toBeVisible();
    await expect(page.locator('#ar-schedule-fields')).toBeHidden();
  });
});
