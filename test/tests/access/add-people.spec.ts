import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * U25-T01 (addpeople-mock Main.dc.html / Phone.dc.html): Add people. Typed or pasted addresses become
 * chips with their status (new, already a user, not an address, a mistyped domain with a fix), one
 * role for everyone, optional access from a searchable environment list, a summary bar from the
 * server's preview, and a result screen with each generated password shown once.
 *
 * Most tests fake the server's answers (email domains, environments, the bulk-onboard preview and
 * apply) so they check what the page shows and sends. The last describe runs against the smoke app.
 */

declare const ContentRouter: any;

const DOMAINS = [{ domain: 'tcgdigital.com', count: 40 }, { domain: 'lummustech.com', count: 12 }];
const ENVS = [
  { environmentId: 'e-ise', name: 'ise', displayName: 'ISE', isActive: true, vmCount: 3, runningVmCount: 1, groupCount: 1 },
  { environmentId: 'e-pay', name: 'pay-qa', displayName: 'pay-qa', isActive: true, vmCount: 5, runningVmCount: 0, groupCount: 1 },
  { environmentId: 'e-sit', name: 'sit-55', displayName: 'SIT 55', isActive: true, vmCount: 10, runningVmCount: 9, groupCount: 1 },
];
const TEAMS = [{ teamId: 't-data', displayName: 'Data', active: true }, { teamId: 't-ops', displayName: 'CloudOps', active: true }];
const EXISTING = 'viraj.gore@lummustech.com';

function onboardResult(body: any, applied: boolean) {
  const people = body.people.map((p: any) => {
    const existing = p.email === EXISTING;
    return {
      email: p.email, displayName: p.displayName, outcome: existing ? 'EXISTING' : 'NEW', reason: null,
      userId: applied ? `u-${p.email}` : null, teamId: p.teamId, grantsNew: body.access.length, grantsChanged: 0, grantsSkipped: 0,
      initialPassword: applied && p.generatePassword && !existing ? `pw-${p.email.split('@')[0]}-7Qk` : null,
    };
  });
  const count = (o: string) => people.filter((p: any) => p.outcome === o).length;
  return { applied, newPeople: count('NEW'), existingPeople: count('EXISTING'), skippedPeople: 0,
    newGrants: people.length * body.access.length, changedGrants: 0, skippedGrants: 0, people, grants: [] };
}

/** Fakes the server for this page; returns the bodies it was sent (preview and apply). */
async function fakeServer(page: Page) {
  const sent = { preview: [] as any[], apply: [] as any[] };
  await page.route(/\/api\/auth\/options$/, r => r.fulfill({ json: { passwordLoginEnabled: true } }));
  await page.route(/\/api\/v1\/users\/email-domains$/, r => r.fulfill({ json: DOMAINS }));
  await page.route(/\/api\/v1\/environments$/, r => (r.request().method() === 'GET' ? r.fulfill({ json: ENVS }) : r.fallback()));
  await page.route(/\/api\/v1\/teams$/, r => (r.request().method() === 'GET' ? r.fulfill({ json: TEAMS }) : r.fallback()));
  await page.route(/\/api\/v1\/users\/bulk-onboard\?dryRun=(true|false)$/, r => {
    const body = r.request().postDataJSON();
    const preview = r.request().url().endsWith('dryRun=true');
    (preview ? sent.preview : sent.apply).push(body);
    return r.fulfill({ json: onboardResult(body, !preview) });
  });
  return sent;
}

async function openAdd(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/user-management/add'; });
  await expect(page.locator('#add-people-view h1')).toHaveText('Add people', { timeout: 10_000 });
  await expect(page.locator('#adp-paste')).toBeVisible();
}

async function paste(page: Page, text: string) {
  await page.locator('#adp-paste').fill(text);
  await page.locator('#adp-paste-add').click();
}

const chips = (page: Page) => page.locator('#adp-chips .adp-chip');
const summary = (page: Page) => page.locator('#adp-summary');
const note = (page: Page) => page.locator('#adp-summary-note');

async function addAccess(page: Page, query: string, name: string, level: string, days: string) {
  await page.locator('#adp-env').fill(query);
  await page.locator('#adp-env-list').getByRole('option', { name: new RegExp(`^${name}\\b`) }).click();
  await page.locator('#adp-level').selectOption(level);
  await page.locator('#adp-days').selectOption({ label: days });
  await page.locator('#adp-grant-add').click();
}

