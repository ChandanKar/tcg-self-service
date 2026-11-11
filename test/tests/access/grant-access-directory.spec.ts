import { test, expect } from '@playwright/test';
import { signIn, canSignIn } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * U21-T01: on the Grant access page a person picked from the Microsoft directory is not added to the
 * app on the spot. They are sent by their directory id with the grant, and the server adds them in the
 * same step, so leaving the page without granting adds nobody.
 *
 * The directory lookup is off on the smoke app, so the directory search and the bulk preview/grant
 * answers are faked; the spec checks what the page asks of the server.
 */
test.describe('Grant access: a person from the directory', () => {
  test.skip(!canSignIn('admin'), 'needs TEST_DEV_ADMIN_ID');

  const person = { directoryObjectId: 'obj-e2e-1', displayName: 'Asha Rao', email: 'asha.e2e@directory.test',
    userPrincipalName: 'asha.e2e@directory.test', alreadyInApp: false };

  test('is added only with the grant, by directory id; leaving first adds nobody', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Directory grant');
    test.skip(!envId, 'could not seed an environment');

    const userPosts: string[] = [];
    const bulkBodies: any[] = [];
    page.on('request', r => {
      if (r.method() === 'POST' && /\/api\/v1\/users(\?|$)/.test(r.url())) userPosts.push(r.url());
    });
    await page.route('**/api/v1/users/search**', r => r.fulfill({ json: [] }));
    await page.route('**/api/v1/directory/search**', r => r.fulfill({ json: [person] }));
    const plan = (userId: string | null) => ({ people: 1, environments: 1, changes: 1, skipped: 0, rows: [{
      userId, userName: person.displayName, userEmail: person.email, environmentId: envId, environmentName: 'Directory grant',
      scopeType: 'ENVIRONMENT', scopeId: envId, scopeName: 'Directory grant', outcome: 'NEW', currentLevel: null,
      currentExpiresAt: null, resultLevel: 'USER', resultExpiresAt: null, reason: null }] });
    await page.route('**/api/v1/access-grants/bulk/preview', r => {
      bulkBodies.push(r.request().postDataJSON());
      return r.fulfill({ json: plan(null) });
    });
    await page.route('**/api/v1/access-grants/bulk', r => {
      bulkBodies.push(r.request().postDataJSON());
      return r.fulfill({ status: 201, json: plan('u-new') });
    });

    await signIn(page, 'admin');
    await page.goto(`/home#/access-management/grant?environmentId=${envId}`);
    const search = page.locator('#ga-person-search');
    await search.fill('asha');
    const option = page.locator('.ga-option', { hasText: 'Asha Rao' });
    await expect(option).toContainText('not in the app yet');
    await option.click();

    // Picked: a chip marked new, and still nobody added.
    await expect(page.locator('#ga-chips .ga-chip')).toContainText('Asha Rao · new');
    expect(userPosts).toEqual([]);

    const env = page.locator(`input[data-ga-env-pick="${envId}"]`);
    if (!(await env.isChecked())) await env.check();
    const grant = page.locator('#ga-grant');
    await expect(grant).toBeEnabled();
    const preview = bulkBodies.at(-1);
    expect(preview.directoryObjectIds).toEqual(['obj-e2e-1']);
    expect(preview.userEmails).toEqual([]);

    await grant.click();
    await expect(page.getByText('1 person was added to the app.', { exact: false })).toBeVisible();
    const applied = bulkBodies.at(-1);
    expect(applied.directoryObjectIds).toEqual(['obj-e2e-1']);
    expect(userPosts).toEqual([]);
  });
});
