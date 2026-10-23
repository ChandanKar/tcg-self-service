import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E09-T10: the VM registry's review UI. The registry API is faked in the browser (an in-memory
 * environment with two groups), so nothing is changed on the server and no cloud call is made:
 * drift / pending badges and the Needs-attention chips, Acknowledge, Move, Reactivate (and its
 * refusal reason), Edit for a VM on page 2, and the delete wording.
 */

declare const ContentRouter: any;
declare const VmRegistry: any;

const ENV_ID = 'env-reg-e2e';

type Vm = Record<string, any>;

function vm(id: string, groupId: string, groupName: string, seq: number, patch: Partial<Vm> = {}): Vm {
  return {
    vmId: id, groupId, groupName, name: id, displayName: id.toUpperCase(), purpose: null, remarks: null,
    provider: 'AWS', region: 'ap-south-1', providerVmId: `i-${id}`, vmType: 'DEV', sequencePosition: seq,
    dependsOnVmIds: [], status: 'RUNNING', stateDriftDetected: false, discoveryPending: false,
    isActive: true, discoveryIgnored: false, deletedAt: null, ...patch,
  };
}

class FakeRegistry {
  groups = [
    { groupId: 'g-disc', name: 'discovered', displayName: 'Auto-Discovered', sequencePosition: 999, dependsOnGroupIds: [] },
    { groupId: 'g-web', name: 'web', displayName: 'Web', sequencePosition: 1, dependsOnGroupIds: [] },
  ];
  vms: Vm[] = [];
  moves: any[] = [];
  reactivateError: string | null = null;

  constructor() {
    this.vms.push(vm('found-1', 'g-disc', 'discovered', 1, { discoveryPending: true, status: 'UNKNOWN' }));
    this.vms.push(vm('found-2', 'g-disc', 'discovered', 2, { discoveryPending: true, status: 'UNKNOWN' }));
    for (let i = 1; i <= 26; i++) {
      this.vms.push(vm(`web-${i}`, 'g-web', 'web', i, { stateDriftDetected: i === 1 }));
    }
    this.vms.push(vm('gone-1', 'g-web', 'web', 40, { isActive: false, status: 'NOT_FOUND' }));
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }

  private active(groupId: string) {
    return this.vms.filter(v => v.groupId === groupId && v.isActive).sort((a, b) => a.sequencePosition - b.sequencePosition);
  }

  private page(items: Vm[], page: number, size: number) {
    return { content: items.slice(page * size, page * size + size),
      page: { size, number: page, totalElements: items.length, totalPages: Math.max(1, Math.ceil(items.length / size)) } };
  }

