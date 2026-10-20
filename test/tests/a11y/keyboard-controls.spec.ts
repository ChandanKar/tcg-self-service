import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E13-T09: environment row actions, automation day pills and trigger cards work from the
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

  test('every row action in Groups and VMs is a focusable button named after its target', async ({ page }) => {
    // The test schema has no VMs, so the environment's groups are faked.
    const vm = (id: string, status = 'STOPPED') => ({ vmId: id, name: `vm-${id}`, provider: 'AWS', region: 'ap-south-1',
      status, sequencePosition: 1 });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web tier', sequencePosition: 1 }, vms: [vm('a1')] },
      { group: { groupId: 'g2', name: 'db', displayName: 'Database tier', sequencePosition: 2 }, vms: [vm('b1', 'RUNNING')] },
    ] }));
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);

    // U07-T03: one table with group rows and VM rows instead of collapsible group cards.
    const table = page.locator('#env-vms');
    await expect(table.locator('.env-group-row')).toHaveCount(2, { timeout: 15_000 });
    for (const name of ['Start group Web tier', 'Stop group Database tier', 'Start vm-a1', 'Stop vm-b1',
      'Details of vm-a1', 'Details of vm-b1']) {
      const button = table.getByRole('button', { name, exact: true });
      await button.focus();
      await expect(button).toBeFocused();
    }
    await expect(page.locator('.group-card-toggle')).toHaveCount(0);
  });

  test('day pills toggle with Space; trigger type moves with the arrow keys', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/automation-rules'; });
    await page.waitForLoadState('networkidle');
    // Bootstrap moves focus into the dialog on 'shown'; wait for it so it cannot steal focus later.
    const shown = page.evaluate(() => new Promise(resolve =>
      document.addEventListener('shown.bs.modal', () => resolve(true), { once: true })));
    await page.locator('#ar-new-rule-btn').click();
    await shown;
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
