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
  await expect(page.locator('#manageGroupsModal')).toBeVisible();
  await expect(page.locator('#vrReviewCard')).toBeVisible();
}

const row = (page: Page, name: string) => page.locator('#groupsContentArea tr', { hasText: name });
const chip = (page: Page, text: string) => page.locator('#vrReviewArea [data-action="vr-review-state"]', { hasText: text });

test.describe('VM registry review UI', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('badges and Needs-attention counts; Acknowledge clears a pending VM', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);

    await expect(chip(page, 'Pending review (2)')).toBeVisible();
    await expect(chip(page, 'Drift (1)')).toBeVisible();
    await expect(chip(page, 'Removed or inactive (1)')).toBeVisible();
    await expect(row(page, 'found-1').locator('.status-badge.review')).toHaveText(/Pending review/);
    await expect(row(page, 'web-1').locator('.status-badge.drift')).toHaveText(/Drift/);
    await expect(row(page, 'web-2').locator('[data-action="vr-ack-vm"]')).toHaveCount(0);

    await row(page, 'found-1').locator('[data-action="vr-ack-vm"]').click();

    await expect(chip(page, 'Pending review (1)')).toBeVisible();
    await expect(row(page, 'found-1').locator('.status-badge.review')).toHaveCount(0);
  });

  test('Move takes a pending VM out of Auto-Discovered without a page reload', async ({ page }) => {
    const fake = new FakeRegistry();
    await openRegistry(page, fake);
    await page.evaluate(() => { (window as any).__noReload = true; });

    await row(page, 'found-2').locator('[data-action="vr-move-vm"]').click();
    await expect(page.locator('#moveVmModal')).toBeVisible();
    await expect(page.locator('#moveVmTargetGroup option')).toHaveText(['Web']); // not its own group
    await page.locator('#btnSubmitMoveVm').click();

    await expect(page.locator('#moveVmModal')).toBeHidden();
    expect(fake.moves).toEqual([{ targetGroupId: 'g-web' }]);
    await expect(page.locator('div.card[data-group-id="g-disc"] tr', { hasText: 'found-2' })).toHaveCount(0);
    await expect(page.locator('div.card[data-group-id="g-web"]')).toContainText('27/27 VMs');
    expect(await page.evaluate(() => (window as any).__noReload)).toBe(true);
  });

  test('Reactivate from the Removed-or-inactive list, and the reason when it is refused', async ({ page }) => {
    const fake = new FakeRegistry();
    fake.reactivateError = 'Instance not found in ap-south-1 - fix the region first';
    await openRegistry(page, fake);

    await chip(page, 'Removed or inactive (1)').click();
    const inactive = page.locator('#vrReviewList tr', { hasText: 'gone-1' });
    await expect(inactive.locator('.status-badge', { hasText: 'Inactive' })).toBeVisible();
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

    await page.locator('[data-group-id="g-web"] [data-action="vr-group-vm-page"]', { hasText: 'Next' }).click();
    await row(page, 'web-26').locator('[data-action="vr-edit-vm"]').click();

    await expect(page.locator('#registerVmModal')).toBeVisible();
    await expect(page.locator('#vmName')).toHaveValue('web-26');
    await expect(page.locator('#vmSequencePosition')).toHaveValue('26');
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
