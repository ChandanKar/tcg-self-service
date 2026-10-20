// Run with: node --test "src/test/js/**/*.test.mjs"
// U04-T02: the pager, tabs and filter group of the new screens.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({ document: {}, window: {} });
    const run = (rel, name) => vm.runInContext(readFileSync(STATIC + rel, 'utf8') + `\nthis.${name} = ${name};`, context);
    run('js/core/utils.js', 'Utils');
    vm.runInContext('this.Config = { PAGE_SIZE: 10 };', context);
    run('js/ui/pagination.js', 'Pagination');
    run('js/ui/tabs.js', 'Tabs');
    run('js/ui/filter-group.js', 'FilterGroup');
    return context;
}

const attr = (html, re) => (re.exec(html) || [])[1];

test('buildPager: 54 items, page 1 reads 1–10 of 54, Previous disabled, Next targets page 2', () => {
    const { Pagination } = load();
    const html = Pagination.buildPager({ page: 1, totalItems: 54, action: 'env-page', key: 'envs' });
    assert.match(html, /<span class="pager-range">1–10 of 54<\/span>/);
    const [prev, next] = html.match(/<button[^>]*>(Previous|Next)<\/button>/g);
    assert.match(prev, /disabled/);
    assert.doesNotMatch(next, /disabled/);
    assert.equal(attr(next, /data-page="(\d+)"/), '2');
    assert.equal(attr(next, /data-action="([^"]+)"/), 'env-page');
    assert.equal(attr(next, /data-page-key="([^"]+)"/), 'envs');
});

test('buildPager: the last page is partial and Next is disabled; page numbers out of range clamp', () => {
    const { Pagination } = load();
    const last = Pagination.buildPager({ page: 6, totalItems: 54, action: 'a' });
    assert.match(last, /51–54 of 54/);
    assert.match(last.match(/<button[^>]*>Next<\/button>/)[0], /disabled/);
    assert.match(Pagination.buildPager({ page: 99, totalItems: 54, action: 'a' }), /51–54 of 54/);
    assert.match(Pagination.buildPager({ page: 2, pageSize: 25, totalItems: 54, action: 'a' }), /26–50 of 54/);
});

test('buildPager: no items shows the empty text and no buttons', () => {
    const { Pagination } = load();
    const html = Pagination.buildPager({ page: 1, totalItems: 0, action: 'a', emptyText: 'No VMs <yet>' });
    assert.doesNotMatch(html, /<button/);
    assert.match(html, /No VMs &lt;yet&gt;/);
});

test('slice returns one page and clamps the page number', () => {
    const { Pagination } = load();
    const list = Array.from({ length: 23 }, (_, i) => i + 1);
    assert.deepEqual([...Pagination.slice(list, 1)], [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    assert.deepEqual([...Pagination.slice(list, 3)], [21, 22, 23]);
    assert.deepEqual([...Pagination.slice(list, 9)], [21, 22, 23]);
    assert.deepEqual([...Pagination.slice(list, 0)], [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    assert.equal(Pagination.slice(null, 1).length, 0);
});

test('Tabs.markup: a labelled tablist, one selected tab in the Tab order, panels linked', () => {
    const { Tabs } = load();
    const html = Tabs.markup({ id: 'env-tabs', label: 'Environment sections', active: 'cost',
        tabs: [{ key: 'vms', label: 'VMs', count: 6 }, { key: 'cost', label: 'Cost & <settings>' }] });
    assert.match(html, /role="tablist" id="env-tabs" aria-label="Environment sections"/);
    const tabs = html.match(/<button[\s\S]*?<\/button>/g);
    assert.equal(tabs.length, 2);
    assert.match(tabs[0], /role="tab"/);
    assert.match(tabs[0], /aria-selected="false"/);
    assert.match(tabs[0], /tabindex="-1"/);
    assert.match(tabs[0], /aria-controls="env-tabs-panel-vms"/);
    assert.match(tabs[0], /<span class="tab-count">6<\/span>/);
    assert.match(tabs[1], /aria-selected="true"/);
    assert.match(tabs[1], /tabindex="0"/);
    assert.match(tabs[1], /Cost &amp; &lt;settings&gt;/);

    const panel = Tabs.panel({ id: 'env-tabs', key: 'vms', active: 'cost' }, '<p>x</p>');
    assert.match(panel, /role="tabpanel"/);
    assert.match(panel, /aria-labelledby="env-tabs-tab-vms"/);
    assert.match(panel, / hidden>/);
});

test('Tabs.markup: an unknown active key selects the first tab', () => {
    const { Tabs } = load();
    const html = Tabs.markup({ id: 't', label: 'x', active: 'nope', tabs: [{ key: 'a', label: 'A' }, { key: 'b', label: 'B' }] });
    assert.match(html.match(/<button[\s\S]*?<\/button>/g)[0], /aria-selected="true"/);
});

test('FilterGroup.markup: a labelled group of toggle buttons, one pressed', () => {
    const { FilterGroup } = load();
    const html = FilterGroup.markup({ label: 'Show', active: 'running', action: 'vm-filter',
        options: [{ key: 'all', label: 'All', count: 12 }, { key: 'running', label: 'Running', count: 9 }, { key: 'problems', label: 'Problems' }] });
    assert.match(html, /role="group" aria-label="Show"/);
    const buttons = html.match(/<button[\s\S]*?<\/button>/g);
    assert.equal(buttons.length, 3);
    assert.deepEqual(buttons.map(b => attr(b, /aria-pressed="(\w+)"/)), ['false', 'true', 'false']);
    assert.equal(attr(buttons[1], /data-filter="(\w+)"/), 'running');
    assert.equal(attr(buttons[1], /data-action="([^"]+)"/), 'vm-filter');
    assert.match(buttons[0], /<span class="filter-count">12<\/span>/);
});
