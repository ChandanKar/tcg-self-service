import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E05-T08: the operations UI. The operations API is faked in the browser (no cloud calls): a
 * tiny in-memory server answers create / get / cancel / history, and each test moves runs
 * between states to drive the real VmOperations module.
 */

declare const VmOperations: any;
declare const OperationHistory: any;
declare const ContentRouter: any;

type Exec = {
  executionId: string; environmentId: string; operationType: string; status: string;
  totalTargets: number; completedTargets: number; failedTargets: number; skippedTargets: number;
  errorMessage: string | null; startedAt: string; completedAt: string | null;
  initiatedByDisplayName: string; details: any[];
};

class FakeOperations {
  runs = new Map<string, Exec>();
  polls = new Map<string, number>();
  cancels: string[] = [];
  createError: { status: number; message: string } | null = null;
  private seq = 0;

  async install(page: Page) {
    await page.route(/\/api\/v1\/environments\/[^/]+\/operations(\/.*)?(\?.*)?$/, (route) => this.handle(route));
  }

  set(id: string, patch: Partial<Exec>) {
    Object.assign(this.runs.get(id)!, patch);
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }

  private handle(route: Route) {
    const req = route.request();
    const url = new URL(req.url());
    const parts = url.pathname.split('/'); // ['', 'api','v1','environments',env,'operations',id?,'cancel'?]
    const envId = parts[4];
    const id = parts[6];
    if (req.method() === 'POST' && !id) {
      if (this.createError) {
        return this.json(route, { error: 'Error', message: this.createError.message }, this.createError.status);
      }
      const exec: Exec = {
        executionId: `exec-${++this.seq}`, environmentId: envId, operationType: req.postDataJSON().operationType,
        status: 'IN_PROGRESS', totalTargets: 3, completedTargets: 0, failedTargets: 0, skippedTargets: 0,
        errorMessage: null, startedAt: new Date().toISOString(), completedAt: null,
        initiatedByDisplayName: 'Test User 1',
        details: [1, 2, 3].map(n => ({ detailId: `d${n}`, targetName: `vm-${n}`, status: 'in_progress', progressPercentage: 20 })),
      };
      this.runs.set(exec.executionId, exec);
      return this.json(route, exec, 202);
    }
    if (req.method() === 'POST' && parts[7] === 'cancel') {
      this.cancels.push(id);
      this.set(id, { status: 'CANCELLED', completedAt: new Date().toISOString() });
      return this.json(route, this.runs.get(id));
    }
    if (req.method() === 'GET' && id === 'page') {
      // U08-T04: one page of runs, newest first, without steps.
      const size = Number(url.searchParams.get('size')) || 7;
      const number = Number(url.searchParams.get('page')) || 0;
      const all = [...this.runs.values()].filter(r => r.environmentId === envId).reverse();
      return this.json(route, { content: all.slice(number * size, number * size + size).map(r => ({ ...r, details: null })),
        page: { size, number, totalElements: all.length, totalPages: Math.ceil(all.length / size) } });
    }
    if (req.method() === 'GET' && id && id !== 'time-estimates') {
      this.polls.set(id, (this.polls.get(id) || 0) + 1);
      return this.json(route, this.runs.get(id));
    }
    if (req.method() === 'GET' && !id) {
      return this.json(route, [...this.runs.values()].filter(r => r.environmentId === envId).reverse());
    }
    return route.continue();
  }
}

async function ready(page: Page) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof VmOperations !== 'undefined' && typeof Modals !== 'undefined');
}
declare const Modals: any;

const start = (page: Page, envId: string, name: string) =>
  page.evaluate(([e, n]) => { VmOperations.startEnvironment(e, n).catch(() => {}); }, [envId, name]);

