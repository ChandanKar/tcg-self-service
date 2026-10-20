import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E36-T05: the Ask panel. Hidden while the assistant is off; when on, the user menu opens a slide-out
 * where a question is sent with the earlier turns, the answer is shown as text (model HTML is never
 * rendered; "- " lines become a list) with the tools used, and errors such as the daily limit are shown.
 * The assistant API is faked in the browser. U09-T06 (AskPanel.dc.html): the scope line under the
 * title, suggestion chips that send their question, "Open <environment>" links for environments an
 * answer names, Start over, and Ask in the help menu.
 */

declare const AssistantPanel: any;
declare const Slideout: any;

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function setUp(page: Page, enabled: boolean, answers: Array<{ status: number; body: unknown }> = []) {
  const asked: any[] = [];
  await page.route(/\/api\/v1\/users\/me\/access$/, route => json(route, [
    { accessId: 'a1', environmentId: 'env-pay', environmentName: 'Payments Dev', accessLevel: 'USER' },
    { accessId: 'a2', environmentId: 'env-crm', environmentName: 'CRM UAT', accessLevel: 'USER' }]));
  await page.route(/\/api\/v1\/assistant\/status$/, route => json(route, enabled
    ? { enabled: true, dailyLimit: 50, remainingToday: 50 } : { enabled: false }));
  await page.route(/\/api\/v1\/assistant\/ask$/, route => {
    asked.push(route.request().postDataJSON());
    const next = answers.shift() || { status: 200, body: { answer: 'ok', toolCalls: [], incomplete: false } };
    return json(route, next.body, next.status);
  });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof AssistantPanel !== 'undefined');
  return asked;
}

async function openPanel(page: Page) {
  await page.locator('.user-profile-trigger').click();
  await page.locator('#ask-menu-btn').click();
  await expect(page.locator('#assistant-question')).toBeFocused();
}

test.describe('Ask panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('is not in the user menu while the assistant is off', async ({ page }) => {
    await setUp(page, false);
    await page.locator('.user-profile-trigger').click();
    await expect(page.locator('#my-account-btn')).toBeVisible();
    await expect(page.locator('#ask-menu-btn')).toBeHidden();
  });

  test('answers are text, lists are lists, and the tools used are disclosed', async ({ page }) => {
    const asked = await setUp(page, true, [
      { status: 200, body: { answer: 'Two environments were idle:\n- <img src=x onerror="window.pwned=1">alpha\n- beta\n\nBoth are <b>estimated</b>.',
        toolCalls: [{ name: 'idle_resources', arguments: { limit: 20 }, error: false }], incomplete: false } },
      { status: 200, body: { answer: 'alpha cost $12.50 (estimated).', toolCalls: [], incomplete: false } }
    ]);
    await openPanel(page);

    await page.locator('#assistant-question').fill('Which of my environments were idle this week?');
    await page.locator('#assistant-question').press('Enter');

    const answer = page.locator('.assistant-msg-answer').first();
    await expect(answer.locator('.assistant-bubble li')).toHaveCount(2);
    await expect(answer.locator('.assistant-bubble li').first()).toHaveText('<img src=x onerror="window.pwned=1">alpha');
    await expect(answer.locator('img, b')).toHaveCount(0);
    await expect(answer).toContainText('Both are <b>estimated</b>.');
    expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined();
    await answer.locator('.assistant-tools summary').click();
    await expect(answer.locator('.assistant-tools')).toContainText('Tools used (1)');
    await expect(answer.locator('.assistant-tools code')).toHaveText('idle_resources');

    await page.locator('#assistant-question').fill('How much did alpha cost?');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-msg-answer').nth(1)).toContainText('$12.50 (estimated)');
    expect(asked[0]).toEqual({ question: 'Which of my environments were idle this week?', history: [] });
    expect(asked[1].history).toEqual([
      { role: 'user', text: 'Which of my environments were idle this week?' },
      { role: 'assistant', text: expect.stringContaining('Two environments were idle') }
    ]);

    await page.locator('#assistant-clear').click();
    await expect(page.locator('.assistant-msg')).toHaveCount(0);
  });

  test('the scope line, chips that ask, and Open links for environments an answer names', async ({ page }) => {
    const asked = await setUp(page, true, [
      { status: 200, body: { answer: 'Two VMs were idle:\n- pay-cache in Payments Dev\n- crm-batch-01 in crm uat\nNothing in Billing.',
        toolCalls: [], incomplete: false } }]);
    await openPanel(page);

    await expect(page.locator('#dynamicSlideoutTitle')).toHaveText('Ask');
    await expect(page.locator('.ask-head-sub')).toHaveText(/It can't start, stop or change anything\./);
    const chips = page.locator('.assistant-chip');
    await expect(chips).toHaveText(['Cost trend for 3 months', 'Spend by service', 'Savings I could make', 'VMs that could be smaller']);
    await expect(page.locator('.assistant-foot')).toContainText('Remembers the last 10 messages of this conversation.');

    await chips.filter({ hasText: 'Savings I could make' }).click();
    await expect(page.locator('.assistant-msg-user')).toHaveText('Savings I could make');
    await expect(page.locator('.assistant-env-link')).toHaveText(['Open Payments Dev', 'Open CRM UAT']);
    expect(asked[0].question).toBe('Savings I could make');
    await expect(chips).toHaveCount(4);

    await page.locator('.assistant-env-link', { hasText: 'Open CRM UAT' }).click();
    await expect(page).toHaveURL(/#\/environments\/env-crm$/);
    await expect(page.locator('#dynamicSlideoutPanel')).not.toHaveClass(/show/);
  });

  test('Ask is also in the help menu while the assistant is on', async ({ page }) => {
    await setUp(page, true);
    await page.locator('#topbar-help-btn').click();
    await page.getByRole('menuitem', { name: 'Ask about cost and environments' }).click();
    await expect(page.locator('#assistant-question')).toBeFocused();
    await page.locator('#dynamicSlideoutPanel .close-btn').click();
    await page.evaluate(() => Slideout.open('Other', '<p>x</p>'));
    await expect(page.locator('.ask-head-sub')).toHaveCount(0);
  });

  test('the daily limit and failures are shown and not sent back as history', async ({ page }) => {
    const asked = await setUp(page, true, [
      { status: 429, body: { error: 'Too Many Requests', message: 'You have asked 50 questions today, the daily limit. Try again tomorrow.' } }
    ]);
    await openPanel(page);

    await page.locator('#assistant-question').fill('One more?');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-msg-failed')).toContainText('the daily limit');

    await page.locator('#assistant-question').fill('Again');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-msg-answer').nth(1)).toContainText('ok');
    expect(asked[1].history).toEqual([{ role: 'user', text: 'One more?' }]);
  });
});