test.describe('Add people', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('pasted addresses become chips: new, already a user, not an address, a mistyped domain with its fix', async ({ page }) => {
    const sent = await fakeServer(page);
    await openAdd(page);
    await expect(summary(page)).toHaveText('Nobody to add yet.');
    await expect(page.locator('#adp-submit')).toBeDisabled();

    await paste(page, `pat.example@tcgdigital.com, sam.example@tcgdigital.com\n${EXISTING}; bailsali@tcgdigitalc.com, not an address`);
    await expect(chips(page)).toHaveCount(5);
    await expect(page.locator('#adp-paste')).toHaveValue('');
    await expect(page.locator('#adp-chips .adp-chip-invalid')).toContainText('not an email address');
    const typo = page.locator('#adp-chips .adp-chip-typo');
    await expect(typo).toContainText('bailsali@tcgdigitalc.com');
    await expect(typo).toContainText('domain looks mistyped');
    await expect(page.locator('#adp-chips .adp-chip-existing')).toContainText('already a user, skipped', { timeout: 10_000 });
    await expect(summary(page)).toHaveText('Adding 2 people as User, with no access yet.');
    await expect(note(page)).toContainText('1 already a user (skipped) · 2 addresses need fixing (not added)');
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people');
    const previewed = sent.preview.at(-1).people.map((p: any) => p.email);
    expect(previewed).toEqual(['pat.example@tcgdigital.com', 'sam.example@tcgdigital.com', EXISTING]);

    await typo.getByRole('button', { name: 'Use tcgdigital.com' }).click();
    await expect(page.locator('#adp-chips .adp-chip-typo')).toHaveCount(0);
    await expect(chips(page).filter({ hasText: 'bailsali@tcgdigital.com' })).toHaveClass(/adp-chip-new/);
    await expect(summary(page)).toHaveText('Adding 3 people as User, with no access yet.', { timeout: 10_000 });
    await expect(note(page)).toContainText('1 already a user (skipped) · 1 address needs fixing (not added)');

    await page.getByRole('button', { name: 'Remove not an address' }).click();
    await expect(chips(page)).toHaveCount(4);
    await expect(note(page)).not.toContainText('needs fixing');
  });

  test('typing: an address followed by a comma becomes a chip; the rest stays in the box', async ({ page }) => {
    await fakeServer(page);
    await openAdd(page);
    await page.locator('#adp-paste').pressSequentially('ada@tcgdigital.com, bo@tcg');
    await expect(chips(page)).toHaveCount(1);
    await expect(page.locator('#adp-paste')).toHaveValue(/^\s*bo@tcg$/);
    await page.locator('#adp-paste').pressSequentially('digital.com');
    await page.locator('#adp-paste').blur();
    await expect(chips(page)).toHaveCount(2);
  });

  test('Environment admin without an Admin level warns; access chips and the summary sentence', async ({ page }) => {
    const sent = await fakeServer(page);
    await openAdd(page);
    await paste(page, 'pat.example@tcgdigital.com, sam.example@tcgdigital.com');
    await expect(page.locator('#adp-no-access')).toHaveText('No access yet — people can ask for it later.');

    await page.getByRole('radio', { name: /^Environment admin/ }).click();
    const warn = page.locator('#adp-envadmin-warn');
    await expect(warn).toHaveText('Environment admins manage only the environments you give them. Add at least one with the Admin level.');

    // The environment list is a combobox: type to filter, arrows and Enter pick.
    const env = page.getByRole('combobox', { name: 'Environment', exact: true });
    await env.fill('is');
    await expect(page.locator('#adp-env-list').getByRole('option')).toHaveCount(1);
    await env.press('ArrowDown');
    await env.press('Enter');
    await expect(env).toHaveValue('ISE');
    await page.locator('#adp-grant-add').click();
    await expect(page.locator('#adp-grants .adp-chip')).toHaveText(['ISE · Operator · 30 days']);
    await expect(page.locator('#adp-no-access')).toBeHidden();
    await expect(warn).toBeVisible();

    await addAccess(page, 'pay', 'pay-qa', 'VIEWER', '90 days');
    await expect(summary(page)).toHaveText(
      'Adding 2 people as Environment admin, with access to ISE (Operator, 30 days) and pay-qa (View only, 90 days).', { timeout: 10_000 });

    await addAccess(page, 'sit', 'SIT 55', 'ADMIN', 'No end');
    await expect(warn).toBeHidden();
    await page.getByRole('button', { name: 'Remove access to SIT 55' }).click();
    await expect(warn).toBeVisible();

    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    await expect(page.locator('#adp-done-title')).toHaveText('2 people added');
    const body = sent.apply.at(-1);
    expect(body.role).toBe('ENV_ADMIN');
    expect(body.access).toEqual([
      { environmentId: 'e-ise', scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 },
      { environmentId: 'e-pay', scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'VIEWER', durationDays: 90 }]);
    await expect(page.getByText('They are marked unverified until they first sign in with Microsoft.')).toBeVisible();

    await page.getByRole('button', { name: 'Add more people' }).click();
    await expect(page.locator('#adp-paste')).toBeVisible();
    await expect(chips(page)).toHaveCount(0);
    await expect(summary(page)).toHaveText('Nobody to add yet.');
  });

  test('Administrator hides access: nothing to give, the sentence says every environment', async ({ page }) => {
    const sent = await fakeServer(page);
    await openAdd(page);
    await paste(page, 'pat.example@tcgdigital.com');
    await addAccess(page, 'is', 'ISE', 'USER', '30 days');
    await page.getByRole('radio', { name: /^Administrator/ }).click();
    await expect(page.locator('#adp-admin-note')).toHaveText('Administrators can use every environment, so there is nothing to give here.');
    await expect(page.locator('#adp-env')).toHaveCount(0);
    await expect(summary(page)).toHaveText('Adding 1 person as Administrator, who can use every environment.', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    await expect(page.locator('#adp-done-title')).toHaveText('1 person added');
    expect(sent.apply.at(-1).role).toBe('ADMIN');
    expect(sent.apply.at(-1).access).toEqual([]);
  });

  test('details for everyone go with each person', async ({ page }) => {
    const sent = await fakeServer(page);
    await openAdd(page);
    await paste(page, 'Pia Sen <pia@tcgdigital.com>, ravi@tcgdigital.com');
    await expect(chips(page).first()).toContainText('Pia Sen');
    await page.getByRole('button', { name: /Details for everyone/ }).click();
    await page.getByLabel('Department').fill('Engineering');
    await page.getByLabel('Project code').fill('DLK-204');
    await page.getByLabel('Project code').press('Tab');
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    await expect(page.locator('#adp-done-title')).toHaveText('2 people added');
    const people = sent.apply.at(-1).people;
    expect(people[0]).toMatchObject({ email: 'pia@tcgdigital.com', displayName: 'Pia Sen', department: 'Engineering', projectCode: 'DLK-204' });
    expect(people[1]).toMatchObject({ email: 'ravi@tcgdigital.com', department: 'Engineering', projectCode: 'DLK-204' });
  });

  test('with a password: each person gets their own, shown once, with Copy all and a warning on leaving', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: (text: string) => { (window as any).__copied = text; return Promise.resolve(); } },
      });
    });
    const sent = await fakeServer(page);
    await openAdd(page);
    await paste(page, 'pat.example@tcgdigital.com, sam.example@tcgdigital.com');
    await page.getByRole('radio', { name: 'Email and a password' }).check();
    const signInNote = page.locator('#adp-signin-note');
    await expect(signInNote).toContainText('Each person gets their own generated password, shown once after adding. They choose their own at first sign-in.');
    await expect(signInNote).toContainText('These colleagues can use Microsoft sign-in; use a password only for people outside the directory.');
    await expect(note(page)).toContainText('Passwords are shown once after adding.', { timeout: 10_000 });
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people');
    await page.locator('#adp-submit').click();

    const body = sent.apply.at(-1);
    expect(body.people.map((p: any) => p.generatePassword)).toEqual([true, true]);
    expect(body.people.every((p: any) => !p.initialPassword)).toBe(true);

    await expect(page.locator('#adp-done-title')).toHaveText('2 people added');
    await expect(page.locator('#adp-passwords tbody tr')).toHaveText([
      'pat.example@tcgdigital.compw-pat.example-7Qk', 'sam.example@tcgdigital.compw-sam.example-7Qk']);
    await expect(page.getByText('Shown once. Pass each password on; they choose their own at first sign-in.')).toBeVisible();

    // Leaving before copying asks first.
    await page.getByRole('link', { name: 'Back to Users' }).click();
    await expect(page.locator('#confirmModal')).toContainText('Leave without copying?');
    await expect(page.locator('#confirmModal')).toContainText("The passwords can't be shown again.");
    await page.locator('#confirmModal').getByRole('button', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/#\/user-management\/add$/);

    await page.getByRole('button', { name: 'Copy all' }).click();
    await expect(page.locator('#adp-copy-note')).toHaveText('Copied.');
    expect(await page.evaluate(() => (window as any).__copied)).toBe(
      'pat.example@tcgdigital.com\tpw-pat.example-7Qk\nsam.example@tcgdigital.com\tpw-sam.example-7Qk');
    await page.getByRole('link', { name: 'Back to Users' }).click();
    await expect(page).toHaveURL(/#\/user-management$/, { timeout: 10_000 });
  });

  test("one person's own name and team (the panel under the chips) go with that person only", async ({ page }) => {
    const sent = await fakeServer(page);
    await openAdd(page);
    await paste(page, 'Pia Sen <pia@tcgdigital.com>, ravi@tcgdigital.com');
    await expect(chips(page).first()).toContainText('Pia Sen');
    await page.getByRole('button', { name: /Details for everyone/ }).click();
    await page.locator('#adp-team').selectOption('t-ops');

    const edit = page.getByRole('button', { name: 'Edit ravi@tcgdigital.com' });
    const panel = page.locator('#adp-person');
    await edit.click();
    await expect(panel).toBeVisible();
    await expect(page.locator('#adp-person-name')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(panel).toBeHidden();
    await expect(edit).toBeFocused();

    await edit.click();
    await panel.getByLabel('Name').fill('Ravi Kumar');
    await panel.getByLabel('Team').selectOption('t-data');
    await panel.getByLabel('Project code').fill('RV 1');
    await panel.getByRole('button', { name: 'Done' }).click();
    await expect(page.locator('#adp-person-error')).toHaveText('Project code can only use letters, digits, dot, dash and underscore.');
    await panel.getByLabel('Project code').fill('RV-1');
    await panel.getByRole('button', { name: 'Done' }).click();
    await expect(panel).toBeHidden();
    const ravi = chips(page).filter({ hasText: 'ravi@tcgdigital.com' });
    await expect(ravi).toContainText('Ravi Kumar');
    await expect(ravi).toContainText('own details');

    await expect.poll(() => sent.preview.at(-1)?.people?.find((p: any) => p.email === 'ravi@tcgdigital.com')?.teamId).toBe('t-data');
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    await expect(page.locator('#adp-done-title')).toHaveText('2 people added');
    const people = sent.apply.at(-1).people;
    expect(people[0]).toMatchObject({ email: 'pia@tcgdigital.com', displayName: 'Pia Sen', teamId: 't-ops', projectCode: null });
    expect(people[1]).toMatchObject({ email: 'ravi@tcgdigital.com', displayName: 'Ravi Kumar', teamId: 't-data', projectCode: 'RV-1' });
  });

  test('Cancel with people in the list asks before discarding them', async ({ page }) => {
    await fakeServer(page);
    await openAdd(page);
    await paste(page, 'keep.me@tcgdigital.com');
    await page.locator('#adp-cancel').click();
    await expect(page.locator('#confirmModal')).toContainText('Discard the list?');
    await page.locator('#confirmModal').getByRole('button', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/#\/user-management\/add$/);
    await expect(chips(page)).toHaveCount(1);
  });

  test('from the directory: picked people are chips with their name', async ({ page }) => {
    await fakeServer(page);
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, directory: true } }) });
    });
    await page.route(/\/api\/v1\/directory\/search\?/, route => route.fulfill({ json: [
      { directoryObjectId: 'oid-n', displayName: 'Nikhil Banerjee', email: 'nikhil@tcgdigital.com', alreadyInApp: false, department: 'Engineering' }] }));
    await openAdd(page);
    await expect(page.locator('#adp-no-directory')).toHaveCount(0);
    const search = page.getByRole('combobox', { name: 'Find in the Microsoft directory' });
    await search.fill('ni');
    await page.getByRole('option', { name: /Nikhil Banerjee/ }).click();
    await expect(chips(page)).toHaveCount(1);
    await expect(chips(page).first()).toContainText('Nikhil Banerjee');
    await expect(summary(page)).toHaveText('Adding 1 person as User, with no access yet.', { timeout: 10_000 });
  });
});