test.describe('Operations UI', () => {
  test.beforeEach(async () => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('Cancel in the progress modal cancels the run and shows "Cancelled"', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');
    const cancel = page.locator('#btn-cancel-operation');
    await expect(cancel).toBeVisible({ timeout: 10_000 });

    await cancel.click();
    await expect(page.locator('#confirmModal')).toContainText('VMs already started or stopped stay that way');
    await page.locator('#confirmBtn').click();

    await expect(page.locator('#progress-status-text')).toHaveText('Cancelled', { timeout: 5_000 });
    expect(fake.cancels).toEqual(['exec-1']);
    await expect(cancel).toBeHidden();
  });

  test('two operations in two environments are both tracked until each finishes', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');
    await expect.poll(() => fake.polls.get('exec-1') || 0).toBeGreaterThan(0);
    await page.locator('#btn-close-progress').click();
    await start(page, 'env-b', 'Env B');
    await expect.poll(() => fake.polls.get('exec-2') || 0).toBeGreaterThan(0);

    const aBefore = fake.polls.get('exec-1')!;
    await expect.poll(() => fake.polls.get('exec-1')!, { timeout: 10_000 }).toBeGreaterThan(aBefore + 1);
    expect(await page.evaluate(() => VmOperations.trackedExecutionIds())).toEqual(['exec-1', 'exec-2']);

    fake.set('exec-1', { status: 'COMPLETED', completedTargets: 3, details: [] });
    await expect.poll(() => page.evaluate(() => VmOperations.trackedExecutionIds()), { timeout: 10_000 }).toEqual(['exec-2']);
    // B's modal never showed A's result.
    await expect(page.locator('#progress-status-text')).not.toHaveText(/Done/);
    fake.set('exec-2', { status: 'COMPLETED', completedTargets: 3, details: [] });
    await expect.poll(() => page.evaluate(() => VmOperations.trackedExecutionIds()), { timeout: 10_000 }).toEqual([]);
    await expect(page.locator('#progress-status-text')).toHaveText('Done');
  });

  test('a 403 shows the server reason, not a generic lock hint', async ({ page }) => {
    const fake = new FakeOperations();
    fake.createError = { status: 403, message: 'You cannot operate on VM group(s): Payments' };
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');

    await expect(page.locator('#progress-status-text')).toHaveText('You cannot operate on VM group(s): Payments');
    await expect(page.locator('#btn-cancel-operation')).toBeHidden();
  });

  test('a lock conflict names the holder', async ({ page }) => {
    const fake = new FakeOperations();
    fake.createError = { status: 409, message: 'Environment is locked by another user: Priya' };
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');

    await expect(page.locator('#progress-status-text')).toHaveText('Environment is locked by another user: Priya');
  });

  test('a run where every step failed says Failed with the reason', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');
    await expect(page.locator('#btn-cancel-operation')).toBeVisible({ timeout: 10_000 });
    fake.set('exec-1', {
      status: 'FAILED', failedTargets: 3, errorMessage: 'All 3 steps failed', completedAt: new Date().toISOString(),
      details: [1, 2, 3].map(n => ({ detailId: `d${n}`, targetName: `vm-${n}`, status: 'failed' })),
    });

    await expect(page.locator('#progress-status-text')).toHaveText('Failed: All 3 steps failed', { timeout: 10_000 });
    await expect(page.locator('#btn-cancel-operation')).toBeHidden();
  });

  test('U08-T03: progress by group in start order, the live line, the reason a VM failed, Hide and re-open', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);
    await start(page, 'env-a', 'CRM UAT');
    await expect.poll(() => fake.polls.get('exec-1') || 0).toBeGreaterThan(0);

    const t0 = Date.now() - 102_000;
    const at = (s: number) => new Date(t0 + s * 1000).toISOString();
    const step = (seq: number, group: string, name: string, status: string, from: number | null, to: number | null, extra: any = {}) => ({
      detailId: name, sequencePosition: seq, groupId: 'g-' + group, groupName: group, targetName: name, status,
      startedAt: from === null ? null : at(from), completedAt: to === null ? null : at(to), ...extra });
    fake.set('exec-1', { totalTargets: 6, startedAt: at(0), details: [
      step(1, 'Database', 'crm-db-01', 'COMPLETED', 0, 41), step(2, 'Database', 'crm-db-02', 'COMPLETED', 0, 48),
      step(3, 'Application', 'crm-api-01', 'COMPLETED', 48, 87), step(4, 'Application', 'crm-api-02', 'IN_PROGRESS', 48, null),
      step(5, 'Application', 'crm-api-03', 'IN_PROGRESS', 48, null), step(6, 'Web', 'crm-web-01', 'PENDING', null, null)] });

    const stages = page.locator('#vm-status-list .op-stage');
    await expect(stages.locator('.op-stage-head')).toHaveText([/1st · Database\s*Done in 0:48/, /2nd · Application\s*Starting/,
      /3rd · Web\s*Waits for Application/], { timeout: 10_000 });
    await expect(stages.nth(0).locator('.op-stage-state')).toHaveText(['Running · 0:41', 'Running · 0:48']);
    await expect(stages.nth(2).locator('.op-stage-state')).toHaveText(['Waiting']);
    await expect(page.locator('#progress-status-text')).toHaveText('3 of 6 running · Application is starting; Web waits for it');
    await expect(page.locator('#progress-bar')).toHaveAttribute('aria-valuenow', '3');
    await expect(page.locator('#progress-bar')).toHaveAttribute('aria-label', '3 of 6 VMs running');
    await expect(page.locator('#progress-elapsed')).toHaveText(/^1:4\d$/);
    await expect(page.getByRole('button', { name: 'Cancel the rest' })).toBeVisible();

    // Hide · keeps running: the banner takes over and re-opens the same run.
    await page.getByRole('button', { name: 'Hide · keeps running' }).click();
    await expect(page.locator('#operation-banner')).toBeVisible();
    await expect(page.locator('#operation-count')).toHaveText('3 of 6 done');
    await page.locator('#view-operation-details').click();
    await expect(page.locator('#vm-status-list .op-stage')).toHaveCount(3);

    fake.set('exec-1', { status: 'PARTIAL_SUCCESS', completedAt: at(140), details: [
      step(1, 'Database', 'crm-db-01', 'COMPLETED', 0, 41), step(2, 'Database', 'crm-db-02', 'COMPLETED', 0, 48),
      step(3, 'Application', 'crm-api-01', 'FAILED', 48, 60, { errorMessage: 'AWS has no m5.large capacity in eu-west-1a right now' }),
      step(4, 'Web', 'crm-web-01', 'COMPLETED', 60, 100)] });
    await expect(page.locator('#progress-status-text')).toHaveText('Done with problems: 1 failed', { timeout: 10_000 });
    await expect(page.locator('#vm-status-list .op-stage').nth(1)).toContainText('Failed: AWS has no m5.large capacity in eu-west-1a right now');
    await expect(page.locator('#btn-cancel-operation')).toBeHidden();
    await expect(page.locator('#btn-close-progress')).toHaveText('Close');
    await expect(page.locator('.op-progress-time')).toHaveText('2:20 in all');

    // U17-T03: partly done says what ran, why the rest did not, a retry for that VM and See steps.
    const result = page.locator('#progress-result .ui-state-partial');
    await expect(result).toHaveAttribute('role', 'alert');
    await expect(result.locator('.ui-state-title')).toHaveText('Started 3 of 4 VMs.');
    await expect(result.locator('.ui-state-text')).toHaveText("crm-api-01 didn't start: AWS has no m5.large capacity in eu-west-1a right now.");
    await expect(result.getByRole('button', { name: 'Retry crm-api-01' })).toBeVisible();
    const toast = page.locator('#notification-container .toast-warning').last();
    await expect(toast).toContainText("Started 3 of 4 VMs. crm-api-01 didn't start");
    await expect(toast.getByRole('button', { name: 'See steps' })).toBeVisible();
    await result.getByRole('button', { name: 'See steps' }).click();
    await expect(page.locator('#dynamicSlideoutPanel[data-variant="op-history"]')).toBeVisible();
  });

  test('U08-T04: the history panel pages runs, names who started them, expands steps, retries a failed VM and cancels', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);
    await start(page, 'env-a', 'Env A');                 // exec-1, will be partial
    await expect.poll(() => fake.polls.get('exec-1') || 0).toBeGreaterThan(0);
    const ago = (min: number) => new Date(Date.now() - min * 60000).toISOString();
    fake.set('exec-1', { status: 'PARTIAL_SUCCESS', completedTargets: 1, failedTargets: 1, skippedTargets: 1,
      startedAt: ago(10), completedAt: ago(8.8), initiatedByDisplayName: 'Priya Nair', scopeLabel: 'Application group',
      details: [
        { detailId: 'd1', sequencePosition: 1, groupId: 'g-app', groupName: 'Application', targetType: 'vm', targetId: 'vm-rw',
          targetName: 'report-worker', status: 'FAILED', errorMessage: 'AWS: no r6i.xlarge capacity in ap-south-1b right now.' },
        { detailId: 'd2', sequencePosition: 2, groupId: 'g-app', groupName: 'Application', targetType: 'vm', targetId: 'vm-etl',
          targetName: 'etl-runner', status: 'SKIPPED' }] } as any);
    await page.locator('#btn-close-progress').click();
    await start(page, 'env-a', 'Env A');                 // exec-2, still running
    await expect.poll(() => fake.polls.get('exec-2') || 0).toBeGreaterThan(0);
    await page.locator('#btn-close-progress').click();
    for (let i = 3; i <= 9; i++) {                        // seven more, finished by the nightly schedule
      await start(page, 'env-a', 'Env A');
      await expect.poll(() => fake.runs.size).toBe(i);
      fake.set(`exec-${i}`, { status: 'COMPLETED', completedTargets: 3, startedAt: ago(60 * i), completedAt: ago(60 * i - 4),
        triggerSource: 'SCHEDULE', triggerName: 'Nightly batch', scopeLabel: 'whole environment' } as any);
      await page.locator('#btn-close-progress').click();
    }

    await page.evaluate(() => OperationHistory.open({ environmentId: 'env-a', displayName: 'Env A', groups: [] }));
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="op-history"]');
    await expect(panel.locator('#dynamicSlideoutTitle')).toHaveText('History · Env A');
    await expect(panel.locator('.op-history-run')).toHaveCount(7, { timeout: 10_000 });
    await expect(panel.locator('.op-history-pager')).toContainText('1–7 of 9');
    await expect(panel.getByRole('button', { name: 'Previous' })).toBeDisabled();
    const nightly = panel.locator('.op-history-run[data-execution-id="exec-9"]');
    await expect(nightly.locator('.op-history-who')).toHaveText('Schedule "Nightly batch"');
    await expect(nightly.locator('.op-history-what')).toHaveText('Start, whole environment');
    await expect(nightly.locator('.op-history-took')).toHaveText('4:00');
    await expect(nightly.locator('[data-col="result"]')).toHaveText('Done');

    await panel.getByRole('button', { name: 'Next' }).click();
    await expect(panel.locator('.op-history-pager')).toContainText('8–9 of 9');
    const partial = panel.locator('.op-history-run[data-execution-id="exec-1"]');
    const running = panel.locator('.op-history-run[data-execution-id="exec-2"]');
    await expect(partial.locator('[data-col="result"]')).toHaveText('1 failed');
    await expect(partial.locator('.op-history-who')).toHaveText('Priya Nair');
    await expect(partial.locator('.op-history-what')).toHaveText('Start Application group');
    await expect(partial.locator('[data-op-cancel]')).toHaveCount(0);
    await expect(running.locator('[data-col="result"]')).toHaveText('Running');

    // Expanding loads the steps once: the reason, the skipped VM and Retry for the failed one.
    await partial.locator('.op-history-row').click();
    await expect(partial.locator('.op-history-row')).toHaveAttribute('aria-expanded', 'true');
    await expect(partial.locator('.op-history-step')).toHaveCount(2);
    await expect(partial.locator('.op-history-step').nth(0)).toContainText('AWS: no r6i.xlarge capacity in ap-south-1b right now.');
    await expect(partial.locator('.op-history-step').nth(1)).toContainText('Skipped');

    // Cancel on the running row still cancels.
    await running.locator('[data-op-cancel]').click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => fake.cancels).toEqual(['exec-2']);
    await expect(running.locator('[data-col="result"]')).toHaveText('Cancelled', { timeout: 10_000 });
    await expect(partial.locator('.op-history-step')).toHaveCount(2);    // still expanded after the reload

    await partial.getByRole('button', { name: 'Retry report-worker' }).click();
    await expect(panel).not.toHaveClass(/show/);
    await expect(page.locator('#operationConfirmModal .modal-title')).toHaveText('Start report-worker?');
  });

  test('U08-T04: History on the environment page opens the panel and Escape returns to the button', async ({ page, playwright }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    const envId = await seedEnvironment(playwright, 'Hist Btn');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
    const button = page.locator('#btn-operation-history');
    await expect(button).toBeVisible({ timeout: 15_000 });
    await button.click();
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="op-history"]');
    await expect(panel.locator('#dynamicSlideoutTitle')).toHaveText(/^History · Hist Btn \d+$/);
    await expect(panel.locator('.op-history-list')).toContainText('No starts or stops yet.');
    await expect(panel.locator('.op-history-pager')).toContainText('0 of 0');
    await page.keyboard.press('Escape');
    await expect(button).toBeFocused();
  });
});
