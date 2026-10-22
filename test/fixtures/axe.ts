import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

/**
 * U17-T06: one axe run for the a11y specs. WCAG 2.2 AA tags; only serious and critical findings
 * fail a test (moderate ones are reported by the uat scan). EXCEPTIONS lists markup we do not own,
 * each with its reason.
 */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

export const EXCEPTIONS: { rule: string; target: RegExp; why: string }[] = [
    // ECharts draws on a canvas with its own generated wrapper; the chart's data is in the table beside it.
    { rule: 'aria-allowed-attr', target: /_echarts_instance_/, why: 'ECharts wrapper markup' },
];

/** "rule: target — help" for every serious or critical violation in `include` (default: the page). */
export async function seriousViolations(page: Page, include?: string): Promise<string[]> {
    let builder = new AxeBuilder({ page }).withTags(WCAG_TAGS);
    if (include) builder = builder.include(include);
    const result = await builder.analyze();
    return result.violations
        .filter(v => v.impact === 'serious' || v.impact === 'critical')
        .flatMap(v => v.nodes
            .filter(n => !EXCEPTIONS.some(e => e.rule === v.id && e.target.test(n.target.join(' '))))
            .map(n => `${v.id}: ${n.target.join(' ')} — ${v.help}`));
}
