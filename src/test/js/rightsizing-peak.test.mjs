// Run with: node --test "src/test/js/**/*.test.mjs"
// U26-T05: the rightsizing table says what its CPU peak is: the busiest minute, or 5-minute averages
// on days collected before the busiest minute was kept.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/cost-management.js', 'utf8') + '\nthis.C = CostManagement;', context);
const M = context.C.model;

test('a peak from CloudWatch maximums reads as the busiest minute, not an average', () => {
    const basis = M.cpuPeakBasis({ peakCpuUtilization: 68.45, reasons: ['CPU_LOW', 'MEMORY_LOW'] });
    assert.equal(basis.key, 'maximum');
    assert.equal(basis.label, 'CPU: busiest minute');
    assert.match(basis.title, /not an average/);
});

test('a peak resting on 5-minute averages says so and that the real peak may be higher', () => {
    const basis = M.cpuPeakBasis({ peakCpuUtilization: 32.05, reasons: ['AWS_OVER_PROVISIONED', 'CPU_PEAK_FROM_AVERAGES'] });
    assert.equal(basis.key, 'average');
    assert.equal(basis.label, 'CPU: 5-minute averages');
    assert.match(basis.title, /real peak may be higher/);
});

test('no CPU peak, no label', () => {
    assert.equal(M.cpuPeakBasis({ peakCpuUtilization: null, reasons: ['CPU_PEAK_FROM_AVERAGES'] }), null);
    assert.equal(M.cpuPeakBasis(null), null);
    assert.equal(M.cpuPeakBasis({ peakCpuUtilization: 0 }).key, 'maximum');
});
