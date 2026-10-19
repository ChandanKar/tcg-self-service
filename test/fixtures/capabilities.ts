import type { Page } from '@playwright/test';

/**
 * Switch features on for this page only (U02-T01/T03). The app asks /api/v1/users/me/capabilities once
 * after sign-in and does not call a switched-off feature's API at all, so a spec that fakes a feature's
 * API must also say the feature is on. Call it before signing in.
 */
export async function withFeatures(page: Page, features: Record<string, boolean>): Promise<void> {
  await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
    const real = await route.fetch();
    const body = await real.json();
    body.features = { ...(body.features || {}), ...features };
    await route.fulfill({ response: real, json: body });
  });
}
