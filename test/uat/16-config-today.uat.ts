import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, apiJson, readState, visit, verdict } from './guard';

/**
 * Acceptance on configuration, 2026-11-12: L07 (schedules), L08 (budget), L11 (team), J14 (VM registry).
 * Everything is on ise (group-3 / worker2 where a VM is needed) and is removed or restored after:
 * the L07 rule is created switched off so it never fires, then deleted with its holiday calendar; the
 * L11 team is deleted and ise goes back to its team; J14 edits worker2 back and reactivates it.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const group = (name: string) => (readState<any>('preflight')!.iseVms as any[]).find(g => g.group.name === name);
const note = (type: string, v: unknown) => test.info().annotations.push({ type, description: typeof v === 'string' ? v : JSON.stringify(v) });

async function h(page: Page): Promise<Record<string, string>> {
  const c = (await page.context().cookies()).find(k => k.name === 'XSRF-TOKEN');
  return { 'Content-Type': 'application/json', ...(c ? { 'X-XSRF-TOKEN': decodeURIComponent(c.value) } : {}) };
}
const send = async (page: Page, method: 'post' | 'put' | 'patch' | 'delete', url: string, data?: unknown) => {
  const r = await (page.request as any)[method](url, { headers: await h(page), ...(data !== undefined ? { data } : {}) });
  let body: any = null; try { body = await r.json(); } catch { body = (await r.text()).slice(0, 200); }
  return { status: r.status() as number, body };
};

test.describe.configure({ timeout: 15 * 60_000 });

test('[L07] schedules: two windows, overlap refused, holiday, skip, suggestion', async ({ page }) => {
  await signInAdmin(page);
  const g3 = group('group-3');
  const base = { environmentId: iseId(), scopeType: 'GROUP', scopeId: g3.group.groupId, triggerType: 'SCHEDULE', timezone: 'Asia/Kolkata', enabled: false };
  const weekdays = ['MON', 'TUE', 'WED', 'THU', 'FRI'];
  const overlap = await send(page, 'post', '/api/v1/automation-rules', { ...base, name: 'UAT L07 overlap (Claude)',
    windows: [{ daysOfWeek: weekdays, startTime: '08:00', stopTime: '18:00' }, { daysOfWeek: ['FRI'], startTime: '17:00', stopTime: '20:00' }] });
  const cal = await send(page, 'post', '/api/v1/holiday-calendars', { name: 'UAT L07 holidays (Claude)', description: 'Acceptance; deleted after' });
  const holiday = '2026-11-16';
  const date = await send(page, 'post', `/api/v1/holiday-calendars/${cal.body.calendarId}/dates`, { date: holiday, label: 'UAT holiday' });
  const ruleReq = { ...base, name: 'UAT L07 rule (Claude, switched off)', holidayCalendarId: cal.body.calendarId, holidayBehavior: 'SKIP_ALL',
    windows: [{ daysOfWeek: weekdays, startTime: '08:00', stopTime: '18:00' }, { daysOfWeek: ['SAT'], startTime: '10:00', stopTime: '12:00' }] };
  const preview = await send(page, 'post', '/api/v1/automation-rules/preview', { ...ruleReq, enabled: true });
  const created = await send(page, 'post', '/api/v1/automation-rules', ruleReq);
  const ruleId = created.body && created.body.ruleId;
  let skip: any = null, nextAfter: any = null;
  if (ruleId) {
    const nextStop = new Date('2026-11-13T12:30:00Z');
    skip = await send(page, 'post', `/api/v1/automation-rules/${ruleId}/overrides`, { startsAt: new Date(nextStop.getTime() - 3600_000).toISOString(),
      endsAt: new Date(nextStop.getTime() + 3600_000).toISOString(), skipAction: 'STOP', reason: 'UAT L07: skip the next stop' });
    nextAfter = await apiJson(page, `/api/v1/automation-rules/${ruleId}/next-runs`).catch((e: any) => ({ error: String(e).slice(0, 120) }));
    await visit(page, '#/automation-rules', 'l07-rules');
  }
  const suggestion = await apiJson(page, `/api/v1/automation-rules/suggestion?environmentId=${iseId()}&timezone=Asia/Kolkata&weeks=4`).catch((e: any) => ({ error: String(e).slice(0, 120) }));
  const delRule = ruleId ? await send(page, 'delete', `/api/v1/automation-rules/${ruleId}`) : null;
  const delCal = cal.body && cal.body.calendarId ? await send(page, 'delete', `/api/v1/holiday-calendars/${cal.body.calendarId}`) : null;
  const runs = (preview.body && (preview.body.runs || preview.body.nextRuns || preview.body)) || [];
  const runDates = Array.isArray(runs) ? runs.map((r: any) => String(r.at || r.time || r.runAt || r).slice(0, 16)) : [];
  const onHoliday = Array.isArray(runs) ? runs.filter((r: any) => String(r.at).startsWith(holiday)) : [];
  note('l07', { overlap, cal: cal.status, date: date.status, created: created.status, onHoliday, nextAfter: JSON.stringify(nextAfter).slice(0, 700), skip: skip && skip.status,
    suggestion: JSON.stringify(suggestion).slice(0, 300), delRule: delRule && delRule.status, delCal: delCal && delCal.status });
  const overlapNamed = overlap.status === 400 && /fri/i.test(JSON.stringify(overlap.body));
  const holidaySkipped = runDates.length > 0 && onHoliday.every((r: any) => r.skipped === true);
  const ok = overlapNamed && created.status < 300 && holidaySkipped && skip && skip.status < 300;
  verdict(ok ? 'accepted' : 'rejected', `Overlapping windows (weekdays 08-18 and Friday 17-20) got ${overlap.status}: ${JSON.stringify(overlap.body).slice(0, 140)}. A rule with weekday and Saturday windows and a holiday calendar (16 Nov) was saved switched off; its preview ${holidaySkipped ? 'marks every run on 16 Nov skipped' : 'does not skip 16 Nov'} (${JSON.stringify(onHoliday.map((r: any) => [r.label, r.skipReason])).slice(0, 160)}). Skipping the next stop: ${skip && skip.status}. Suggest from usage: ${JSON.stringify(suggestion).slice(0, 160)}. Rule and calendar deleted (${delRule && delRule.status}, ${delCal && delCal.status}).`);
  expect(ok).toBeTruthy();
});

test('[L08] budget on ise with a low amount and a 50% threshold', async ({ page }) => {
  await signInAdmin(page);
  const existing = await page.request.get(`/api/v1/environments/${iseId()}/budget`);
  const had = existing.status() === 200 ? await existing.json().catch(() => null) : null;
  const created = had && had.budgetId ? { status: 200, body: had } : await send(page, 'post', '/api/v1/budgets', { scopeType: 'ENVIRONMENT', scopeId: iseId(), monthlyAmount: 50, thresholds: [50], forecastAlert: false, actionType: 'NONE', enabled: true });
  const now = await apiJson(page, `/api/v1/environments/${iseId()}/budget`).catch(() => null);
  await visit(page, '#/budgets', 'l08-budgets');
  await visit(page, '#/environments/' + iseId(), 'l08-ise');
  const card = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').match(/(\$[\d,.]+ of \$[\d,.]+[^.]{0,60}|budget[^.]{0,80})/i)?.[0] || '';
  note('l08', { created: created.status, budget: JSON.stringify(now).slice(0, 400), card });
  verdict('blocked', `A budget for ise of $50 a month with a 50% alert and no action was created (${created.status}); it shows spend against the amount (${card || JSON.stringify(now).slice(0, 120)}). The threshold alert comes from the daily evaluation: check the bell on 2026-11-13, then delete the budget.`);
  expect(created.status).toBeLessThan(300);
});

test('[L11] a team with members and ise; spend by team', async ({ page }) => {
  await signInAdmin(page);
  const overview: any = await apiJson(page, '/api/v1/teams/overview');
  const teams: any[] = Array.isArray(overview) ? overview : overview.teams || [];
  const oldTeam = teams.find(t => (t.environments || []).some((e: any) => (e.environmentId || e.id) === iseId()));
  const oldEnvs = oldTeam ? (oldTeam.environments || []).map((e: any) => e.environmentId || e.id) : [];
  const team = await send(page, 'post', '/api/v1/teams', { name: 'uat-team-claude', displayName: 'UAT team (Claude)', costCenter: 'UAT' });
  const teamId = team.body.teamId;
  const users: any = await apiJson(page, '/api/v1/users?size=1000');
  const op = (Array.isArray(users) ? users : users.content).find((u: any) => u.email === process.env.UAT_OPERATOR_USER);
  const member = await send(page, 'post', `/api/v1/teams/${teamId}/members`, { userId: op.userId });
  const assign = await send(page, 'put', `/api/v1/teams/${teamId}/environments`, { environmentIds: [iseId()] });
  const env = await apiJson(page, `/api/v1/environments/${iseId()}`);
  const trend = await apiJson(page, '/api/v1/cost-management/spend-trend-by-team?days=30').catch((e: any) => ({ error: String(e).slice(0, 100) }));
  await visit(page, '#/teams', 'l11-teams');
  await visit(page, '#/cost-management', 'l11-cost');
  const trendText = JSON.stringify(trend);
  const restore = oldTeam ? await send(page, 'put', `/api/v1/teams/${oldTeam.teamId}/environments`, { environmentIds: oldEnvs }) : null;
  const del = await send(page, 'delete', `/api/v1/teams/${teamId}`);
  const envAfter = await apiJson(page, `/api/v1/environments/${iseId()}`);
  note('l11', { oldTeam: oldTeam && oldTeam.displayName, team: team.status, member: member.status, assign: assign.status, envTeam: env.teamName || env.team, trendHasTeam: /UAT team/.test(trendText), trend: trendText.slice(0, 300), restore: restore && restore.status, del: del.status, envTeamAfter: envAfter.teamName || envAfter.team });
  const ok = team.status < 300 && member.status < 300 && assign.status < 300 && /UAT team/.test(JSON.stringify(env)) && (!oldTeam || (restore && restore.status < 300));
  verdict(ok ? 'accepted' : 'rejected', `Created 'UAT team (Claude)', added UAT Operator, assigned ise (${assign.status}); ise then showed its team as ${JSON.stringify(env.teamName || env.team)}; spend by team ${/UAT team/.test(trendText) ? 'includes it' : `answered ${trendText.slice(0, 120)}`}. Afterwards ise went back to ${oldTeam ? oldTeam.displayName : 'no team'} (${restore && restore.status}) and the test team was deleted (${del.status}).`);
  expect(ok).toBeTruthy();
});

test('[J14] VM registry: edit, remove (history kept) and restore worker2', async ({ page }) => {
  await signInAdmin(page);
  const g3 = group('group-3');
  const vm = g3.vms[0];
  const base = `/api/v1/environments/${iseId()}/vms/${vm.vmId}`;
  const before = await apiJson(page, base);
  const dup = await send(page, 'post', `/api/v1/environments/${iseId()}/vms`, { groupId: g3.group.groupId, name: vm.name + '-dup', displayName: 'dup', provider: 'AWS',
    region: before.region, instanceType: before.instanceType, providerVmId: before.providerVmId, sequencePosition: 2 });
  const edit = await send(page, 'put', base, { ...before, displayName: 'UAT J14 worker2 (edited)' });
  const afterEdit = await apiJson(page, base);
  const revert = await send(page, 'put', base, { ...before });
  const removed = await send(page, 'delete', base);
  const groups: any[] = await apiJson(page, `/api/v1/environments/${iseId()}/vms`);
  const listed = groups.flatMap(g => g.vms).some((v: any) => v.vmId === vm.vmId);
  const history: any = await apiJson(page, `/api/v1/audit/logs?size=100&environmentId=${iseId()}`);
  const historyRows = (history.content || []).filter((a: any) => String(a.targetId || '').includes(vm.vmId) || String(a.details || '').includes(vm.name)).length;
  const back = await send(page, 'post', `${base}/reactivate`);
  const groups2: any[] = await apiJson(page, `/api/v1/environments/${iseId()}/vms`);
  const restored = groups2.flatMap(g => g.vms).find((v: any) => v.vmId === vm.vmId);
  await visit(page, '#/vm-registry', 'j14-registry');
  note('j14', { dup: dup.status, dupBody: JSON.stringify(dup.body).slice(0, 160), edit: edit.status, editedName: afterEdit.displayName, revert: revert.status, removed: removed.status, listedAfterRemove: listed, historyRows, back: back.status, restored: restored && { name: restored.displayName || restored.name, group: g3.group.name, status: restored.status } });
  if (dup.status < 300 && dup.body && dup.body.vmId) await send(page, 'delete', `/api/v1/environments/${iseId()}/vms/${dup.body.vmId}`);
  const ok = edit.status < 300 && afterEdit.displayName === 'UAT J14 worker2 (edited)' && revert.status < 300 && removed.status < 300 && !listed && historyRows > 0 && back.status < 300 && !!restored;
  verdict(ok ? 'accepted' : 'rejected', `worker2 (i-076bbf4c25618c4d3) in ise group-3: adding the same instance again got ${dup.status} (${JSON.stringify(dup.body).slice(0, 100)}); editing its name stuck and was reverted; removing it (${removed.status}) hid it from ise while its ${historyRows} audit rows stayed; reactivating (${back.status}) brought it back to group-3. No spare EC2 instance exists to add a new one (the test VM allowlist is ise's three).`);
  expect(ok).toBeTruthy();
});
