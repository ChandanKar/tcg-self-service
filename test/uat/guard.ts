import { test as base, expect, type Page, type Request, type APIResponse } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Staging acceptance runs (not part of the normal suite). Safety first: every request that
 * changes something is refused unless it is on the allowlist below, and anything that starts,
 * stops, restarts or resizes machines is allowed only for the environment named in
 * UAT_ALLOWED_ENV ("ise"). A refused request fails the test that made it.
 */

export const ENV = {
  base: process.env.UAT_BASE_URL || '',
  adminUser: process.env.UAT_ADMIN_USER || '',
  adminPass: process.env.UAT_ADMIN_PASS || '',
  allowedEnvName: process.env.UAT_ALLOWED_ENV || 'ise',
};

export const STATE_DIR = path.resolve(__dirname, 'state');
export function readState<T = any>(name: string): T | null {
  const p = path.join(STATE_DIR, name + '.json');
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf-8')) : null;
}
export function writeState(name: string, value: unknown) {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(path.join(STATE_DIR, name + '.json'), JSON.stringify(value, null, 2));
}

/** The allowed environment's id, once preflight has found it. */
export function allowedEnvId(): string | null {
  const s = readState<{ ise: { environmentId: string } }>('preflight');
  return s?.ise?.environmentId ?? null;
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Requests that change nothing in AWS and nothing anyone else depends on. */
const ALWAYS_ALLOWED: RegExp[] = [
  /\/api\/auth\/login$/,
  /\/api\/auth\/logout$/,
  /\/logout$/,
  /\/api\/v1\/notifications\/[^/]+\/read$/,
  /\/api\/v1\/notifications\/read-all$/,
  /\/api\/v1\/client-errors$/,
];

/** Paths that act on machines or AWS: allowed only for the allowed environment. */
const ENV_SCOPED: RegExp[] = [
  /\/api\/v1\/environments\/([^/]+)\/operations(\/.*)?$/,
  /\/api\/v1\/environments\/([^/]+)\/lock\/(acquire|release|break|extend)$/,
  /\/api\/v1\/environments\/([^/]+)\/vms\/[^/]+$/,          // edit a VM (J14), never delete: see below
];

/** Never allowed, whatever the environment. */
const NEVER: RegExp[] = [
  /\/rightsizing\/.*apply/i,
  /\/waste\/.*\/(fix|action)/i,
  /\/spot\//i,
  /\/provisioning\//i,
  /\/cost-management\/tagging/i,
  /\/settings(\/|$)/i,                                       // no switch flips on staging
  /\/environments\/[^/]+$/,                                  // no environment edits or deletes
];

export type Extra = { allow?: RegExp[] };

function decide(req: Request, isePath: string | null, extra: RegExp[]): string | null {
  if (!MUTATING.has(req.method())) return null;
  const url = new URL(req.url());
  const p = url.pathname;
  if (!p.startsWith('/api') && !p.endsWith('/logout')) return null;   // form posts to the page itself
  if (ALWAYS_ALLOWED.some(r => r.test(p))) return null;
  if (NEVER.some(r => r.test(p))) return `never allowed: ${req.method()} ${p}`;
  for (const r of ENV_SCOPED) {
    const m = p.match(r);
    if (m) {
      if (req.method() === 'DELETE') return `no deletes of VMs: ${p}`;
      if (process.env.UAT_DRY === '1') return `dry run, not sent: ${req.method()} ${p}`;
      return isePath && m[1] === isePath ? null : `only ${ENV.allowedEnvName} may be acted on: ${req.method()} ${p}`;
    }
  }
  if (extra.some(r => r.test(p))) return null;
  return `not on the allowlist: ${req.method()} ${p}`;
}

type Fixtures = { guard: { refused: string[]; allow: (r: RegExp) => void } };

export const test = base.extend<Fixtures>({
  guard: [async ({ page }, use, testInfo) => {
    const refused: string[] = [];
    const extra: RegExp[] = [];
    const ise = allowedEnvId();
    await page.route('**/*', async route => {
      const why = decide(route.request(), ise, extra);
      if (why) {
        refused.push(why);
        await route.abort('blockedbyclient');
        return;
      }
      await route.continue();
    });
    await use({ refused, allow: r => { extra.push(r); } });
    if (refused.length) {
      await testInfo.attach('refused-requests', { body: refused.join('\n'), contentType: 'text/plain' });
    }
    expect(refused, 'requests the safety guard refused').toEqual([]);
  }, { auto: true }],
});
export { expect };

/** Signs in through the real password form. */
export async function signInAdmin(page: Page) {
  if (!ENV.adminUser || !ENV.adminPass) throw new Error('UAT_ADMIN_USER / UAT_ADMIN_PASS not set');
  await page.goto('/login');
  await page.locator('#username').fill(ENV.adminUser);
  await page.locator('#password').fill(ENV.adminPass);
  await page.locator('#submitBtn').click();
  await page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 20_000 });
}

/** A read-only API call with the page's session (GET only; writes go through the UI). */
export async function apiGet(page: Page, url: string): Promise<APIResponse> {
  return page.request.get(url);
}

export async function apiJson<T = any>(page: Page, url: string): Promise<T> {
  const r = await apiGet(page, url);
  expect(r.status(), `GET ${url}`).toBeLessThan(400);
  return r.json() as Promise<T>;
}

/** Records the verdict a scenario reached, picked up by the results writer. */
export function verdict(status: 'accepted' | 'rejected' | 'blocked' | 'untested', note: string) {
  test.info().annotations.push({ type: 'verdict', description: JSON.stringify({ status, note }) });
}

export type Visit = { serverErrors: string[]; pageErrors: string[]; failureText: string[] };

/**
 * Opens an app route by hash and waits for it to settle; reports 5xx answers, uncaught script
 * errors and on-page failure messages seen while it loaded, and saves a full-page screenshot.
 */
export async function visit(page: Page, hash: string, shot: string): Promise<Visit> {
  const serverErrors: string[] = [];
  const pageErrors: string[] = [];
  const onResp = (r: any) => { if (r.status() >= 500) serverErrors.push(`${r.status()} ${new URL(r.url()).pathname}`); };
  const onErr = (e: Error) => pageErrors.push(e.message);
  page.on('response', onResp);
  page.on('pageerror', onErr);
  await page.evaluate(h => { location.hash = h; }, hash);
  await page.waitForLoadState('networkidle', { timeout: 45_000 }).catch(() => {});
  await page.waitForTimeout(800);
  const failureText = await page.locator('#content-area').getByText(/failed to load|couldn.t load|something went wrong|error loading/i).allInnerTexts().catch(() => []);
  await page.screenshot({ path: test.info().outputPath(shot + '.png'), fullPage: true });
  page.off('response', onResp);
  page.off('pageerror', onErr);
  return { serverErrors, pageErrors, failureText };
}

export function clean(v: Visit): boolean {
  return !v.serverErrors.length && !v.pageErrors.length && !v.failureText.length;
}
export function describeVisit(v: Visit): string {
  const parts: string[] = [];
  if (v.serverErrors.length) parts.push('server errors: ' + v.serverErrors.join(', '));
  if (v.pageErrors.length) parts.push('script errors: ' + v.pageErrors.join(' | ').slice(0, 300));
  if (v.failureText.length) parts.push('on page: ' + v.failureText.join(' | ').slice(0, 200));
  return parts.join('; ');
}
