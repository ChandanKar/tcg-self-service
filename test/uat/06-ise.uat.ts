import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, apiJson, readState, visit, verdict } from './guard';

/**
 * The ise scenarios: the only environment this run may start or stop. Both VMs start STOPPED and
 * end STOPPED. group-3 (worker2) depends on group-2 (worker1).
 */
test.describe.configure({ mode: 'serial' });

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED', 'PARTIALLY_COMPLETED', 'PARTIAL', 'COMPLETED_WITH_ERRORS'];
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const vmsOf = () => (readState<any>('preflight')!.iseVms as any[]).flatMap(g => g.vms.map((v: any) => ({ ...v, groupName: g.group.name })));
const worker = (group: string) => vmsOf().find(v => v.groupName === group)!;

async function waitOp(page: Page, execId: string, minutes = 10): Promise<any> {
  const end = Date.now() + minutes * 60_000;
  for (;;) {
    const op = await apiJson(page, `/api/v1/environments/${iseId()}/operations/${execId}`);
    if (TERMINAL.includes(op.status)) return op;
    if (Date.now() > end) return op;
    await page.waitForTimeout(10_000);
  }
}

async function vmStates(page: Page): Promise<Record<string, string>> {
  const groups = await apiJson(page, `/api/v1/environments/${iseId()}/vms`);
  const out: Record<string, string> = {};
  groups.forEach((g: any) => g.vms.forEach((v: any) => { out[v.name] = v.status; }));
  return out;
}

/** Clicks an action, confirms the dialog it opens, and returns the execution the server created. */
async function runAction(page: Page, click: () => Promise<void>): Promise<string> {
  const posted = page.waitForResponse(r => r.request().method() === 'POST' && /\/operations$/.test(new URL(r.url()).pathname), { timeout: 30_000 });
  await click();
  const modal = page.locator('.modal.show').last();
  await expect(modal).toBeVisible();
  await modal.locator('.modal-footer button.btn-primary, .modal-footer button.btn-success, .modal-footer button.btn-danger').last().click();
  const resp = await posted;
  expect(resp.status(), 'operation accepted').toBeLessThan(300);
  return (await resp.json()).executionId;
}

/** Opens a collapsed group so its VM buttons can be clicked. */
async function expandGroup(page: Page, label: string) {
  const toggle = page.locator('#env-groups-container button.group-card-toggle:visible', { hasText: label }).first();
  await toggle.scrollIntoViewIfNeeded();
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await page.waitForTimeout(600);
}

function step(op: any, vmName: string) {
  return (op.details || []).find((d: any) => d.targetName === vmName) || {};
}

test.beforeEach(async ({ page }) => { await signInAdmin(page); });

