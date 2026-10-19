import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U03-T01: the app uses the approved identity: IBM Plex Sans for the interface and IBM Plex Mono for
 * data, both self-hosted (never Google Fonts), and the brand navy for the primary colour.
 */

declare const ContentRouter: any;

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

  test('the sign-in page loads the font without signing in', async ({ request }) => {
    const font = await request.get('/webfonts/ibm-plex-sans-latin-400-normal.woff2', { maxRedirects: 0 });
    expect(font.status()).toBe(200);
  });
});
