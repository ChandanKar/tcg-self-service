// Run with: node --test "src/test/js/**/*.test.mjs"
// U17-T01: the shared empty / filtered / loading / couldn't-load states escape their text, show the ref of a 500,
// wire their buttons through Actions, and leave the 300 ms delay to the .ui-loading class.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const handlers = {};
    const context = vm.createContext({
        window: {},
        Actions: { register: (name, fn) => { handlers[name] = fn; } }
    });
    vm.runInContext(readFileSync(STATIC + 'js/core/utils.js', 'utf8') + '\nthis.Utils = Utils;', context);
    vm.runInContext(readFileSync(STATIC + 'js/ui/states.js', 'utf8') + '\nthis.UiStates = UiStates;', context);
    return { S: context.UiStates, handlers };
}

/** A fake element with the attributes a rendered button would have. */
function buttonFrom(markup) {
    const attrs = {};
    for (const m of markup.matchAll(/data-([a-z-]+)="([^"]*)"/g)) attrs['data-' + m[1]] = m[2];
    return { getAttribute: k => attrs[k] };
}

test('every message is escaped', () => {
    const { S } = load();
    const out = S.empty({ title: '<b>No</b> "envs"', text: 'a & b', action: { label: '<i>Go</i>', href: '#/x?a=1&b=2' } });
    assert.ok(out.includes('&lt;b&gt;No&lt;/b&gt; &quot;envs&quot;'));
    assert.ok(out.includes('a &amp; b'));
    assert.ok(out.includes('&lt;i&gt;Go&lt;/i&gt;'));
    assert.ok(out.includes('href="#/x?a=1&amp;b=2"'));
    assert.ok(!out.includes('<b>No</b>'));
    const f = S.failed({ what: '<script>', status: 0 });
    assert.ok(f.includes('&lt;script&gt; didn&#39;t load') || f.includes('&lt;script&gt; didn\'t load'));
});

test('empty: title, one sentence and one primary action', () => {
    const { S } = load();
    const out = S.empty({ title: 'No environments yet', text: 'Ask for access to one and it appears here once approved.',
        action: { label: 'Request access', href: '#/request-access' } });
    assert.match(out, /role="status"/);
    assert.match(out, /class="ui-state-title">No environments yet</);
    assert.match(out, /<a class="btn btn-primary btn-sm" href="#\/request-access">Request access<\/a>/);
    assert.match(out, /ui-state-glyph/);
});

test('filtered: names the filter and Show all runs the callback once', () => {
    const { S, handlers } = load();
    let cleared = 0;
    const out = S.filtered({ title: 'No VMs match "Running" in Web', text: 'All 6 VMs in Web are stopped.', clearLabel: 'Show all VMs',
        onClear: () => { cleared++; } });
    assert.ok(out.includes('No VMs match &quot;Running&quot; in Web'));
    assert.match(out, /data-action="ui-state-callback"/);
    assert.match(out, />Show all VMs</);
    const btn = buttonFrom(out);
    handlers['ui-state-callback'](btn);
    handlers['ui-state-callback'](btn);
    assert.equal(cleared, 1);
    const named = S.filtered({ title: 'x', clearAction: 'vm-clear-filter', clearData: { group: 'web' } });
    assert.match(named, /data-action="vm-clear-filter" data-group="web"/);
});

test('failed: the reason by status, the page still works, Try again and the ref of a 500', () => {
    const { S, model } = (() => { const l = load(); return { S: l.S, model: l.S.model }; })();
    const out = S.failed({ what: 'VMs', xhr: { status: 500, responseJSON: { ref: '7f3a92c1' } }, retryAction: 'vm-retry' });
    assert.match(out, /role="alert"/);
    assert.match(out, /VMs didn(&#39;|')t load/);
    assert.match(out, /Something failed on the server\. The rest of the page still works\./);
    assert.match(out, /data-action="vm-retry">Try again</);
    assert.match(out, /<span class="ui-state-ref">ref 7f3a92c1<\/span>/);
    assert.equal(model.reason(0), 'The server didn\'t answer.');
    assert.equal(model.reason(403), 'You can\'t see this.');
    assert.equal(model.reason(503), 'Something failed on the server.');
    // No ref without one, and nothing odd is echoed as a ref.
    assert.ok(!S.failed({ what: 'VMs', xhr: { status: 0 } }).includes('ui-state-ref'));
    assert.equal(model.refOf({ responseJSON: { ref: '<img src=x>' } }), '');
});

test('loading: outlines shaped like the content, delayed 300 ms by the ui-loading class', () => {
    const { S } = load();
    const rows = S.loading('rows', { count: 3 });
    assert.match(rows, /^<div class="ui-loading ui-loading-rows">/);
    assert.equal((rows.match(/ui-outline-row/g) || []).length, 3);
    assert.equal((S.loading('cards').match(/ui-outline-card/g) || []).length, 3);
    assert.match(S.loading('lines'), /Loading…/);
    const css = readFileSync(STATIC + 'css/components/states.css', 'utf8');
    assert.match(css, /\.ui-loading,[\s\S]*?animation: ui-loading-appear 0s linear 300ms both;/);
    assert.match(css, /prefers-reduced-motion: reduce/);
});
