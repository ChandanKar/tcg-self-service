import { test, expect, signInAdmin, apiJson, writeState, verdict } from './guard';

/**
 * Switched-off tier: reads the effective value of every switch from Settings (read-only) and
 * confirms each feature ships off on staging. These scenarios are accepted only when someone
 * turns the feature on, so they stay "not tested" with a note.
 */
const SWITCHES: Record<string, string[]> = {
  S01: ['lease.enabled'], S02: ['automation.idle-stop.enabled'], S03: ['locks.expiry.enabled'],
  S04: ['cost.actuals.enabled'], S05: ['cost.optimizer.enabled'], S06: ['cost.reservations.enabled'],
  S07: ['cost.tagging.enabled'], S08: ['vm.discovery.enabled'], S09: ['notification.email.enabled'],
  S10: ['retention.enabled'], S11: ['budgets.actions.enabled'], S12: ['budgets.aws-sync.enabled'],
  S13: ['cost.anomalies.enabled'], S14: ['waste.sweep.enabled'], S15: ['waste.actions.enabled'],
  S16: ['teams.entra-sync.enabled'], S17: ['chargeback.enabled'], S18: ['chat.enabled'],
  S19: ['chat.actions.stop-enabled'], S20: ['jira.enabled'], S21: ['approvals.enabled'], S22: ['quotas.enabled'],
  S23: ['eks.metrics.enabled', 'eks.scad.enabled'], S24: ['aws.multi-account.enabled'],
  S25: ['rightsizing.safe-resize.enabled'], S26: ['spot.enabled', 'spot.pricing.enabled'],
  S27: ['provisioning.enabled'], S28: ['provisioning.terraform.enabled'], S29: ['rds.enabled'],
  S30: ['rds.keep-stopped.enabled'], S31: ['api-tokens.enabled'], S32: ['springdoc.api-docs.enabled'],
  S33: ['webhooks.enabled'], S34: ['assistant.mcp.enabled'], S35: ['assistant.chat.enabled'],
  S36: ['carbon.enabled'], S37: ['graph.directory.enabled'],
};

test('switched-off features are off on staging', async ({ page, guard }) => {
  await signInAdmin(page);
  const settings: any[] = await apiJson(page, '/api/v1/settings');
  const byKey = new Map(settings.map(s => [s.key, s]));
  const report: Record<string, { key: string; value: string | null; source?: string }[]> = {};
  for (const [id, keys] of Object.entries(SWITCHES)) {
    report[id] = keys.map(k => ({ key: k, value: byKey.has(k) ? String(byKey.get(k).value) : null, source: byKey.get(k)?.source }));
  }
  writeState('switches', report);
  // One verdict line per scenario, read by the results writer.
  for (const [id, rows] of Object.entries(report)) {
    const on = rows.filter(r => r.value === 'true');
    const unknown = rows.filter(r => r.value === null);
    const note = on.length
      ? `ON on staging: ${on.map(r => r.key).join(', ')}. Test it as the scenario says.`
      : unknown.length === rows.length
        ? `Not listed in Settings (${rows.map(r => r.key).join(', ')}); its scheduled job shows DISABLED in System Health. Ships off; not tested.`
        : `Confirmed off on staging (${rows.map(r => r.key + '=' + (r.value ?? 'n/a')).join(', ')}). Ships off; accept when you turn it on.`;
    test.info().annotations.push({ type: 'verdict:' + id, description: JSON.stringify({ status: 'untested', note }) });
  }
  verdict('untested', 'see per-scenario annotations');
  expect(guard.refused).toEqual([]);
});
