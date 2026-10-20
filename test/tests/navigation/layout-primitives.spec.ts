import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U04-T02: the layout pieces of the new screens. A page built from tabs, a 260 px list next to a
 * detail panel with 10 rows and a pager fits 1440x900 without scrolling; the tabs work from the
 * keyboard; the filter group reports its pressed button; the side panel keeps its actions at the
 * bottom while its body scrolls.
 */

declare const Tabs: any;
declare const FilterGroup: any;
declare const Pagination: any;
declare const Slideout: any;

async function ready(page: Page) {
  test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof Tabs !== 'undefined' && typeof FilterGroup !== 'undefined');
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  // The first page renders again once its data arrives; mount the fixture after that.
  await page.waitForLoadState('networkidle');
}

async function mountFixture(page: Page) {
  await page.evaluate(() => {
    const rows = Array.from({ length: 10 }, (_, i) =>
      `<tr><td>web-${String(i + 1).padStart(2, '0')}</td><td class="mono">t3.medium</td><td>Running</td></tr>`).join('');
    const groups = ['Database', 'Application', 'Web', 'Batch'].map((name, i) =>
      `<button type="button" aria-current="${i === 2}"><b>${i + 1} · ${name}</b><span>Starts after ${name}</span></button>`).join('');
    document.getElementById('content-area')!.innerHTML = `
      <div class="page" id="fixture-page">
        <div class="content-header"><h1>Payments Prod Mirror</h1><p>54 VMs in 4 groups</p></div>
        ${Tabs.markup({ id: 'fx-tabs', label: 'Environment sections', active: 'vms',
          tabs: [{ key: 'vms', label: 'VMs', count: 54 }, { key: 'cost', label: 'Cost' }, { key: 'settings', label: 'Settings' }] })}
        ${Tabs.panel({ id: 'fx-tabs', key: 'vms', active: 'vms' }, `
          <div class="master-detail">
            <nav class="md-list" aria-label="Groups"><div class="md-list-title">4 groups</div>${groups}</nav>
            <section class="panel">
              <div class="panel-head"><h2>Web · 24 VMs</h2>${FilterGroup.markup({ label: 'Show', active: 'all', action: 'fx-filter',
                options: [{ key: 'all', label: 'All' }, { key: 'running', label: 'Running' }, { key: 'stopped', label: 'Stopped' }] })}</div>
              <table class="table mb-0"><thead><tr><th>VM</th><th>Size</th><th>State</th></tr></thead><tbody>${rows}</tbody></table>
              <div class="panel-foot">${Pagination.buildPager({ page: 1, totalItems: 24, action: 'fx-page', key: 'web' })}</div>
            </section>
          </div>`)}
        ${Tabs.panel({ id: 'fx-tabs', key: 'cost', active: 'vms' }, '<p>Cost</p>')}
        ${Tabs.panel({ id: 'fx-tabs', key: 'settings', active: 'vms' }, '<p>Settings</p>')}
      </div>`;
    (window as any).__tabChanges = [];
    Tabs.bind(document.getElementById('fixture-page'), (key: string) => (window as any).__tabChanges.push(key));
  });
}

test.describe('Layout primitives', () => {
  test('tabs, a 260 px list, a 10-row detail and a pager fit 1440x900 without scrolling', async ({ page }) => {
    await ready(page);
    await mountFixture(page);

    await expect(page.locator('.pager-range')).toHaveText('1–10 of 24');
    await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
    const list = await page.locator('.md-list').boundingBox();
    expect(list!.width).toBe(260);
    const fits = await page.locator('#mainContent').evaluate(el => el.scrollHeight <= el.clientHeight);
    expect(fits, 'the page should not need to scroll').toBeTruthy();
    const pager = await page.locator('.pager').boundingBox();
    expect(pager!.y + pager!.height).toBeLessThanOrEqual(900);
    await expect(page.locator('.md-list button[aria-current="true"]')).toHaveCSS('box-shadow', /217, 162, 27/);
  });

  test('the tabs move and select with the arrow keys, Home and End', async ({ page }) => {
    await ready(page);
    await mountFixture(page);
    const vms = page.getByRole('tab', { name: /VMs/ });
    const cost = page.getByRole('tab', { name: 'Cost' });
    const settings = page.getByRole('tab', { name: 'Settings' });

    await vms.focus();
    await page.keyboard.press('ArrowRight');
    await expect(cost).toBeFocused();
    await expect(cost).toHaveAttribute('aria-selected', 'true');
    await expect(vms).toHaveAttribute('tabindex', '-1');
    await expect(page.locator('#fx-tabs-panel-cost')).toBeVisible();
    await expect(page.locator('#fx-tabs-panel-vms')).toBeHidden();

    await page.keyboard.press('End');
    await expect(settings).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await expect(vms).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(settings).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('Home');
    await expect(vms).toHaveAttribute('aria-selected', 'true');
    expect(await page.evaluate(() => (window as any).__tabChanges)).toEqual(['cost', 'settings', 'vms', 'settings', 'vms']);

    await cost.click();
    await expect(cost).toHaveAttribute('aria-selected', 'true');
  });

  test('the filter group shows its pressed button filled navy', async ({ page }) => {
    await ready(page);
    await mountFixture(page);
    const group = page.getByRole('group', { name: 'Show' });
    await expect(group.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    await expect(group.getByRole('button', { name: 'All' })).toHaveCSS('background-color', 'rgb(20, 36, 92)');
    await expect(group.getByRole('button', { name: 'Running' })).toHaveAttribute('aria-pressed', 'false');
  });

  test('the side panel keeps its actions at the bottom while the body scrolls', async ({ page }) => {
    await ready(page);
    await page.evaluate(() => {
      const body = Array.from({ length: 60 }, (_, i) => `<p>Line ${i + 1}</p>`).join('');
      Slideout.open('Start 2 VMs', body, { width: 480,
        footer: '<button type="button" class="btn btn-secondary" id="fx-cancel">Cancel</button><button type="button" class="btn btn-success" id="fx-start">Start 2 VMs</button>' });
    });
    const panel = page.locator('#dynamicSlideoutPanel');
    await expect(panel).toHaveClass(/show/);
    const box = await panel.boundingBox();
    expect(Math.round(box!.width)).toBe(480);
    await expect(panel.locator('.slideout-panel-header h3')).toHaveCSS('font-size', '18px');
    await expect(panel.locator('.close-btn')).toHaveCSS('width', '32px');
    const footer = await panel.locator('.slideout-panel-footer').boundingBox();
    expect(footer!.y + footer!.height).toBeLessThanOrEqual(900);
    expect(await panel.locator('.slideout-panel-content').evaluate(el => el.scrollHeight > el.clientHeight)).toBeTruthy();
    await expect(page.locator('#fx-start')).toBeInViewport();

    await page.keyboard.press('Escape');
    await expect(panel).not.toHaveClass(/show/);

    // Opened again without a footer, the action row is gone.
    await page.evaluate(() => Slideout.open('Details', '<p>x</p>'));
    await expect(page.locator('#dynamicSlideoutPanel .slideout-panel-footer')).toBeHidden();
  });
});
