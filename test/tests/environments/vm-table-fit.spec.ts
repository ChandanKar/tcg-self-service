import { test, expect, type APIRequestContext } from '@playwright/test';
import { canSignIn, devIds } from '../../fixtures/auth';

/**
 * U21-T15: on a laptop window (1366 x 657) the environment's VM table never grows past its card.
 * Staging's ISE has VM names like mcube_dev_poc_master_100.112.1.135 (34 characters, no spaces):
 * the table was cut on the right, so each VM's Start button and the groups' Start group were not
 * visible and "CPU 24 h" wrapped on three lines. Now a long name wraps after "_" or "." and stays
 * readable in full (and on hover), and every button shows.
 */
test.describe('Environment page: the VM table fits its card', () => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');

  const names = ['mcube_dev_poc_master_100.112.1.135', 'mcube_dev_poc_work1_100.112.1.162', 'mcube_dev_poc_worker2_100.112.1.170'];

  async function api(playwright: any): Promise<APIRequestContext> {
    return playwright.request.newContext({
      baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin!, 'Content-Type': 'application/json' },
    });
  }

  /** An environment with ISE's three long names, its rows faked as staging shows them; returns its id. */
  async function seedIse(page: any, playwright: any): Promise<string> {
    const admin = await api(playwright);
    const stamp = Date.now();
    const env = await (await admin.post('/api/v1/environments', { data: { name: `fit-${stamp}`, displayName: `Fit ${stamp}`,
      cloudProvider: 'AWS', region: 'ap-south-1' } })).json();
    for (let i = 0; i < names.length; i++) {
      const group = await (await admin.post(`/api/v1/environments/${env.environmentId}/groups`, { data: { name: `group-${i + 1}`,
        displayName: `Group-${i + 1}`, sequencePosition: i + 1 } })).json();
      await admin.post(`/api/v1/environments/${env.environmentId}/vms`, { data: { groupId: group.groupId, name: `${names[i]}-${stamp}`,
        displayName: names[i], provider: 'AWS', region: 'ap-south-1', instanceType: 'm6a.xlarge',
        providerVmId: `i-0${String(stamp).slice(-8)}${String(i).padStart(8, '0')}`, sequencePosition: 1 } });
    }
    await admin.dispose();

    // The rows as staging shows them: an IP, a size, Stopped and a CPU figure on each (ISE, 2026-11-11).
    await page.route(new RegExp(`/api/v1/environments/${env.environmentId}/vms$`), async route => {
      const groups = await (await route.fetch()).json();
      groups.forEach((g: any) => (g.vms || []).forEach((vm: any) => Object.assign(vm, {
        privateIp: String(vm.displayName).split('_').pop(), instanceType: 'm6a.xlarge', status: 'STOPPED', cpuAvg24h: 13 })));
      await route.fulfill({ json: groups });
    });
    return env.environmentId;
  }

  test('34-character VM names at 1366 x 657: every row and group button shows, each name is readable in full', async ({ page, playwright }) => {
    const envId = await seedIse(page, playwright);
    await page.setViewportSize({ width: 1366, height: 657 });
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.admin! });
    await page.goto(`/home#/environments/${envId}`);
    const table = page.locator('.env-vm-table');
    await expect(table.locator('.env-vm-row')).toHaveCount(3, { timeout: 15_000 });

    const fit = await page.evaluate(() => {
      const card = document.querySelector('.env-vms-panel')!.getBoundingClientRect();
      const inCard = (el: Element) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.left >= card.left - 1 && b.right <= card.right + 1; };
      const buttons = [...document.querySelectorAll('.env-vm-table .env-vm-actions .btn, .env-vm-table .env-group-actions .btn')];
      const names = [...document.querySelectorAll('.env-vm-name-text')] as HTMLElement[];
      const name = names[0];
      const cpu = document.querySelector('.env-vm-table thead th:nth-child(5)') as HTMLElement;
      return {
        tableWider: Math.round(document.querySelector('.env-vm-table')!.getBoundingClientRect().width - card.width),
        buttons: buttons.length, buttonsInCard: buttons.filter(inCard).length,
        nameTitle: name ? name.title : '(no name span)',
        namesShown: names.map(n => n.innerText.replace(/\s+/g, '')),
        namesCut: names.filter(n => n.scrollWidth > n.clientWidth + 1 || n.getBoundingClientRect().right > card.right + 1).length,
        cpuLines: Math.round(cpu.getBoundingClientRect().height / parseFloat(getComputedStyle(cpu).lineHeight || '18')),
      };
    });
    // Acceptance: Details and Start on each of the 3 rows plus Start group on each of the 3 groups, all inside the card.
    expect(fit.buttons).toBe(9);
    expect(fit.buttonsInCard, `${fit.buttonsInCard} of 9 buttons inside the card; table ${fit.tableWider}px wider than it`).toBe(9);
    expect(fit.tableWider).toBeLessThanOrEqual(1);
    expect(fit.nameTitle).toBe('mcube_dev_poc_master_100.112.1.135');
    expect(fit.namesShown).toEqual(names);
    expect(fit.namesCut).toBe(0);
    expect(fit.cpuLines).toBeLessThanOrEqual(2);
    await expect(table.locator('thead th').nth(4)).toHaveText('CPU 24 h');
  });

  test('U21-T20: at 1280 x 720 the CPU column gives way, names get room and the buttons stay in their column', async ({ page, playwright }) => {
    const envId = await seedIse(page, playwright);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.admin! });
    await page.goto(`/home#/environments/${envId}`);
    const table = page.locator('.env-vm-table');
    await expect(table.locator('.env-vm-row')).toHaveCount(3, { timeout: 15_000 });
    await expect(table.locator('thead th', { hasText: 'CPU 24 h' })).toBeHidden();

    const fit = await page.evaluate(() => {
      const card = document.querySelector('.env-vms-panel')!.getBoundingClientRect();
      const inCard = (el: Element) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.left >= card.left - 1 && b.right <= card.right + 1; };
      const buttons = [...document.querySelectorAll('.env-vm-table .env-vm-actions .btn, .env-vm-table .env-group-actions .btn')];
      const nameCells = [...document.querySelectorAll('.env-vm-row td.env-vm-name')] as HTMLElement[];
      const nameTexts = [...document.querySelectorAll('.env-vm-name-text')] as HTMLElement[];
      const right = (sel: string) => [...document.querySelectorAll(sel)].map(e => Math.round(e.getBoundingClientRect().right));
      return {
        buttons: buttons.length, buttonsInCard: buttons.filter(inCard).length,
        nameWidth: Math.min(...nameCells.map(c => Math.round(c.getBoundingClientRect().width))),
        nameLines: Math.max(...nameTexts.map(n => Math.round(n.getBoundingClientRect().height / parseFloat(getComputedStyle(n).lineHeight || '18')))),
        groupActionRight: right('.env-group-actions'), vmActionRight: right('.env-vm-row td:last-child'),
      };
    });
    expect(fit.buttons).toBe(9);
    expect(fit.buttonsInCard, `${fit.buttonsInCard} of 9 buttons inside the card`).toBe(9);
    expect(fit.nameWidth, 'the VM name column has room').toBeGreaterThanOrEqual(110);
    expect(fit.nameLines, 'a 34-character name takes at most three lines').toBeLessThanOrEqual(3);
    // Each group's button sits in the same column as the VMs' buttons.
    expect(new Set([...fit.groupActionRight, ...fit.vmActionRight]).size).toBe(1);
  });
});
