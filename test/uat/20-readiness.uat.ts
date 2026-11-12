import { test, expect, signInAdmin, apiJson, writeState, visit } from './guard';
/** Readiness r03 (upgraded data) and r04 (System Health), read-only. */
test('r03 r04', async ({ page }) => {
  await signInAdmin(page);
  const health: any = await apiJson(page, '/api/v1/monitoring/scheduler-health');
  const jobs: any[] = Array.isArray(health) ? health : (health.jobs || health.content || []);
  const envs: any[] = await apiJson(page, '/api/v1/environments?includeInactive=true');
  const users: any = await apiJson(page, '/api/v1/users?size=2000');
  const ulist: any[] = Array.isArray(users) ? users : users.content;
  const rules: any = await apiJson(page, '/api/v1/automation-rules');
  const grants: any = await apiJson(page, '/api/v1/access-grants?size=1').catch(() => null);
  const audit: any = await apiJson(page, '/api/v1/audit/logs?size=1&page=0');
  const oldest: any = audit.totalElements ? await apiJson(page, `/api/v1/audit/logs?size=1&page=${audit.totalElements - 1}`).catch(() => null) : null;
  let groups = 0, vms = 0;
  for (const e of envs.slice(0, 400)) { groups += e.groupCount || 0; vms += e.vmCount || e.totalVms || 0; }
  await visit(page, '#/system-health', 'r04-health');
  writeState('readiness', {
    jobs: jobs.map((j: any) => ({ name: j.name || j.jobName || j.key, state: j.status || j.state || j.health, enabled: j.enabled, last: j.lastRunAt || j.lastSuccessAt, err: j.lastError || j.error })),
    rawKeys: Object.keys(Array.isArray(health) ? (health[0] || {}) : health),
    envs: envs.length, active: envs.filter(e => e.isActive !== false && e.active !== false).length, groups, vms, users: ulist.length,
    rules: (Array.isArray(rules) ? rules : rules.content || []).length,
    grants: grants && (grants.totalElements ?? (Array.isArray(grants) ? grants.length : null)),
    auditTotal: audit.totalElements, oldestAudit: oldest && (oldest.content || [])[0] && (oldest.content[0].createdAt),
  });
  expect(jobs.length).toBeGreaterThan(0);
});