test.describe('Add people on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('one column, nothing wider than 390, the bar above the bottom menu, 44 px targets', async ({ page }) => {
    await fakeServer(page);
    await openAdd(page);
    await paste(page, `a.really.long.address.for.a.phone@tcgdigital.com, ${EXISTING}, bailsali@tcgdigitalc.com`);
    await addAccess(page, 'is', 'ISE', 'USER', '30 days');
    await expect(summary(page)).toHaveText('Adding 1 person as User, with access to ISE (Operator, 30 days).', { timeout: 10_000 });
    await page.waitForTimeout(300);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);

    const bar = await page.locator('.adp-bar').boundingBox();
    const nav = await page.locator('.mobile-nav').boundingBox();
    expect(bar).not.toBeNull();
    expect(bar!.x).toBeGreaterThanOrEqual(0);
    expect(bar!.x + bar!.width).toBeLessThanOrEqual(390);
    if (nav) expect(bar!.y + bar!.height).toBeLessThanOrEqual(nav.y + 1);
    await expect(page.locator('#adp-submit')).toBeInViewport();

    const small = await page.locator('#add-people-view').locator('button:visible, a.btn:visible, input[type=radio]:visible')
      .evaluateAll(els => els.map(el => {
        const target = (el.tagName === 'INPUT' ? el.closest('label') : el) as HTMLElement;
        const r = target.getBoundingClientRect();
        return { name: target.textContent?.trim() || target.getAttribute('aria-label') || '', h: r.height };
      }).filter(t => t.h < 44));
    expect(small, JSON.stringify(small)).toEqual([]);
  });
});

