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
        ['Using environments', 'Access and notifications', 'Cost', 'Problems and questions', 'Manage', 'Administration']);
    for (const t of HelpContent.all()) assert.ok(keys.includes(t.group), `${t.id} has group ${t.group}`);
});

test('topics for a role sit in that role\'s group; everyone\'s topics do not', () => {
    for (const t of HelpContent.all()) {
        if (!t.roles) assert.ok(['using', 'access', 'cost', 'trouble'].includes(t.group), t.id);
        else assert.ok(['manage', 'admin'].includes(t.group), t.id);
    }
});

test('grouped keeps the groups\' order and leaves out empty ones', () => {
    const list = HelpContent.all().filter(t => !t.roles);
    const groups = JSON.parse(JSON.stringify(Help.model.grouped(list)));
    assert.deepEqual(groups.map(g => g.name), ['Using environments', 'Access and notifications', 'Cost', 'Problems and questions']);
    assert.deepEqual(groups[0].topics.slice(0, 5).map(t => t.title),
        ['Getting started and roles', 'Signing in and out', 'Home', 'Reading an environment', 'Starting and stopping']);
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

// U27: help for every persona, written as tasks.
test('every topic has sections with text, and related topics that exist', () => {
    const ids = new Set(HelpContent.all().map(t => t.id));
    assert.equal(ids.size, HelpContent.all().length, 'topic ids are unique');
    for (const t of HelpContent.all()) {
        assert.ok(t.sections.length, t.id);
        assert.ok(t.summary && t.summary.trim(), `${t.id} has a summary`); // U28: every topic opens with who and what
        for (const s of t.sections) {
            assert.ok(s.heading, t.id);
            assert.ok((s.paragraphs || []).length + (s.steps || []).length + (s.terms || []).length > 0, `${t.id}: ${s.heading} is empty`);
            for (const pair of s.terms || []) assert.equal(pair.length, 2, `${t.id}: ${pair[0]}`);
        }
        for (const r of t.related || []) assert.ok(ids.has(r), `${t.id} links to missing ${r}`);
    }
});

test('each persona has its own topics, and every page with a role opens a topic that role can see', () => {
    const forEveryone = HelpContent.all().filter(t => !t.roles).map(t => t.id);
    for (const id of ['getting-started', 'sign-in', 'home', 'environments', 'locks', 'locking', 'access', 'my-account', 'notifications', 'faq']) {
        assert.ok(forEveryone.includes(id), id);
    }
    const ids = id => HelpContent.find(id).roles || [];
    for (const id of ['access-admin', 'vm-registry', 'environment-settings', 'automation-rules', 'budgets']) assert.ok(ids(id).includes('ENV_ADMIN'), id);
    for (const id of ['users', 'teams', 'cost-management', 'rightsizing', 'savings', 'aws-accounts', 'system-health', 'settings', 'api-access']) assert.ok(ids(id).includes('ADMIN'), id);
    assert.equal(HelpContent.PAGE_TOPICS['dashboard'], 'home');
    assert.equal(HelpContent.PAGE_TOPICS['user-management'], 'users');
    assert.equal(HelpContent.PAGE_TOPICS['system-health'], 'system-health');
    assert.equal(HelpContent.PAGE_TOPICS['environment-settings'], 'environment-settings');
});

test('terms render as pairs, escaped, and search finds words in them', () => {
    context.Utils = {
        escapeHtml: v => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'),
        raw: v => ({ __raw: String(v) }),
        html: (strings, ...values) => strings.reduce((out, s, i) => out + s + (i < values.length
            ? (values[i] && values[i].__raw !== undefined ? values[i].__raw : context.Utils.escapeHtml(values[i])) : ''), ''),
    };
    const html = Help.model.sectionHtml({ heading: 'H', terms: [['<b>x</b>', 'y']] });
    assert.match(html, /<dl class="help-terms"><div class="help-term"><dt>&lt;b&gt;x&lt;\/b&gt;<\/dt><dd>y<\/dd><\/div><\/dl>/);
    assert.ok(Help.model.text(HelpContent.find('locks')).includes('no approval needed'));
    delete context.Utils;
});
