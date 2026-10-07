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