  async install(page: Page) {
    await page.route(/\/api\/v1\/environments(\?.*)?$/, route => route.request().method() === 'GET'
      ? this.json(route, [{ environmentId: ENV_ID, name: 'reg-e2e', displayName: 'Registry E2E', isActive: true,
        viewerCanOperate: true, viewerCanAdminister: true, viewerCanManageAccess: true,
          serviceType: 'EC2', groupCount: 2, vmCount: this.vms.length, createdAt: new Date().toISOString() }])
      : route.continue());
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms(/.*)?(\\?.*)?$`), route => this.handle(route));
  }

  private handle(route: Route) {
    const req = route.request();
    const url = new URL(req.url());
    const rest = url.pathname.split(`/environments/${ENV_ID}/vms`)[1] || '';
    const parts = rest.split('/').filter(Boolean);
    const q = (k: string, d: number) => Number(url.searchParams.get(k) ?? d);
    if (req.method() === 'GET' && parts.length === 0) {
      return this.json(route, this.groups.map(g => ({
        group: { ...g, vmCount: this.active(g.groupId).length, runningVmCount: this.active(g.groupId).length },
        vms: this.active(g.groupId).slice(0, 25),
      })));
    }
    if (req.method() === 'GET' && parts[0] === 'review' && parts[1] === 'counts') {
      return this.json(route, {
        drift: this.vms.filter(v => v.isActive && v.stateDriftDetected).length,
        pending: this.vms.filter(v => v.isActive && v.discoveryPending).length,
        inactive: this.vms.filter(v => !v.isActive).length,
      });
    }
    if (req.method() === 'GET' && parts[0] === 'review') {
      const state = url.searchParams.get('state');
      const items = this.vms.filter(v => state === 'DRIFT' ? v.isActive && v.stateDriftDetected
        : state === 'PENDING' ? v.isActive && v.discoveryPending : !v.isActive);
      return this.json(route, this.page(items, q('page', 0), q('size', 25)));
    }
    if (req.method() === 'GET' && parts[1] === 'page') {
      return this.json(route, this.page(this.active(parts[0]), q('page', 0), q('size', 25)));
    }
    const target = this.vms.find(v => v.vmId === parts[0]);
    if (req.method() === 'GET' && parts.length === 1) {
      return target ? this.json(route, target) : this.json(route, { message: 'VM not found' }, 404);
    }
    if (req.method() === 'PUT' && parts[1] === 'acknowledge') {
      target!.discoveryPending = false;
      return this.json(route, target);
    }
    if (req.method() === 'PUT' && parts[1] === 'move') {
      const body = req.postDataJSON();
      this.moves.push(body);
      const group = this.groups.find(g => g.groupId === body.targetGroupId)!;
      Object.assign(target!, { groupId: group.groupId, groupName: group.name, discoveryPending: false,
        sequencePosition: Math.max(...this.active(group.groupId).map(v => v.sequencePosition)) + 1 });
      return this.json(route, target);
    }
    if (req.method() === 'POST' && parts[1] === 'reactivate') {
      if (this.reactivateError) return this.json(route, { error: 'Validation', message: this.reactivateError }, 400);
      Object.assign(target!, { isActive: true, status: 'STOPPED' });
      return this.json(route, target);
    }
    return route.continue();
  }
}

async function openRegistry(page: Page, fake: FakeRegistry) {
  await fake.install(page);
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/vm-registry'; });
  await page.waitForFunction(() => typeof VmRegistry !== 'undefined' && (window as any).VmRegistryState?.environments?.length > 0);
  await page.evaluate(id => VmRegistry.manageGroups(id), ENV_ID);
  await expect(page.locator('#vr-tree')).toBeVisible();
  await expect(page.locator('#vrReviewCard')).toBeVisible();
}

const row = (page: Page, name: string) => page.locator('#vr-tree tr', { hasText: name });
const chip = (page: Page, text: string) => page.locator('#vrReviewArea [data-action="vr-review-state"]', { hasText: text });

test.describe('VM registry review UI', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('notes and Needs-attention counts; Mark reviewed clears a pending VM', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);

    await expect(chip(page, 'Pending review (2)')).toBeVisible();
    await expect(chip(page, 'Drift (1)')).toBeVisible();
    await expect(chip(page, 'Removed or inactive (1)')).toBeVisible();
    await expect(row(page, 'found-1').locator('.vr-note.vr-warn')).toHaveText('Found by discovery');
    await expect(row(page, 'web-1').locator('.vr-note.vr-warn')).toHaveText('State changed outside the platform');
    await expect(row(page, 'web-2').locator('[data-action="vr-ack-vm"]')).toHaveCount(0);

    await row(page, 'found-1').locator('[data-action="vr-ack-vm"]').click();

    await expect(chip(page, 'Pending review (1)')).toBeVisible();
    await expect(row(page, 'found-1').locator('.vr-note.vr-warn')).toHaveCount(0);
  });

  test('Move takes a pending VM out of Auto-Discovered without a page reload', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);
    await page.evaluate(() => { (window as any).__noReload = true; });

    await row(page, 'found-2').locator('[data-action="vr-move-vm"]').click();
    const dialog = page.locator('#registryMoveModal'); // U11-T05
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.modal-title')).toHaveText('Move FOUND-2');
    await expect(dialog.locator('.acd-sub')).toHaveText('Now in Auto-Discovered, start order 2');
    await expect(dialog.locator('#rd-move-target option')).toHaveText(['Web · starts 1st']); // not its own group
    await dialog.locator('#rd-move-save').click();

    await expect(dialog).toBeHidden();
    await expect(page.getByText('Moved FOUND-2 to Web.')).toBeVisible();
    expect(fake.moves).toEqual([{ targetGroupId: 'g-web' }]);
    await expect(page.locator('tbody[data-group-id="g-disc"] tr', { hasText: 'found-2' })).toHaveCount(0);
    await expect(page.locator('tbody[data-group-id="g-web"] .vr-count')).toHaveText('27 VMs');
    expect(await page.evaluate(() => (window as any).__noReload)).toBe(true);
  });

  test('Reactivate from the Removed-or-inactive list, and the reason when it is refused', async ({ page }) => {
    const fake = new FakeRegistry();
    fake.reactivateError = 'Instance not found in ap-south-1 - fix the region first';
    await openRegistry(page, fake);

    await chip(page, 'Removed or inactive (1)').click();
    const inactive = page.locator('#vrReviewList tr', { hasText: 'gone-1' });
    await expect(inactive.locator('.vr-note')).toHaveText('Not found in AWS; inactive');
    await expect(inactive.locator('[data-action="vr-edit-vm"]')).toHaveCount(0);
    await inactive.locator('[data-action="vr-reactivate-vm"]').click();
    await expect(page.getByText('Instance not found in ap-south-1 - fix the region first').first()).toBeVisible();

    fake.reactivateError = null;
    await inactive.locator('[data-action="vr-reactivate-vm"]').click();
    await expect(chip(page, 'Removed or inactive')).toHaveCount(0);
  });

  test('Edit works for a VM on the second page of a group', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);

    // U11-T03: a group shows 10 VMs at a time.
    await expect(page.locator('tbody[data-group-id="g-web"] .vr-vm-row')).toHaveCount(10);
    await page.locator('tbody[data-group-id="g-web"] [data-action="vr-group-page"]', { hasText: 'Next' }).click();
    await expect(page.locator('tbody[data-group-id="g-web"] .pager-range')).toHaveText('11–20 of 26');
    await row(page, 'web-12').locator('[data-action="vr-edit-vm"]').click();

    await expect(page.locator('#registerVmModal')).toBeVisible();
    await expect(page.locator('#vmName')).toHaveValue('web-12');
    await expect(page.locator('#vmSequencePosition')).toHaveValue('12');
  });

  test('at phone width the review strip wraps and nothing scrolls the page sideways', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const fake = new FakeRegistry();
    await openRegistry(page, fake);
    await chip(page, 'Pending review (2)').click();
    await expect(page.locator('#vrReviewList tr', { hasText: 'found-1' })).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    const chips = await page.locator('#vrReviewArea [data-action="vr-review-state"]').evaluateAll(
      els => els.map(e => e.getBoundingClientRect().right));
    for (const right of chips) expect(right).toBeLessThanOrEqual(390);
  });

  test('Remove explains that history is kept and discovery ignores the instance', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);

    await row(page, 'web-3').locator('[data-action="vr-delete-vm"]').click();

    await expect(page.getByText(/Remove "WEB-3" from the platform\? Its history is kept and discovery will ignore this instance/))
      .toBeVisible();
  });
});

/**
 * U11-T03 (VmRegistry.dc.html): the environments on the left (8 a page), the selected one's groups
 * and VMs on the right, the selection in the address, and a banner for what needs a look. The
 * environment list, each environment's groups and the review summary are faked in the browser.
 */
test.describe('VM registry layout', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const envs = Array.from({ length: 21 }, (_, i) => ({
    environmentId: `env-l${String(i + 1).padStart(2, '0')}`, name: `layout-${i + 1}`, displayName: `Layout ${String(i + 1).padStart(2, '0')}`,
    isActive: true, serviceType: 'EC2', groupCount: 2, vmCount: 3, viewerCanAdminister: true,
    groupStates: [{ groupId: 'g1', sequencePosition: 1, running: 2, busy: 0, stopped: 0, failed: 0, unknown: 0 },
      { groupId: 'g2', sequencePosition: 2, running: 0, busy: 0, stopped: 1, failed: 0, unknown: 0 }],
  }));

  async function fakeList(page: Page, summary: unknown[] = []) {
    const json = (route: Route, body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    await page.route(/\/api\/v1\/environments(\?.*)?$/, route => route.request().method() === 'GET' ? json(route, envs) : route.continue());
    await page.route(/\/api\/v1\/environments\/review-summary$/, route => json(route, summary));
    await page.route(/\/api\/v1\/environments\/env-l\d+\/vms\/review\/counts$/, route => json(route, { drift: 1, pending: 0, inactive: 0 }));
    await page.route(/\/api\/v1\/environments\/env-l\d+\/vms\/review\?/, route => json(route,
      { content: [], page: { size: 10, number: 0, totalElements: 0, totalPages: 1 } }));
    await page.route(/\/api\/v1\/environments\/env-l\d+\/vms$/, route => {
      const db = { groupId: 'g-db', name: 'db', displayName: 'Database', sequencePosition: 1, dependsOnGroupIds: [], vmCount: 1 };
      const app = { groupId: 'g-app', name: 'app', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: ['g-db'], vmCount: 40 };
      const vmOf = (n: number, patch = {}) => ({ vmId: `vm-${n}`, name: `app-${n}`, displayName: `app-${n}`, groupId: 'g-app',
        instanceType: 'm6i.large', isActive: true, status: 'RUNNING', provider: 'AWS', ...patch });
      return json(route, [
        { group: db, vms: [{ vmId: 'vm-db', name: 'pay-db', displayName: 'pay-db', groupId: 'g-db', instanceType: 'r6g.large',
          isActive: true, status: 'RUNNING', provider: 'AWS', stateDriftDetected: true }] },
        { group: app, vms: Array.from({ length: 25 }, (_, i) => vmOf(i + 1)) },
      ]);
    });
  }

  async function open(page: Page, hash = '#/vm-registry') {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(h => { location.hash = h; }, hash);
    await expect(page.locator('#vr-tree')).toBeVisible({ timeout: 15_000 });
  }

  test('8 environments a page, the first selected with its groups and VMs, the selection in the address', async ({ page }) => {
    await fakeList(page);
    await open(page);

    await expect(page.locator('#vr-env-list .vr-env')).toHaveCount(8);
    await expect(page.locator('#vm-registry-pagination .pager-range')).toHaveText('1–8 of 21');
    await expect(page.locator('.vr-env[aria-current="true"] .vr-env-name')).toHaveText('Layout 01');
    await expect(page.locator('.vr-env').first().locator('.vr-env-meta')).toHaveText('layout-1 · 2 groups · 3 VMs');
    await expect(page.locator('.vr-env').first().locator('.power-strip')).toBeVisible();
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 01');
    await expect(page).toHaveURL(/#\/vm-registry\?env=env-l01$/);

    // The tree: groups in start order with what they wait for, VMs indented with size and notes.
    await expect(page.locator('.vr-group-row .vr-order')).toHaveText(['1st', '2nd · after Database']);
    const db = page.locator('.vr-vm-row', { hasText: 'pay-db' });
    await expect(db.locator('.vr-size')).toHaveText('r6g.large');
    await expect(db.locator('.vr-note.vr-warn')).toHaveText('State changed outside the platform');
    await expect(page.locator('tbody[data-group-id="g-app"] .vr-vm-row')).toHaveCount(10);
    await expect(page.locator('tbody[data-group-id="g-app"] .pager-range')).toHaveText('1–10 of 40');

    await page.locator('#vm-registry-pagination [data-action="vr-env-page"]', { hasText: 'Next' }).click();
    await expect(page.locator('#vm-registry-pagination .pager-range')).toHaveText('9–16 of 21');
    await page.locator('.vr-env', { hasText: 'Layout 12' }).click();
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 12');
    await expect(page).toHaveURL(/#\/vm-registry\?env=env-l12$/);

    // Opening the address again opens that environment, on its page of the list.
    await page.reload();
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 12', { timeout: 15_000 });
    await expect(page.locator('#vm-registry-pagination .pager-range')).toHaveText('9–16 of 21');

    // Add VMs from AWS opens the Add VMs page for the selected environment (U11-T04).
    await page.locator('#vr-detail-head [data-action="vr-add-vms"]').click();
    await expect(page).toHaveURL(/#\/vm-registry\/env-l12\/add$/);
    await expect(page.locator('.sidebar-menu-link[data-content="vm-registry"]')).toHaveClass(/active/);
    await page.goBack();
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 12', { timeout: 15_000 });

    await page.locator('#searchEnvironments').fill('layout 2');
    await expect(page.locator('#vr-env-list .vr-env')).toHaveCount(2); // Layout 20 and 21
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 20');
  });

  test('fits 1440x900 without a page scrollbar', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await fakeList(page);
    await open(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('the banner adds up what needs a look and Review them opens the first environment on that list', async ({ page }) => {
    await fakeList(page, [{ environmentId: 'env-l12', name: 'Layout 12', drift: 1, pending: 0 },
      { environmentId: 'env-l03', name: 'Layout 03', drift: 0, pending: 2 }]);
    await open(page);

    await expect(page.locator('.vr-banner')).toHaveText(/3 VMs need a look:\s+1 changed outside the platform, 2 found in AWS and not in a group\.\s+Review them/);
    await page.locator('.vr-banner').click();
    await expect(page.locator('#vr-sel-title')).toHaveText('Layout 12');
    await expect(page.locator('#vrReviewArea [data-action="vr-review-state"][aria-pressed="true"]')).toHaveText(/Drift/);
  });

  test('users get the no-access page and no registry controls', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await expect(page.locator('[data-no-access] h1').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('[data-action="vr-create-environment"], [data-action="vr-edit-environment"], [data-action="vr-delete-environment"]'))
      .toHaveCount(0);
  });
});

/**
 * U11-T09: every registry action is still there in the new layout: Reactivate an environment, Delete
 * an empty group, Mark as reviewed from the Needs-review list, Reactivate a removed VM (above) and
 * Sync node groups now for EKS. The API is faked in the browser.
 */
test.describe('Every registry action', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const json = (route: Route, body: unknown, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

  test('a deactivated environment shows Reactivate, which brings it back', async ({ page }) => {
    let active = false;
    const reactivations: string[] = [];
    const env = () => ({ environmentId: 'env-off', name: 'old-env', displayName: 'Old Env', isActive: active, serviceType: 'EC2',
      groupCount: 0, vmCount: 0, viewerCanAdminister: true });
    await page.route(/\/api\/v1\/environments(\?.*)?$/, route => {
      const inactive = route.request().url().includes('includeInactive=true');
      return json(route, inactive || active ? [env()] : []);
    });
    await page.route(/\/api\/v1\/environments\/env-off\/reactivate$/, route => {
      reactivations.push(route.request().method());
      active = true;
      return json(route, env());
    });
    await page.route(/\/api\/v1\/environments\/env-off\/vms(\/review\/counts)?$/, route =>
      json(route, route.request().url().endsWith('counts') ? { drift: 0, pending: 0, inactive: 0 } : []));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await expect(page.locator('#vr-env-list')).toContainText('No environments yet', { timeout: 15_000 });

    // U20-T06: two clicks from the registry: Deactivated (n), then Reactivate.
    await page.getByRole('button', { name: 'Deactivated (1)' }).click();
    await expect(page.locator('#filterStatus')).toHaveValue('inactive');
    await expect(page.locator('#vr-sel-title')).toHaveText('Old Env');
    await expect(page.getByRole('button', { name: 'Active environments' })).toBeVisible();
    await expect(page.locator('#vr-detail-head [data-action="vr-delete-environment"]')).toHaveCount(0);
    await page.locator('#vr-detail-head [data-action="vr-reactivate-environment"]').click();

    await expect(page.getByText('Environment reactivated')).toBeVisible();
    expect(reactivations).toEqual(['POST']);
  });

  test('an empty group can be deleted; one with VMs cannot', async ({ page }) => {
    const fake = new FakeRegistry();
    fake.groups.push({ groupId: 'g-empty', name: 'spare', displayName: 'Spare', sequencePosition: 2, dependsOnGroupIds: [] });
    const deletes: string[] = [];
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/groups/g-empty$`), route => {
      deletes.push(route.request().method());
      fake.groups = fake.groups.filter(g => g.groupId !== 'g-empty');
      return route.fulfill({ status: 204 });
    });
    await openRegistry(page, fake);

    await expect(page.locator('tbody[data-group-id="g-web"] [data-action="vr-delete-group"]')).toBeDisabled();
    await page.locator('tbody[data-group-id="g-empty"] [data-action="vr-delete-group"]').click();
    await page.locator('.modal.show .btn-danger', { hasText: 'Delete' }).click();

    await expect(page.locator('tbody[data-group-id="g-empty"]')).toHaveCount(0);
    expect(deletes).toEqual(['DELETE']);
  });

  test('the Needs-review list offers Mark as reviewed for each VM found by discovery', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);

    await chip(page, 'Pending review (2)').click();
    await expect(page.locator('#vrReviewArea [data-action="vr-review-state"][aria-pressed="true"]')).toHaveText('Pending review (2)');
    const listed = page.locator('#vrReviewList tr', { hasText: 'found-1' });
    await expect(listed.locator('.vr-count')).toHaveText('in discovered');
    await listed.locator('[data-action="vr-ack-vm"]').click();

    await expect(chip(page, 'Pending review (1)')).toBeVisible();
    await expect(page.locator('#vrReviewList tr', { hasText: 'found-1' })).toHaveCount(0);
  });

  test('an EKS environment offers Sync node groups now and says when it finished', async ({ page }) => {
    const syncs: string[] = [];
    await page.route(/\/api\/v1\/environments(\?.*)?$/, route => json(route, [{ environmentId: 'env-eks', name: 'hr-eks',
      displayName: 'HR Portal', isActive: true, serviceType: 'EKS', groupCount: 1, vmCount: 2, viewerCanAdminister: true }]));
    await page.route(/\/api\/v1\/environments\/env-eks\/vms(\/review\/counts)?$/, route =>
      json(route, route.request().url().endsWith('counts') ? { drift: 0, pending: 0, inactive: 0 }
        : [{ group: { groupId: 'g-ng', name: 'ng-1', displayName: 'ng-1', sequencePosition: 1, dependsOnGroupIds: [], vmCount: 0 }, vms: [] }]));
    await page.route(/\/api\/v1\/monitoring\/sync\/eks\/env-eks$/, route => {
      syncs.push(route.request().method());
      return json(route, { nodeGroupsSynced: 3 });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await expect(page.locator('#vr-sel-title')).toHaveText('HR Portal', { timeout: 15_000 });

    await expect(page.locator('#vr-detail-head [data-action="vr-add-vms"]')).toHaveCount(0);
    await page.locator('#eks-sync-now-btn').click();

    await expect(page.getByText(/^Node groups synced at \d{1,2}:\d{2}.*: 3 node groups checked\.$/)).toBeVisible();
    expect(syncs).toEqual(['POST']);
    await expect(page.locator('#eks-sync-now-btn')).toBeEnabled();
  });
});

