// Run with: node --test "src/test/js/**/*.test.mjs"
// U03-T03: the power strip draws one light per VM, groups in start order, a bar for big compact
// strips, and names itself in words.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/ui/power-strip.js', 'utf8') + '\nthis.PowerStrip = PowerStrip;', context);
    return context.PowerStrip;
}

const strip = (states, opts) => load().render(
    states.split('|').map((s, i) => ({ name: 'g' + i, lights: s.split('') })), opts);
const count = (html, re) => (html.match(re) || []).length;
const ariaLabel = html => /aria-label="([^"]*)"/.exec(html)[1];

test('RRR|RR|SS: seven lights in three groups, then 5/7', () => {
    const html = strip('RRR|RR|SS');
    assert.equal(count(html, /class="pip/g), 7);
    assert.equal(count(html, /class="pip on"/g), 5);
    assert.equal(count(html, /class="power-strip-group"/g), 3);
    assert.match(html, /<b>5<\/b>\/7/);
    assert.equal(ariaLabel(html), '5 of 7 VMs running');
    assert.match(html, /role="img"/);
});

test('over 24 lights a compact strip becomes a bar; the large strip keeps every light', () => {
    const states = 'R'.repeat(30) + '|' + 'S'.repeat(24);
    const compact = strip(states);
    assert.equal(count(compact, /class="pip/g), 0);
    assert.match(compact, /class="power-bar"/);
    assert.match(compact, /width:56%/);
    const large = strip(states, { large: true });
    assert.equal(count(large, /class="pip/g), 54);
    assert.match(large, /power-strip-lg/);
    assert.doesNotMatch(large, /power-bar/);
    assert.equal(count(strip('R'.repeat(24)), /class="pip/g), 24, '24 lights still draw');
});

test('failed and not-read VMs show as red and hatched lights and are named', () => {
    const PowerStrip = load();
    const groups = PowerStrip.fromVmGroups([
        { group: { name: 'web', displayName: 'Web', sequencePosition: 2 },
          vms: [{ status: 'RUNNING', sequencePosition: 1, lastStateSyncAt: '2026-10-20T00:00:00Z' }] },
        { group: { name: 'db', displayName: 'Database', sequencePosition: 1 },
          vms: [
              { status: 'STOPPED', sequencePosition: 2, lastStateSyncAt: null },
              { status: 'ERROR', sequencePosition: 1, lastStateSyncAt: '2026-10-20T00:00:00Z' },
          ] },
    ]);
    assert.deepEqual(JSON.parse(JSON.stringify(groups)), [
        { name: 'Database', lights: ['E', 'U'] },
        { name: 'Web', lights: ['R'] },
    ]);
    const html = PowerStrip.render(groups);
    assert.match(html, /class="pip err"/);
    assert.match(html, /class="pip unknown"/);
    assert.equal(ariaLabel(html), '1 of 3 VMs running, 1 failed, 1 not read from AWS yet');
});

test('VM states map to lights', () => {
    const { lightFor } = load();
    const map = s => lightFor({ status: s });
    assert.equal(map('RUNNING'), 'R');
    assert.equal(map('STARTING'), 'B');
    assert.equal(map('STOPPING'), 'B');
    assert.equal(map('STOPPED'), 'S');
    assert.equal(map('ERROR'), 'E');
    assert.equal(map('NOT_FOUND'), 'E');
    assert.equal(map('TERMINATED'), 'E');
    assert.equal(map('UNKNOWN'), 'U');
    assert.equal(lightFor({ status: 'RUNNING', lastStateSyncAt: null }), 'U');
});

test('group counts order the lights running, busy, failed, not read, stopped', () => {
    const PowerStrip = load();
    const groups = PowerStrip.fromGroupStates([
        { name: 'app', sequencePosition: 2, running: 1, stopped: 1 },
        { name: 'db', sequencePosition: 1, running: 2, busy: 1, problem: 1, unknown: 1, stopped: 1 },
    ]);
    assert.equal(groups[0].lights.join(''), 'RRBEUS');
    assert.equal(groups[1].lights.join(''), 'RS');
    const html = PowerStrip.render(groups);
    assert.equal(ariaLabel(html), '3 of 8 VMs running, 1 failed, 1 starting or stopping, 1 not read from AWS yet');
});

test('counts() draws running lights first, then stopped, and handles nothing to show', () => {
    const PowerStrip = load();
    const html = PowerStrip.counts(2, 5);
    assert.equal(count(html, /class="pip on"/g), 2);
    assert.equal(count(html, /class="pip"/g), 3);
    assert.equal(ariaLabel(html), '2 of 5 VMs running');
    const none = PowerStrip.counts(0, 0);
    assert.equal(count(none, /class="pip/g), 0);
    assert.match(none, /<b>0<\/b>\/0/);
    assert.equal(count(PowerStrip.counts(9, 3), /class="pip on"/g), 3, 'running never exceeds total');
});

test('group names are escaped', () => {
    const html = load().render([{ name: '<img src=x onerror=alert(1)>"', lights: ['R'] }]);
    assert.doesNotMatch(html, /<img/);
    assert.match(html, /title="&lt;img src=x onerror=alert\(1\)&gt;&quot;"/);
});

test('showCount: false leaves the number out (legend lights)', () => {
    const html = load().render([{ name: '', lights: ['R'] }], { showCount: false });
    assert.doesNotMatch(html, /power-strip-count/);
});
