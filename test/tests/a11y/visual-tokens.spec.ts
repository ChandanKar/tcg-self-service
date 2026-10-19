import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U03-T01: the app uses the approved identity: IBM Plex Sans for the interface and IBM Plex Mono for
 * data, both self-hosted (never Google Fonts), and the brand navy for the primary colour.
 * U03-T02: buttons, inputs, status chips, tables and dialogs take the style guide's values.
 */

declare const ContentRouter: any;
declare const Modals: any;
declare const DestructiveConfirm: any;

test.describe('Visual identity tokens', () => {
  test('the interface renders in self-hosted IBM Plex with the brand colours', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    const external: string[] = [];
    page.on('request', r => { if (/fonts\.(googleapis|gstatic)\.com/.test(r.url())) external.push(r.url()); });

    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => document.fonts.ready);

    const fonts = await page.evaluate(async () => {
      await document.fonts.load('14px "IBM Plex Sans"');
      await document.fonts.load('14px "IBM Plex Mono"');
      const code = document.createElement('code');
      document.body.appendChild(code);
      const codeFont = getComputedStyle(code).fontFamily;
      code.remove();
      return {
        codeFont,
        sans: document.fonts.check('14px "IBM Plex Sans"'),
        mono: document.fonts.check('14px "IBM Plex Mono"'),
        body: getComputedStyle(document.body).fontFamily,
        navy: getComputedStyle(document.documentElement).getPropertyValue('--brand-navy').trim(),
        primary: getComputedStyle(document.documentElement).getPropertyValue('--bs-primary').trim(),
      };
    });
    expect(fonts.sans).toBeTruthy();
    expect(fonts.mono).toBeTruthy();
    expect(fonts.body).toMatch(/^"?IBM Plex Sans/);
    expect(fonts.codeFont).toMatch(/^"?IBM Plex Mono/);
    expect(fonts.navy.toLowerCase()).toBe('#14245c');
    expect(fonts.primary.toLowerCase()).toBe('#14245c');
    expect(external).toEqual([]);
  });

  test('buttons, chips, tables and dialogs follow the style guide', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof Modals !== 'undefined');
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });

    const looks = await page.evaluate(() => {
      const box = document.createElement('div');
      box.id = 'u03-sample';
      box.innerHTML = `
        <button class="btn btn-primary" data-k="primary">Primary</button>
        <button class="btn btn-success" data-k="start">Start</button>
        <button class="btn btn-secondary" data-k="secondary">Secondary</button>
        <button class="btn btn-outline-danger" data-k="stop">Stop</button>
        <button class="btn btn-danger btn-sm" data-k="stopSm">Stop</button>
        <input class="form-control" data-k="input">
        <span class="status-badge running" data-k="running">Running</span>
        <span class="status-badge stopped" data-k="stopped">Stopped</span>
        <span class="status-badge starting" data-k="starting">Starting</span>
        <span class="status-badge error" data-k="failed">Failed</span>
        <table class="table"><thead><tr><th data-k="th">Name</th></tr></thead><tbody><tr><td data-k="td">web-01</td></tr></tbody></table>`;
      document.querySelector('#content-area')!.appendChild(box);
      const out: Record<string, any> = {};
      box.querySelectorAll<HTMLElement>('[data-k]').forEach(el => {
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        out[el.dataset.k!] = { bg: cs.backgroundColor, color: cs.color, border: cs.borderTopColor,
          radius: cs.borderTopLeftRadius, height: Math.round(r.height), size: cs.fontSize, weight: cs.fontWeight };
      });
      box.remove();
      return out;
    });
    expect(looks.primary).toMatchObject({ bg: 'rgb(20, 36, 92)', height: 38, radius: '8px', size: '14px', weight: '500' });
    expect(looks.start).toMatchObject({ bg: 'rgb(14, 134, 86)', color: 'rgb(255, 255, 255)', height: 38 });
    expect(looks.secondary).toMatchObject({ bg: 'rgb(255, 255, 255)', color: 'rgb(20, 36, 92)', border: 'rgb(185, 194, 218)' });
    expect(looks.stop).toMatchObject({ bg: 'rgb(255, 255, 255)', color: 'rgb(168, 51, 31)', border: 'rgb(232, 184, 174)' });
    expect(looks.stopSm).toMatchObject({ color: 'rgb(168, 51, 31)', height: 30 });
    expect(looks.input).toMatchObject({ height: 36, radius: '8px' });
    expect(looks.running).toMatchObject({ bg: 'rgb(227, 244, 236)', color: 'rgb(11, 107, 68)', size: '12px', radius: '999px' });
    expect(looks.stopped).toMatchObject({ bg: 'rgb(236, 239, 245)', color: 'rgb(74, 83, 104)' });
    expect(looks.starting).toMatchObject({ bg: 'rgb(251, 243, 220)', color: 'rgb(122, 80, 0)' });
    expect(looks.failed).toMatchObject({ bg: 'rgb(251, 238, 235)', color: 'rgb(168, 51, 31)' });
    expect(looks.th).toMatchObject({ bg: 'rgb(247, 248, 251)', color: 'rgb(91, 100, 120)', size: '12.5px', weight: '500' });
    expect(looks.td.height).toBeGreaterThanOrEqual(38);

    await page.evaluate(() => Modals.confirm('Remove the rule?', 'The rule stops running.', () => {}));
    const dialog = page.locator('#confirmModal');
    await expect(dialog).toBeVisible();
    const modal = await page.evaluate(() => {
      const content = document.querySelector('#confirmModal .modal-content')!;
      const title = document.querySelector('#confirmModal .modal-title')!;
      const backdrop = document.querySelector('.modal-backdrop')!;
      return { radius: getComputedStyle(content).borderTopLeftRadius, titleSize: getComputedStyle(title).fontSize,
        titleWeight: getComputedStyle(title).fontWeight, backdrop: getComputedStyle(backdrop).backgroundColor,
        backdropOpacity: getComputedStyle(backdrop).opacity };
    });
    expect(modal).toMatchObject({ radius: '14px', titleSize: '18px', titleWeight: '600', backdrop: 'rgb(10, 20, 48)' });
    await expect.poll(async () => page.evaluate(() => getComputedStyle(document.querySelector('.modal-backdrop')!).opacity))
      .toBe('0.42');
  });

  test('the destructive confirm dialog has a plain header and a Stop-style confirm button', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof DestructiveConfirm !== 'undefined');
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => DestructiveConfirm.show({ title: 'Delete budget', message: 'This removes the budget.',
      impact: ['Alerts stop'], confirmText: 'DELETE', actionText: 'Delete budget', onConfirm: () => {} }));
    await expect(page.locator('#destructiveConfirmModal')).toBeVisible();
    await page.locator('#confirm-text-input').fill('DELETE');
    await expect(page.locator('#btn-destructive-confirm')).toBeEnabled();
    await expect(page.locator('#destructiveConfirmModal .modal-header')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
    const confirm = page.locator('#btn-destructive-confirm');
    await expect(confirm).toHaveCSS('color', 'rgb(168, 51, 31)');
    await expect(confirm).toHaveCSS('background-color', 'rgb(255, 255, 255)');
    await expect(confirm).toHaveCSS('border-top-color', 'rgb(232, 184, 174)');
  });

  test('the sign-in page loads the font without signing in', async ({ request }) => {
    const font = await request.get('/webfonts/ibm-plex-sans-latin-400-normal.woff2', { maxRedirects: 0 });
    expect(font.status()).toBe(200);
  });
});