/** Against the smoke app's own server: the page's request and the bulk onboarding agree. */
test.describe('Add people against the server', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('typed addresses as Environment admin with an environment; they show on Users', async ({ page, playwright }) => {
    const tag = Date.now();
    const envId = await seedEnvironment(playwright, `Add people ${tag}`);
    test.skip(!envId, 'could not seed an environment');
    const a = `ada.${tag}@onboard.test`;
    const b = `bo.${tag}@onboard.test`;
    await openAdd(page);
    await paste(page, `${a}; ${b}`);
    await page.getByRole('radio', { name: /^Environment admin/ }).click();
    await page.locator('#adp-env').fill(`Add people ${tag}`);
    await page.locator('#adp-env-list .adp-option').first().click();
    await page.locator('#adp-grant-add').click();
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people', { timeout: 10_000 });
    const sent = page.waitForResponse(r => r.url().includes('/users/bulk-onboard?dryRun=false'));
    await page.locator('#adp-submit').click();
    const body = (await sent).request().postDataJSON();
    expect(body.role).toBe('ENV_ADMIN');
    expect(body.people.map((p: any) => p.email)).toEqual([a, b]);
    expect(body.access).toEqual([{ environmentId: envId, scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 }]);
    await expect(page.locator('#adp-done-title')).toHaveText('2 people added', { timeout: 10_000 });

    await page.getByRole('link', { name: 'Back to Users' }).click();
    await expect(page).toHaveURL(/#\/user-management$/, { timeout: 10_000 });
    await page.locator('#user-search').fill(`${tag}@onboard.test`);
    const added = page.locator('#users-table-body tr');
    await expect(added).toHaveCount(2);
    await expect(added.first().locator('td').nth(1)).toHaveText('Environment admin');
    await expect(added.first().locator('.um-not-yet')).toHaveText('Not signed in yet');
  });

  test('with a password: the generated one is shown once, works once, and the person chooses their own', async ({ page, playwright }) => {
    const email = `pia.${Date.now()}@onboard.test`;
    await openAdd(page);
    await paste(page, `Pia Sen <${email}>`);
    await page.getByRole('radio', { name: 'Email and a password' }).check();
    await expect(page.locator('#adp-submit')).toHaveText('Add 1 person', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    const row = page.locator('#adp-passwords tbody tr');
    await expect(row).toHaveCount(1, { timeout: 10_000 });
    await expect(row.locator('td').first()).toHaveText(email);
    const given = (await row.locator('td').nth(1).textContent())!.trim();
    expect(given.length).toBeGreaterThanOrEqual(12);

    const anon = await playwright.request.newContext({ baseURL: process.env.BASE_URL });
    const first = await (await anon.post('/api/auth/login', { data: { username: email, password: given } })).json();
    expect(first).toMatchObject({ success: false, passwordChangeRequired: true });
    const changed = await anon.post('/api/auth/change-password', { data: { username: email, password: given, newPassword: 'pia-chose-this-one' } });
    expect(changed.status()).toBe(200);
    expect(await (await anon.post('/api/auth/login', { data: { username: email, password: given } })).status()).toBe(401);
    expect(await (await anon.post('/api/auth/login', { data: { username: email, password: 'pia-chose-this-one' } })).json())
      .toMatchObject({ success: true });
    await anon.dispose();
  });
});
