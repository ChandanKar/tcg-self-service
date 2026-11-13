// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T07: every help topic sits in a known group, the groups keep their order, and two topics carry the legend.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ window: {}, Actions: { register() {} } });
vm.runInContext(readFileSync(STATIC + 'js/features/help-content.js', 'utf8') + '\nthis.HelpContent = HelpContent;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/help.js', 'utf8') + '\nthis.Help = Help;', context);
const { HelpContent, Help } = context;

test('every topic is in one of the groups', () => {
    const keys = HelpContent.GROUPS.map(g => g[0]);
    assert.deepEqual(JSON.parse(JSON.stringify(HelpContent.GROUPS.map(g => g[1]))),
        ['Using environments', 'Access', 'Cost and more', 'Manage', 'Administration']);
    for (const t of HelpContent.all()) assert.ok(keys.includes(t.group), `${t.id} has group ${t.group}`);
});

test('topics for a role sit in that role\'s group; everyone\'s topics do not', () => {
    for (const t of HelpContent.all()) {
        if (!t.roles) assert.ok(['using', 'access', 'cost'].includes(t.group), t.id);
        else assert.ok(['manage', 'admin'].includes(t.group), t.id);
    }
});

test('grouped keeps the groups\' order and leaves out empty ones', () => {
    const list = HelpContent.all().filter(t => !t.roles);
    const groups = JSON.parse(JSON.stringify(Help.model.grouped(list)));
    assert.deepEqual(groups.map(g => g.name), ['Using environments', 'Access', 'Cost and more']);
    assert.deepEqual(groups[0].topics.slice(0, 3).map(t => t.title),
        ['Getting started and roles', 'Environments and start order', 'Starting, stopping and locks']);
});

test('the start/stop and environments topics carry the legend', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(HelpContent.all().filter(t => t.legend).map(t => t.id).sort())), ['environments', 'locks']);
    assert.match(Help.LEGEND, /^Green is running, gold is starting or stopping, hollow is stopped, striped means/);
});

test('U23-T13: a topic about a switched-off feature is left out', () => {
    const leases = HelpContent.find('leases');
    context.Capabilities = { has: f => f !== 'leases' };
    assert.equal(Help.visible(leases), false);
    context.Capabilities = { has: () => true };
    assert.equal(Help.visible(leases), true);
    delete context.Capabilities;
});
