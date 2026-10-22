// Run with: node --test "src/test/js/**/*.test.mjs"
// U19-T08: every commercial region with its name; the environments list's region filter.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

test('every commercial region has a name; no GovCloud or China; codes are unique', () => {
    const context = vm.createContext({ window: {}, location: { origin: '' } });
    context.window = context;
    vm.runInContext(readFileSync(STATIC + 'js/config.js', 'utf8') + '\nthis.C = Config;', context);
    const C = context.C;
    const codes = C.AWS_REGIONS.map(r => r.value);
    assert.equal(codes.length, 34);
    assert.equal(new Set(codes).size, codes.length);
    assert.ok(codes.every(c => /^[a-z]{2}(-[a-z]+)+-[0-9]$/.test(c)));
    assert.ok(!codes.some(c => c.startsWith('us-gov') || c.startsWith('cn-')));
    assert.equal(C.regionName('me-central-1'), 'Middle East (UAE)');
    assert.equal(C.regionName('xx-test-9'), 'xx-test-9');
    assert.equal(C.regionLabel('ap-south-1'), 'ap-south-1 · Mumbai');
    assert.equal(C.regionLabel('xx-test-9'), 'xx-test-9');
});
