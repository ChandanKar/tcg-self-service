import type { APIRequestContext } from '@playwright/test';
import { devIds } from './auth';

/**
 * Seeds an environment through the API as the dev admin, so a spec never depends on data left by
 * other runs (the Java integration tests reset the shared test schema). Returns its id, or '' when
 * dev identities are not configured.
 */
export async function seedEnvironment(playwright: any, label: string): Promise<string> {
  if (!devIds.admin) return '';
  const admin: APIRequestContext = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
  const stamp = Date.now();
  const created = await admin.post('/api/v1/environments', {
    data: { name: `${label.toLowerCase().replace(/\W+/g, '-')}-${stamp}`, displayName: `${label} ${stamp}`, cloudProvider: 'AWS' },
  });
  const id = created.ok() ? (await created.json()).environmentId : '';
  await admin.dispose();
  return id;
}
