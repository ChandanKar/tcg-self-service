// Run with: node --test "src/test/js/**/*.test.mjs"
// U18-T01: the list panel's arithmetic (how many rows fit, which page keeps the first visible item)
// and its markup (a named, keyboard-reachable scroll region between a fixed head and foot).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({ window: {} });
    vm.runInContext(readFileSync(STATIC + 'js/core/utils.js', 'utf8') + '\nthis.Utils = Utils;', context);
    vm.runInContext(readFileSync(STATIC + 'js/ui/list-panel.js', 'utf8') + '\nthis.ListPanel = ListPanel;', context);
    return context.ListPanel;
}

test('rows that fit: floor of the space over the row height, within min and max', () => {
    const L = load();
    assert.equal(L.rowsThatFit(440, 44), 10);
    assert.equal(L.rowsThatFit(439, 44), 9);
    assert.equal(L.rowsThatFit(60, 44), 3, 'never fewer than 3');
    assert.equal(L.rowsThatFit(60, 44, { min: 1 }), 1);
    assert.equal(L.rowsThatFit(10000, 44), 50, 'never more than 50');
    assert.equal(L.rowsThatFit(10000, 44, { max: 15 }), 15);
    assert.equal(L.rowsThatFit(0, 44), 3, 'no space measured yet: the minimum');
    assert.equal(L.rowsThatFit(400, 0), 3, 'no row measured yet: the minimum');
});

test('a new page size keeps the first visible item on screen', () => {
    const L = load();
    assert.equal(L.pageKeepingFirst(0, 9), 1);
    assert.equal(L.pageKeepingFirst(8, 9), 1);
    assert.equal(L.pageKeepingFirst(9, 9), 2);
    // was on page 3 of 15 (items 30..44); at 9 a page item 30 is on page 4 (27..35)
    assert.equal(L.pageKeepingFirst(30, 9), 4);
    assert.equal(L.pageKeepingFirst(30, 0), 1);
});

test('markup: a fixed head and foot around a named, keyboard-reachable body', () => {
    const L = load();
    const html = L.markup({ id: 'envs', title: 'Environments <b>', toolbarHtml: '<input type="search">', bodyHtml: '<table></table>', footHtml: '<div class="pager"></div>' });
    assert.match(html, /<section class="list-panel" id="envs" aria-labelledby="envs-title">/);
    assert.match(html, /<h2 id="envs-title">Environments &lt;b&gt;<\/h2>/, 'the title is escaped');
    assert.match(html, /class="list-panel-body" id="envs-body" tabindex="0" role="region" aria-label="Environments &lt;b&gt;, scrolls"/);
    assert.match(html, /<div class="list-panel-foot" id="envs-foot"><div class="pager"><\/div><\/div>/);
    assert.ok(html.indexOf('list-panel-head') < html.indexOf('list-panel-body') && html.indexOf('list-panel-body') < html.indexOf('list-panel-foot'));
});

test('markup without a title or foot: labelled by the label, no empty head or foot', () => {
    const L = load();
    const html = L.markup({ id: 'x', label: 'Activity', bodyHtml: '' });
    assert.match(html, /aria-label="Activity"/);
    assert.doesNotMatch(html, /list-panel-head/);
    assert.doesNotMatch(html, /list-panel-foot/);
});

test('fitCount is null when the page scrolls instead (no frame: phones, small windows)', () => {
    const L = load();
    assert.equal(L.framed(), false);
    assert.equal(L.fitCount({ querySelector: () => null }), null);
});
