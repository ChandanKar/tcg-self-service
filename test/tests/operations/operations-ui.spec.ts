import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E05-T08: the operations UI. The operations API is faked in the browser (no cloud calls): a
 * tiny in-memory server answers create / get / cancel / history, and each test moves runs
 * between states to drive the real VmOperations module.
 */

declare const VmOperations: any;

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

  test('Cancel in the progress modal cancels the run and shows "Operation cancelled"', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);

    await start(page, 'env-a', 'Env A');
    const cancel = page.locator('#btn-cancel-operation');
    await expect(cancel).toBeVisible({ timeout: 10_000 });

    await cancel.click();
    await expect(page.locator('#confirmModal')).toContainText('VMs already started or stopped stay that way');
    await page.locator('#confirmBtn').click();

    await expect(page.locator('#progress-status-text')).toHaveText('Operation cancelled', { timeout: 5_000 });
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
    await expect(page.locator('#progress-status-text')).not.toHaveText(/Completed/);
    fake.set('exec-2', { status: 'COMPLETED', completedTargets: 3, details: [] });
    await expect.poll(() => page.evaluate(() => VmOperations.trackedExecutionIds()), { timeout: 10_000 }).toEqual([]);
    await expect(page.locator('#progress-status-text')).toHaveText('Completed successfully');
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

    await expect(page.locator('#progress-status-text')).toHaveText('Failed — All 3 steps failed', { timeout: 10_000 });
    await expect(page.locator('#btn-cancel-operation')).toBeHidden();
  });

  test('history labels statuses, shows skipped steps and cancels a running row', async ({ page }) => {
    const fake = new FakeOperations();
    await fake.install(page);
    await ready(page);
    await start(page, 'env-a', 'Env A');                 // exec-1, will be partial
    await expect.poll(() => fake.polls.get('exec-1') || 0).toBeGreaterThan(0);
    fake.set('exec-1', { status: 'PARTIAL_SUCCESS', completedTargets: 1, failedTargets: 1, skippedTargets: 1,
      completedAt: new Date().toISOString(), details: [] });
    await page.locator('#btn-close-progress').click();
    await start(page, 'env-a', 'Env A');                 // exec-2, still running
    await expect.poll(() => fake.polls.get('exec-2') || 0).toBeGreaterThan(0);
    await page.locator('#btn-close-progress').click();

    await page.evaluate(() => VmOperations.showHistoryModal('env-a', 'Env A'));
    const partial = page.locator('.operation-history tr[data-execution-id="exec-1"]');
    const running = page.locator('.operation-history tr[data-execution-id="exec-2"]');
    await expect(partial.locator('[data-col="status"]')).toHaveText('Completed with failures');
    await expect(partial).toContainText('skipped 1');
    await expect(partial.locator('[data-op-cancel]')).toHaveCount(0);

    await running.locator('[data-op-cancel]').click();
    await page.locator('#confirmBtn').click();

    await expect.poll(() => fake.cancels).toEqual(['exec-2']);
    await expect(page.locator('.operation-history tr[data-execution-id="exec-2"] [data-col="status"]'))
      .toHaveText('Cancelled', { timeout: 10_000 });
  });
});