/**
 * U11-T05 (RegistryDialogs.dc.html): Edit group offers only the groups that make no loop and checks
 * the start order before saving; Add group; Tell everyone it's being stopped with the audience
 * count and a reason. The API is faked in the browser.
 */
test.describe('Registry dialogs', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  async function withGroups(page: Page) {
    const fake = new FakeRegistry();
    fake.groups = [
      { groupId: 'g-disc', name: 'discovered', displayName: 'Auto-Discovered', sequencePosition: 999, dependsOnGroupIds: [] },
      { groupId: 'g-db', name: 'db', displayName: 'Database', sequencePosition: 1, dependsOnGroupIds: [] },
      { groupId: 'g-app', name: 'app', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: ['g-db'] },
      { groupId: 'g-web', name: 'web', displayName: 'Web', sequencePosition: 3, dependsOnGroupIds: ['g-app'] },
    ];
    const writes: { method: string, path: string, body: any }[] = [];
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/groups(/.*)?$`), route => {
      const req = route.request();
      if (req.method() === 'GET') return route.continue();
      writes.push({ method: req.method(), path: new URL(req.url()).pathname.split('/groups')[1], body: req.postDataJSON() });
      return route.fulfill({ status: req.method() === 'POST' ? 201 : 200, contentType: 'application/json',
        body: JSON.stringify(req.method() === 'POST' ? { groupId: 'g-new' } : []) });
    });
    await openRegistry(page, fake);
    return writes;
  }

  test('Edit group: a group that waits for this one would make a loop; the start order is checked before saving', async ({ page }) => {
    const writes = await withGroups(page);
    await page.locator('tbody[data-group-id="g-app"] [data-action="vr-edit-group"]').click();

    const dialog = page.locator('#registryGroupModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Edit group: Application');
    await expect(dialog.locator('#rd-group-place')).toHaveValue('2');
    await expect(dialog.locator('#rd-group-place option')).toHaveText(['1st', '2nd', '3rd']);
    const web = dialog.locator('.rd-check', { hasText: 'Web' });
    await expect(web.locator('input')).toBeDisabled();
    await expect(web.locator('.rd-loop')).toHaveText('· would make a loop');
    await expect(dialog.locator('.rd-check', { hasText: 'Database' }).locator('input')).toBeChecked();
    await expect(dialog.locator('.rd-check', { hasText: 'Auto-Discovered' })).toHaveCount(0);

    await dialog.locator('#rd-group-place').selectOption('1');
    await dialog.locator('#rd-group-save').click();
    await expect(dialog.locator('#rd-group-error')).toHaveText('Application waits for Database, so it cannot start first');
    expect(writes).toEqual([]);

    await dialog.locator('.rd-check', { hasText: 'Database' }).locator('input').uncheck();
    await dialog.locator('#rd-group-description').fill('Payments API servers');
    await dialog.locator('#rd-group-save').click();
    await expect(dialog).toBeHidden();
    expect(writes.map(w => `${w.method} ${w.path}`)).toEqual(['PUT /g-app', 'PUT /order']);
    expect(writes[0].body).toMatchObject({ name: 'app', displayName: 'Application', description: 'Payments API servers',
      dependsOnGroupIds: [], sequencePosition: 2 });
    expect(writes[1].body).toEqual({ groupIds: ['g-app', 'g-db', 'g-web'] });
  });

  test('Add group: at the end of the start order unless placed earlier', async ({ page }) => {
    const writes = await withGroups(page);
    await page.locator('#vr-detail-head [data-action="vr-open-group-form"]').click();

    const dialog = page.locator('#registryGroupModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Add a group to Registry E2E');
    await expect(dialog.locator('#rd-group-place')).toHaveValue('4');
    await expect(dialog.locator('input[name="rd-waits"]:disabled')).toHaveCount(0);
    await dialog.locator('#rd-group-name').fill('Reports');
    await dialog.locator('.rd-check', { hasText: 'Web' }).locator('input').check();
    await dialog.locator('#rd-group-save').click();

    await expect(page.getByText('Group Reports added.')).toBeVisible();
    expect(writes.map(w => `${w.method} ${w.path}`)).toEqual(['POST ']);
    expect(writes[0].body).toMatchObject({ name: 'Reports', displayName: 'Reports', sequencePosition: 4, dependsOnGroupIds: ['g-web'] });
  });

  test('Tell everyone it\'s being stopped: the audience, a reason, and who it reached', async ({ page }) => {
    const sent: any[] = [];
    let audience = 9;
    await page.route(/\/api\/v1\/environments\/env-notify\/notify-stop\/audience$/, route =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ count: audience }) }));
    await page.route(/\/api\/v1\/environments\/env-notify\/notify-stop$/, route => {
      sent.push(route.request().postDataJSON());
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ emailedCount: 9 }) });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof (window as any).RegistryDialogs !== 'undefined');
    const open = () => page.evaluate(() => (window as any).RegistryDialogs.notifyStop({ environmentId: 'env-notify', displayName: 'Payments Dev' }));

    await open();
    const dialog = page.locator('#registryNotifyModal');
    await expect(dialog.locator('.modal-title')).toHaveText("Tell everyone it's being stopped");
    await expect(dialog.locator('#rd-notify-audience')).toHaveText('9 people can use Payments Dev');
    await expect(dialog.locator('#rd-notify-send')).toHaveText('Send to 9 people');
    await dialog.locator('#rd-notify-reason').fill('Stopping at 16:00 on Friday for the database upgrade.');
    await dialog.locator('#rd-notify-send').click();
    await expect(page.getByText('Sent to 9 people.')).toBeVisible();
    expect(sent).toEqual([{ reason: 'Stopping at 16:00 on Friday for the database upgrade.' }]);

    audience = 0;
    await expect(dialog).toBeHidden();
    await open();
    await expect(dialog.locator('#rd-notify-audience')).toHaveText('No one with access has an email address.');
    await expect(dialog.locator('#rd-notify-send')).toBeDisabled();
  });
});
