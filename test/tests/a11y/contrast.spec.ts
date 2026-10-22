import { test, expect, type Page } from '@playwright/test';
import { canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';
import { seriousViolations } from '../../fixtures/axe';

/**
 * U17-T06 (was E13-T04's contrast check on four pages): axe with the WCAG 2.2 AA rules on every
 * route each persona can open, the environment page, the side panels (My account and each of its
 * tabs, Ask), the notifications, the shortcuts dialog and the start confirm; and the everyday
 * routes again at 390 x 844. No serious or critical violation may remain.
 */

declare const ContentRouter: any;
declare const AssistantPanel: any;
declare const OperationConfirm: any;
declare const Keyboard: any;
declare const MyAccount: any;

let seededEnvId = '';
test.beforeAll(async ({ playwright }) => {
    // Never depend on environments left by other runs (E13 a11y specs).
    seededEnvId = await seedEnvironment(playwright, 'Contrast E2E', { grantUser: true });
});

async function signInAs(page: Page, persona: string) {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': persona });
    await page.goto('/home');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#content-area h1, #content-area .no-access').first()).toBeVisible({ timeout: 15_000 });
}

/** Through Help (no request), so the same page as before still reloads. */
async function visit(page: Page, hash: string) {
    await page.evaluate(() => { location.hash = '#/help'; });
    await expect(page.locator('#help-search')).toBeVisible();
    await page.evaluate(h => { location.hash = h; }, hash);
    await page.waitForLoadState('networkidle');
    // Entrance transitions settle so axe reads final colours; delayed outlines are past 300 ms.
    await page.waitForTimeout(700);
}

async function routes(page: Page): Promise<string[]> {
    const hashes: string[] = await page.evaluate(() => Array.from(new Set(ContentRouter._routeKeys()
        .map((k: string) => { try { return ContentRouter.hashFor(k); } catch (e) { return null; } })
        .filter(Boolean))));
    return hashes.concat(seededEnvId ? ['#/environments/' + seededEnvId] : []);
}

test.describe('Axe on every screen (WCAG 2.2 AA)', () => {
    test.describe.configure({ timeout: 360_000 });

    for (const persona of ['admin-001', 'dev-user-001', 'user-001', 'user-002']) {
        test(`${persona}: every route, panel and dialog`, async ({ page }) => {
            test.skip(!devIds.admin || !canSignIn('admin'), 'Needs the dev X-User-Id header (TEST_DEV_ADMIN_ID)');
            await signInAs(page, persona);
            const found: string[] = [];
            for (const hash of await routes(page)) {
                await visit(page, hash);
                (await seriousViolations(page)).forEach(v => found.push(`${hash} ${v}`));
            }

            // My account, each tab.
            await visit(page, '#/dashboard');
            await page.evaluate(() => MyAccount.open());
            await expect(page.locator('.my-account').first()).toBeVisible({ timeout: 10_000 });
            for (const tab of await page.locator('#dynamicSlideoutPanel [role="tab"]').all()) {
                await tab.click();
                await page.waitForTimeout(500);
                (await seriousViolations(page, '#dynamicSlideoutPanel')).forEach(v => found.push(`My account ${v}`));
            }
            await page.keyboard.press('Escape');

            // The notifications.
            await page.locator('#notification-bell-btn').click();
            await expect(page.locator('#notification-dropdown')).toBeVisible();
            (await seriousViolations(page, '#notification-dropdown')).forEach(v => found.push(`Notifications ${v}`));
            await page.keyboard.press('Escape');

            // Ask.
            await page.evaluate(() => AssistantPanel.open());
            await expect(page.locator('#assistant-question')).toBeVisible();
            (await seriousViolations(page, '#dynamicSlideoutPanel')).forEach(v => found.push(`Ask ${v}`));
            await page.keyboard.press('Escape');

            // The shortcuts dialog.
            const shortcuts = await page.evaluate(() => typeof Keyboard !== 'undefined' && typeof Keyboard.showShortcutsHelp === 'function'
                ? (Keyboard.showShortcutsHelp(), true) : false);
            if (shortcuts) {
                await expect(page.locator('.modal.show')).toBeVisible();
                (await seriousViolations(page, '.modal.show')).forEach(v => found.push(`Shortcuts ${v}`));
                await page.keyboard.press('Escape');
                await expect(page.locator('.modal.show')).toHaveCount(0);
            }

            // The start confirm.
            if (seededEnvId) {
                await page.evaluate(id => OperationConfirm.open({ env: { environmentId: id, displayName: 'Contrast E2E', groups: [] },
                    opType: 'START', scope: { level: 'environment' }, onConfirm: () => Promise.resolve() }), seededEnvId);
                const confirm = page.locator('.modal.show');
                if (await confirm.isVisible({ timeout: 5_000 }).catch(() => false)) {
                    (await seriousViolations(page, '.modal.show')).forEach(v => found.push(`Start confirm ${v}`));
                    await page.keyboard.press('Escape');
                }
            }
            expect(found, found.join('\n')).toEqual([]);
        });
    }

    test('user-001 at 390 x 844: the everyday routes', async ({ page }) => {
        test.skip(!devIds.admin || !canSignIn('admin'), 'Needs the dev X-User-Id header (TEST_DEV_ADMIN_ID)');
        await page.setViewportSize({ width: 390, height: 844 });
        await signInAs(page, 'user-001');
        const found: string[] = [];
        for (const hash of ['#/dashboard', '#/my-environments', '#/request-access', '#/activity-logs', '#/approvals', '#/help',
            ...(seededEnvId ? ['#/environments/' + seededEnvId] : [])]) {
            await visit(page, hash);
            (await seriousViolations(page)).forEach(v => found.push(`${hash} ${v}`));
        }
        expect(found, found.join('\n')).toEqual([]);
    });
});
