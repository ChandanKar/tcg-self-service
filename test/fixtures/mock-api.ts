import type { Page, Route } from '@playwright/test';

/** Delays matching requests by `ms` before letting them through (simulates a slow API). */
export async function delayRoute(page: Page, urlGlob: string, ms: number): Promise<void> {
  await page.route(urlGlob, async (route: Route) => {
    await new Promise((resolve) => setTimeout(resolve, ms));
    await route.continue();
  });
}

/** Answers matching requests with a JSON body instead of calling the server. */
export async function fulfillJson(page: Page, urlGlob: string, body: unknown, status = 200): Promise<void> {
  await page.route(urlGlob, (route: Route) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }));
}

/** Counts requests to matching URLs from now on; read the count with `get()`. */
export function countRequests(page: Page, urlGlob: string | RegExp): { get(): number } {
  let count = 0;
  const matches = (url: string) =>
    typeof urlGlob === 'string' ? url.includes(urlGlob.replace(/\*/g, '')) : urlGlob.test(url);
  page.on('request', (request) => {
    if (matches(request.url())) count++;
  });
  return { get: () => count };
}

/** A Spring Data page body ({content, page}) as /environments/page returns it. */
export function pageBody<T>(content: T[], totalElements = content.length, number = 0, size = 15) {
  return { content, page: { size, number, totalElements, totalPages: Math.ceil(totalElements / size) } };
}

/**
 * Fakes GET /environments/page (U06-T04/T05): `respond` gets the request URL and returns the page
 * body plus the X-Total-Vms / X-Running-Vms totals. Returns the URLs seen, newest last.
 */
export async function fakeEnvironmentPage(
  page: Page,
  respond: (url: URL) => { body: unknown; vms?: number; running?: number },
): Promise<URL[]> {
  const seen: URL[] = [];
  await page.route(/\/api\/v1\/environments\/page(\?.*)?$/, (route: Route) => {
    const url = new URL(route.request().url());
    seen.push(url);
    const { body, vms = 0, running = 0 } = respond(url);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body),
      headers: { 'X-Total-Vms': String(vms), 'X-Running-Vms': String(running) } });
  });
  return seen;
}

/** Per-group state counts (U05-T03) for a faked list row or Home card. */
export function groupStates(name: string, sequencePosition: number,
  s: Partial<Record<'running' | 'busy' | 'stopped' | 'problem' | 'unknown', number>>) {
  const counts = { running: 0, busy: 0, stopped: 0, problem: 0, unknown: 0, ...s };
  return { groupId: `g-${name}-${sequencePosition}`, name, sequencePosition,
    total: Object.values(counts).reduce((a, b) => a + b, 0), ...counts };
}
