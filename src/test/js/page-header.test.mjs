// Run with: node --test "src/test/js/**/*.test.mjs"
// U04-T03: the page header: breadcrumb, title, one summary line, actions on the right.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load(extra = {}) {
    const context = vm.createContext({ document: { title: '' }, window: {}, ...extra });
    const run = (rel, name) => vm.runInContext(readFileSync(STATIC + rel, 'utf8') + `\nthis.${name} = ${name};`, context);
    run('js/core/utils.js', 'Utils');
    run('js/ui/page-header.js', 'PageHeader');
    return context;
}

test('title, breadcrumb and summary; the last crumb is the current page', () => {
    const ctx = load();
    const html = ctx.PageHeader.markup({
        title: 'Payments Prod Mirror',
        summaryHtml: '<span class="mono">44</span> of 54 VMs running',
        breadcrumb: [{ label: 'Environments', href: '#/my-environments' }, { label: 'Payments Prod Mirror' }],
        actionsHtml: '<button type="button" class="btn btn-success">Start all</button>',
    });
    assert.match(html, /class="content-header page-header"/);
    assert.match(html, /<nav class="page-breadcrumb" aria-label="Breadcrumb"><a href="#\/my-environments">Environments<\/a>/);
    assert.match(html, /<span aria-current="page">Payments Prod Mirror<\/span><\/nav>/);
    assert.match(html, /<h1>Payments Prod Mirror<\/h1>/);
    assert.match(html, /<div class="page-summary"><span class="mono">44<\/span> of 54 VMs running<\/div>/);
    assert.match(html, /<div class="page-actions"><button type="button" class="btn btn-success">Start all<\/button><\/div>/);
    assert.equal(ctx.document.title, 'Payments Prod Mirror · Self-Service');
});

test('title and crumb labels are escaped', () => {
    const ctx = load();
    const evil = '<img src=x onerror=alert(1)>';
    const html = ctx.PageHeader.markup({ title: evil, breadcrumb: [{ label: evil, href: '#/x"y' }, { label: evil }] });
    assert.doesNotMatch(html, /<img/);
    assert.match(html, /<h1>&lt;img src=x onerror=alert\(1\)&gt;<\/h1>/);
    assert.match(html, /href="#\/x&quot;y"/);
});

test('empty parts are left out', () => {
    const html = load().PageHeader.markup({ title: 'Help' });
    assert.doesNotMatch(html, /page-breadcrumb/);
    assert.doesNotMatch(html, /page-summary/);
    assert.doesNotMatch(html, /page-actions/);
});

test('helpPage adds the help button to the actions; actionsClass is kept', () => {
    const ctx = load({ HelpLink: { button: (page, label) => `<a class="help-link" data-page="${page}">${label}</a>` } });
    const html = ctx.PageHeader.markup({ title: 'Environments', helpPage: 'my-environments', actionsClass: 'env-detail-actions',
        actionsHtml: '<button>Refresh</button>' });
    assert.match(html, /<div class="page-actions env-detail-actions"><button>Refresh<\/button><a class="help-link" data-page="my-environments">Environments<\/a><\/div>/);
});

test('setDocumentTitle with nothing falls back to the app name', () => {
    const ctx = load();
    ctx.PageHeader.setDocumentTitle('');
    assert.equal(ctx.document.title, 'Self-Service');
});