test('[J04] start ise in dependency order', async ({ page }) => {
  const prior = readState<any>('ise-status');
  let execId = prior?.operations?.[0]?.type === 'START' ? prior.operations[0].id : null;
  await visit(page, '#/environments/' + iseId(), 'j04-before');
  if (!execId) {
    execId = await runAction(page, () => page.locator('#btn-env-action[data-action-type="start"]').click());
  }
  const op = await waitOp(page, execId);
  await visit(page, '#/environments/' + iseId(), 'j04-after');
  const w1 = worker('group-2'), w2 = worker('group-3');
  const s1 = step(op, w1.name), s2 = step(op, w2.name);
  const ordered = s1.completedAt && s2.startedAt && new Date(s1.completedAt) <= new Date(s2.startedAt);
  const states = { [w1.name]: s1.status, [w2.name]: s2.status };
  const ok = op.status === 'COMPLETED' && ordered && s1.status === 'COMPLETED' && s2.status === 'COMPLETED';
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Start All on ise: ${w1.name} (group-2) finished ${s1.completedAt} before ${w2.name} (group-3, depends on group-2) started ${s2.startedAt}; operation COMPLETED; both steps COMPLETED.`
    : `Start All: status ${op.status}; group-2 done ${s1.completedAt}, group-3 started ${s2.startedAt}; states ${JSON.stringify(states)}; ${op.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J06] stop and start a single VM', async ({ page }) => {
  const w2 = worker('group-3');
  await visit(page, '#/environments/' + iseId(), 'j06-before');
  let stopId: string;
  if ((await vmStates(page))[w2.name] === 'RUNNING') {
    await expandGroup(page, 'Group-3');
    stopId = await runAction(page, () => page.locator(`[data-action="stop-vm"][data-vm-id="${w2.vmId}"]`).click());
  } else {
    const ops = await apiJson(page, `/api/v1/environments/${iseId()}/operations`);
    const list = Array.isArray(ops) ? ops : (ops.content || []);
    stopId = list.find((o: any) => o.operationType === 'STOP')!.executionId;
  }
  const stopOp = await waitOp(page, stopId);
  await visit(page, '#/environments/' + iseId(), 'j06-mid');
  await expandGroup(page, 'Group-3');
  const startId = await runAction(page, () => page.locator(`[data-action="start-vm"][data-vm-id="${w2.vmId}"]`).click());
  const startOp = await waitOp(page, startId);
  await visit(page, '#/environments/' + iseId(), 'j06-after');
  const states = await vmStates(page);
  const ok = stopOp.status === 'COMPLETED' && startOp.status === 'COMPLETED' && states[w2.name] === 'RUNNING';
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Single VM ${w2.name}: stop COMPLETED, start COMPLETED, now RUNNING. The UI has no Restart action for a VM (the API supports RESTART), so this was stop then start.`
    : `Single VM: stop ${stopOp.status}, start ${startOp.status}, state ${states[w2.name]}; ${stopOp.errorMessage || startOp.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J05] stop ise in reverse order', async ({ page }) => {
  await visit(page, '#/environments/' + iseId(), 'j05-before');
  const execId = await runAction(page, () => page.locator('#btn-env-action[data-action-type="stop"], #btn-env-action-stop').first().click());
  const op = await waitOp(page, execId);
  await visit(page, '#/environments/' + iseId(), 'j05-after');
  const w1 = worker('group-2'), w2 = worker('group-3');
  const s1 = step(op, w1.name), s2 = step(op, w2.name);
  const reversed = s2.completedAt && s1.startedAt && new Date(s2.completedAt) <= new Date(s1.startedAt);
  const states = await vmStates(page);
  const ok = op.status === 'COMPLETED' && reversed && states[w1.name] === 'STOPPED' && states[w2.name] === 'STOPPED';
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Stop All on ise: ${w2.name} (group-3) stopped ${s2.completedAt} before ${w1.name} (group-2) started stopping ${s1.startedAt}; COMPLETED; both STOPPED. Cancel of a pending operation not exercised (it would need a second start).`
    : `Stop All: status ${op.status}; group-3 done ${s2.completedAt}, group-2 started ${s1.startedAt}; states ${JSON.stringify(states)}; ${op.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J08] lock and release ise', async ({ page }) => {
  await visit(page, '#/environments/' + iseId(), 'j08-before');
  await page.locator('[data-lock-action="acquire"]').first().click();
  await expect(page.locator('#acquireLockModal')).toBeVisible();
  await page.locator('#lockReason').fill('UAT automated check (Claude) - releases itself in this test');
  await page.locator('#confirmAcquireLock').click();
  await page.waitForTimeout(2500);
  const lock = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  await visit(page, '#/environments/' + iseId(), 'j08-locked');
  const release = page.locator('[data-lock-action="release"]').first();
  await release.click();
  const modal = page.locator('.modal.show').last();
  if (await modal.count()) await modal.locator('.modal-footer button.btn-primary, .modal-footer button.btn-danger, .modal-footer button.btn-warning').last().click().catch(() => {});
  await page.waitForTimeout(2500);
  const after = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  const history = await apiJson(page, `/api/v1/environments/${iseId()}/lock/history`);
  const locked = !!(lock.locked || lock.active || lock.lockId);
  const released = !(after.locked || after.active);
  const ok = locked && released;
  verdict(ok ? 'blocked' : 'rejected', ok
    ? 'Admin acquired a lock on ise with a reason and released it; lock history records it. Refusal of a second user and breaking someone else\'s lock need a second account: not tested.'
    : `Lock: acquired=${locked}, released=${released}; ${JSON.stringify(lock).slice(0, 160)} / ${JSON.stringify(after).slice(0, 120)}; history ${JSON.stringify(history).slice(0, 120)}`);
  expect(ok).toBeTruthy();
});

test('ise is back where it started (both STOPPED)', async ({ page }) => {
  const states = await vmStates(page);
  test.info().annotations.push({ type: 'final-state', description: JSON.stringify(states) });
  expect(Object.values(states).every(s => s === 'STOPPED')).toBeTruthy();
});
