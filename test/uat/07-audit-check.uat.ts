import { test, expect, signInAdmin, apiJson, readState, writeState } from './guard';

/** Read-only: who did what on ise, with the browser each action came from. */
test('ise audit trail', async ({ page, guard }) => {
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId;
  const logs = await apiJson(page, `/api/v1/audit/logs/environment/${ise}?page=0&size=30`);
  const rows = (logs.content || logs).map((l: any) => ({
    at: l.timestamp || l.performedAt || l.createdAt, action: l.action, target: l.targetName, user: l.actorName || l.userEmail,
    ip: l.ipAddress, agent: (l.userAgent || '').slice(0, 90), details: (l.details || '').slice(0, 120),
  }));
  writeState('ise-audit', rows);
  expect(guard.refused).toEqual([]);
});
