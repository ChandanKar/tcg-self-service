import type { APIRequestContext } from '@playwright/test';

/**
 * U18-T01: staging-like volume for the fit checks. Staging has 162 active environments; the test
 * schema has a handful plus what specs create. ensureVolume() makes sure at least `count` "Fit volume"
 * environments exist, each with Database / Application / Web groups of two VMs (records only, no AWS
 * calls), so lists reach their page size and pages are checked full. Safe to call again: existing
 * ones are counted by name and only the missing ones are made.
 */
const PREFIX = 'fit-volume-';

export async function ensureVolume(playwright: any, adminId: string, count = 30): Promise<number> {
  const admin: APIRequestContext = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': adminId, 'Content-Type': 'application/json' },
  });
  try {
    const list = await (await admin.get('/api/v1/environments')).json();
    const have = new Set((Array.isArray(list) ? list : []).map((e: any) => e.name).filter((n: string) => n && n.startsWith(PREFIX)));
    const kinds = ['Payments', 'CRM', 'Analytics', 'Billing', 'HR Portal', 'Data Lake', 'Search', 'Mobile API', 'Partner Portal', 'Claims'];
    const stages = ['Dev', 'QA', 'UAT', 'Staging'];
    let made = 0;
    for (let i = 0; i < count; i++) {
      const name = `${PREFIX}${String(i + 1).padStart(2, '0')}`;
      if (have.has(name)) continue;
      const created = await admin.post('/api/v1/environments', { data: {
        name, displayName: `${kinds[i % kinds.length]} ${stages[Math.floor(i / kinds.length) % stages.length]} ${i + 1}`,
        cloudProvider: 'AWS', description: 'Fit-check volume' } });
      if (!created.ok()) continue;
      const envId = (await created.json()).environmentId;
      for (const [g, display, seq] of [['database', 'Database', 1], ['app', 'Application', 2], ['web', 'Web', 3]] as const) {
        const group = await (await admin.post(`/api/v1/environments/${envId}/groups`, { data: { name: g, displayName: display, sequencePosition: seq } })).json();
        if (!group.groupId) continue;
        for (let v = 1; v <= 2; v++) {
          await admin.post(`/api/v1/environments/${envId}/vms`, { data: {
            groupId: group.groupId, name: `${name}-${g}-0${v}`, displayName: `${display} ${v}`, provider: 'AWS',
            region: 'ap-south-1', providerVmId: `i-0${Math.random().toString(16).slice(2, 15)}`, sequencePosition: v } });
        }
      }
      made++;
    }
    return made;
  } finally {
    await admin.dispose();
  }
}
