import { test, signInAdmin, apiJson, writeState } from './guard';
test('r03 counts', async ({ page }) => {
  await signInAdmin(page);
  const a: any = await apiJson(page, '/api/v1/audit/logs?size=1&from=2020-01-01T00:00:00Z');
  const pages = a.totalPages ?? a.page?.totalPages; const total = a.totalElements ?? a.page?.totalElements;
  const last: any = total ? await apiJson(page, `/api/v1/audit/logs?size=1&from=2020-01-01T00:00:00Z&page=${total - 1}`) : null;
  const g: any = await apiJson(page, '/api/v1/access-grants?size=1').catch((e: any) => String(e).slice(0, 80));
  const envAccess: any = await apiJson(page, '/api/v1/access-grants/summary').catch(() => null);
  const ise: any = await apiJson(page, '/api/v1/environments').then((l: any[]) => l.find(e => e.name === 'ise'));
  const iseAccess: any[] = await apiJson(page, `/api/v1/environments/${ise.environmentId}/access`);
  writeState('r03', { auditTotal: total, auditPages: pages, oldest: last && (last.content || [])[0]?.createdAt, newest: (a.content || [])[0]?.createdAt,
    grants: typeof g === 'string' ? g : (g.totalElements ?? g.page?.totalElements ?? (Array.isArray(g) ? g.length : Object.keys(g))), summary: envAccess, iseAccess: iseAccess.length });
});
