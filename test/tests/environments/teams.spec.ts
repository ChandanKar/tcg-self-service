import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E23-T03: the Teams page (create a team, add a member by hand) and the team picker that replaced
 * the free-text owner team in the environment modals. Runs against the smoke app.
 */

declare const ContentRouter: any;

async function openTeams(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/teams'; });
  await expect(page.locator('#teams-view')).toBeVisible({ timeout: 15_000 });
}

test.describe('Teams', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin creates a team and adds a member', async ({ page }) => {
    const name = `Platform ${Date.now()}`;
    await openTeams(page);
    await page.locator('[data-action="team-new"]').click();
    await page.locator('#team-display').fill(name);
    await page.locator('#team-cost-center').fill('CC-42');
    await page.locator('#team-save-btn').click();

    await expect(page.locator('#team-detail-title')).toHaveText(name, { timeout: 10_000 });
    await expect(page.locator('#teams-table')).toContainText(name);
    await expect(page.locator('#team-detail')).toContainText('cost center CC-42');

    const firstUser = await page.locator('#team-add-user option:not([value=""])').first().getAttribute('value');
    expect(firstUser).toBeTruthy();
    await page.locator('#team-add-user').selectOption(firstUser!);
    await page.locator('[data-action="team-add-member"]').click();
    await expect(page.locator('.team-members')).toContainText('Manual');
  });

  test('the environment modal offers teams instead of a free-text owner', async ({ page }) => {
    await openTeams(page);
    const name = `Picker ${Date.now()}`;
    await page.locator('[data-action="team-new"]').click();
    await page.locator('#team-display').fill(name);
    await page.locator('#team-save-btn').click();
    await expect(page.locator('#team-detail-title')).toHaveText(name, { timeout: 10_000 });

    await page.evaluate('Modals.showCreateEnvironment(function() {})');
    const picker = page.locator('#cem-team');
    await expect(picker.locator('option', { hasText: name })).toHaveCount(1);
    await expect(page.locator('#cem-ownerTeam')).toHaveCount(0);

    let sent: any = null;
    await page.route(/\/api\/v1\/environments$/, route => {
      if (route.request().method() !== 'POST') return route.continue();
      sent = route.request().postDataJSON();
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ environmentId: 'e-1', name: 'x' }) });
    });
    await page.locator('#cem-name').fill(`picker-env-${Date.now()}`);
    await picker.selectOption({ label: name });
    await page.locator('#cem-submitBtn').click();
    await expect.poll(() => sent && sent.teamId).toBeTruthy();
    expect(JSON.parse(sent.metadata).ownerTeam).toBeUndefined();
  });
});
