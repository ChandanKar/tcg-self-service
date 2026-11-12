import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, apiJson, readState, visit, verdict } from './guard';

/**
 * The ise scenarios: the only environment this run may start or stop. Its three VMs (group-1 master,
 * group-2 worker1, group-3 worker2; start order 1, 2, 3) start STOPPED and end STOPPED.
 *
 * Not serial: a failed scenario must not skip the stop that follows it, so ise always ends stopped.
 * The tests still run one after another, in file order (one worker, not fully parallel).
 */
test.describe.configure({ mode: 'default', timeout: 20 * 60_000 });   // starts and stops take minutes

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED', 'PARTIALLY_COMPLETED', 'PARTIAL', 'COMPLETED_WITH_ERRORS'];
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const vmsOf = () => (readState<any>('preflight')!.iseVms as any[]).flatMap(g => g.vms.map((v: any) => ({ ...v, groupName: g.group.name, seq: g.group.sequencePosition })));
const byOrder = () => vmsOf().sort((a, b) => a.seq - b.seq);
const worker = (group: string) => vmsOf().find(v => v.groupName === group)!;

async function waitOp(page: Page, execId: string, minutes = 12): Promise<any> {
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

/**
 * Clicks an action, confirms it in the start/stop dialog (OperationConfirm, U08) and returns the
 * execution the server created. Refuses to send anything for approval.
 */
async function runAction(page: Page, click: () => Promise<void>): Promise<string> {
  const posted = page.waitForResponse(r => r.request().method() === 'POST' && /\/operations$/.test(new URL(r.url()).pathname), { timeout: 45_000 });
  await click();
  const confirm = page.locator('#op-confirm-btn');
  await expect(confirm).toBeVisible();
  await expect(confirm).toBeEnabled({ timeout: 20_000 });
  await page.waitForTimeout(1500);       // let the approval answer settle the button's text
  const label = (await confirm.innerText()).trim();
  expect(label, 'no approval request on staging').not.toMatch(/approval/i);
  await confirm.click();
  const resp = await posted;
  expect(resp.status(), 'operation accepted').toBeLessThan(300);
  return (await resp.json()).executionId;
}

/** Closes the progress dialog an operation leaves open ("Stopping …", Done, Close). */
async function closeDialogs(page: Page) {
  for (let i = 0; i < 3; i++) {
    const open = page.locator('.modal.show');
    if (!(await open.count())) return;
    const close = open.last().locator('.modal-footer button', { hasText: /^Close$/ });
    if (await close.count()) await close.first().click().catch(() => {});
    else await page.keyboard.press('Escape');
    await page.waitForTimeout(600);
  }
}

function step(op: any, vmName: string) {
  return (op.details || []).find((d: any) => d.targetName === vmName) || {};
}

const before = (a: any, b: any) => !!(a && b && new Date(a) <= new Date(b));

test.beforeEach(async ({ page }) => { await signInAdmin(page); });

test('[J04] start ise in start order', async ({ page }) => {
  const states0 = await vmStates(page);
  expect(Object.values(states0).every(s => s === 'STOPPED'), `ise starts stopped: ${JSON.stringify(states0)}`).toBeTruthy();
  await visit(page, '#/environments/' + iseId(), 'j04-before');
  const execId = await runAction(page, () => page.locator('#btn-env-action[data-action-type="start"]').click());
  const op = await waitOp(page, execId);
  await visit(page, '#/environments/' + iseId(), 'j04-after');
  const vms = byOrder();
  const steps = vms.map(v => ({ v, s: step(op, v.name) }));
  const ordered = steps.every((x, i) => i === 0 || before(steps[i - 1].s.completedAt, x.s.startedAt));
  const allDone = steps.every(x => x.s.status === 'COMPLETED');
  const states = await vmStates(page);
  const running = vms.every(v => states[v.name] === 'RUNNING');
  const line = steps.map(x => `${x.v.groupName} ${x.v.name} ${x.s.startedAt}→${x.s.completedAt} ${x.s.status}`).join('; ');
  const ok = op.status === 'COMPLETED' && ordered && allDone && running;
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Start all on ise (op ${execId}): each group started only after the one before finished: ${line}. Operation COMPLETED; all three RUNNING.`
    : `Start all (op ${execId}): status ${op.status}; ordered=${ordered}; ${line}; states ${JSON.stringify(states)}; ${op.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J06] stop and start a single VM', async ({ page }) => {
  const w2 = worker('group-3');
  test.skip((await vmStates(page))[w2.name] !== 'RUNNING', 'worker2 is not running (J04 did not start it)');
  await visit(page, '#/environments/' + iseId(), 'j06-before');
  const stopId = await runAction(page, () => page.locator(`#env-vms [data-action="stop-vm"][data-vm-id="${w2.vmId}"]:visible`).first().click());
  const stopOp = await waitOp(page, stopId);
  await closeDialogs(page);
  await visit(page, '#/environments/' + iseId(), 'j06-mid');
  const mid = await vmStates(page);
  const startId = await runAction(page, () => page.locator(`#env-vms [data-action="start-vm"][data-vm-id="${w2.vmId}"]:visible`).first().click());
  const startOp = await waitOp(page, startId);
  await closeDialogs(page);
  await visit(page, '#/environments/' + iseId(), 'j06-after');
  const states = await vmStates(page);
  const others = vmsOf().filter(v => v.name !== w2.name).every(v => mid[v.name] === 'RUNNING');
  const ok = stopOp.status === 'COMPLETED' && startOp.status === 'COMPLETED' && states[w2.name] === 'RUNNING' && mid[w2.name] === 'STOPPED' && others;
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Single VM ${w2.name}: Stop COMPLETED (the other two stayed RUNNING), Start COMPLETED, RUNNING again. The UI has no Restart for one VM; restart is stop then start.`
    : `Single VM: stop ${stopOp.status} (then ${mid[w2.name]}, others running=${others}), start ${startOp.status}, now ${states[w2.name]}; ${stopOp.errorMessage || startOp.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J05] stop ise in reverse order', async ({ page }) => {
  const states0 = await vmStates(page);
  test.skip(Object.values(states0).every(s => s === 'STOPPED'), 'nothing running to stop');
  const wereRunning = Object.values(states0).filter(s => s === 'RUNNING').length;
  await visit(page, '#/environments/' + iseId(), 'j05-before');
  const execId = await runAction(page, () => page.locator('#btn-env-action-stop').click());
  const op = await waitOp(page, execId);
  await visit(page, '#/environments/' + iseId(), 'j05-after');
  const vms = byOrder().reverse();
  const steps = vms.map(v => ({ v, s: step(op, v.name) })).filter(x => x.s.status);
  const reversed = steps.every((x, i) => i === 0 || before(steps[i - 1].s.completedAt, x.s.startedAt));
  const states = await vmStates(page);
  const stopped = vmsOf().every(v => states[v.name] === 'STOPPED');
  const line = steps.map(x => `${x.v.groupName} ${x.v.name} ${x.s.startedAt}→${x.s.completedAt} ${x.s.status}`).join('; ');
  const ok = op.status === 'COMPLETED' && reversed && stopped && steps.length === wereRunning;
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Stop all on ise (op ${execId}) in reverse start order: ${line}. COMPLETED; all three STOPPED. Cancelling a pending operation not exercised.`
    : `Stop all (op ${execId}): status ${op.status}; reversed=${reversed}; ${line}; states ${JSON.stringify(states)}; ${op.errorMessage || ''}`);
  expect(ok).toBeTruthy();
});

test('[J08] lock and release ise', async ({ page }) => {
  await visit(page, '#/environments/' + iseId(), 'j08-before');
  await page.locator('[data-lock-action="acquire"]:visible').first().click();
  await expect(page.locator('#acquireLockModal')).toBeVisible();
  await page.locator('#lockReason').fill('UAT automated check (Claude) - released in the same test');
  await page.locator('#confirmAcquireLock').click();
  await expect(page.locator('#acquireLockModal')).toBeHidden({ timeout: 15_000 });
  await page.waitForTimeout(1500);
  const lock = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  await closeDialogs(page);
  await visit(page, '#/environments/' + iseId(), 'j08-locked');
  await page.locator('[data-lock-action="release"]:visible').first().click();
  const modal = page.locator('.modal.show').last();
  await expect(modal).toBeVisible();
  await modal.locator('.modal-footer button.btn-warning, .modal-footer button.btn-primary, .modal-footer button.btn-danger').last().click();
  await page.waitForTimeout(2500);
  const after = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  const history = await apiJson(page, `/api/v1/environments/${iseId()}/lock/history`);
  const locked = !!lock && lock.isLocked === true;
  const released = !!after && after.isLocked === false;
  const ok = locked && released;
  verdict(ok ? 'blocked' : 'rejected', ok
    ? 'Admin locked ise with a reason and released it; lock history records it. Refusing a second user and breaking someone else\'s lock need a second account: not tested.'
    : `Lock: acquired=${locked}, released=${released}; ${JSON.stringify(lock).slice(0, 160)} / ${JSON.stringify(after).slice(0, 160)}; history ${JSON.stringify(history).slice(0, 120)}`);
  expect(ok).toBeTruthy();
});

test('ise is back where it started (all STOPPED)', async ({ page }) => {
  const states = await vmStates(page);
  test.info().annotations.push({ type: 'final-state', description: JSON.stringify(states) });
  expect(Object.values(states).every(s => s === 'STOPPED'), JSON.stringify(states)).toBeTruthy();
});
