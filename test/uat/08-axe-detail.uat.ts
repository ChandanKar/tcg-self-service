import AxeBuilder from '@axe-core/playwright';
import { test, expect, signInAdmin, readState, visit, writeState } from './guard';

/** Read-only: which elements the serious accessibility findings point at. */
test('axe details', async ({ page, guard }) => {
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId;
  const out: any[] = [];
  for (const [hash, name] of [['#/dashboard', 'dashboard'], ['#/environments/' + ise, 'ise'], ['#/cost-management', 'cost']]) {
    await visit(page, hash, 'axe-detail-' + name);
    const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    r.violations.filter(v => v.impact === 'serious' || v.impact === 'critical').forEach(v =>
      v.nodes.forEach(n => out.push({ page: name, rule: v.id, target: n.target.join(' '), html: n.html.slice(0, 220), why: n.failureSummary?.slice(0, 200) })));
  }
  writeState('axe-detail', out);
  expect(guard.refused).toEqual([]);
});
