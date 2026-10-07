// Run with: node --test "src/test/js/**/*.test.mjs"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const utilsPath = fileURLToPath(new URL('../../main/resources/static/js/core/utils.js', import.meta.url));

function loadUtils() {
    const context = vm.createContext({ window: {}, document: {}, URLSearchParams, Intl });
    // `const Utils` is not a property of the context, so expose it explicitly.
    vm.runInContext(readFileSync(utilsPath, 'utf8') + '\nthis.Utils = Utils;', context);
    return context.Utils;
}

const Utils = loadUtils();

test('escapeHtml escapes markup and both quote kinds', () => {
    assert.equal(
        Utils.escapeHtml(`<a href="x" title='y'>&`),
        '&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;'
    );
});

test('escapeHtml keeps falsy values that are real data', () => {
    assert.equal(Utils.escapeHtml(0), '0');
    assert.equal(Utils.escapeHtml(false), 'false');
    assert.equal(Utils.escapeHtml(''), '');
});

test('escapeHtml turns null and undefined into an empty string', () => {
    assert.equal(Utils.escapeHtml(null), '');
    assert.equal(Utils.escapeHtml(undefined), '');
});

test('escaped value cannot break out of a quoted attribute', () => {
    const payload = '" autofocus onfocus="alert(1)';
    const markup = `<p title="${Utils.escapeHtml(payload)}">x</p>`;
    assert.equal(markup, '<p title="&quot; autofocus onfocus=&quot;alert(1)">x</p>');
});

test('html escapes interpolations and embeds raw markup', () => {
    const out = Utils.html`<p title="${'" onfocus="x'}">${Utils.raw('<b>ok</b>')}</p>`;
    assert.equal(out, '<p title="&quot; onfocus=&quot;x"><b>ok</b></p>');
});

test('html escapes array elements and joins them', () => {
    const items = ['<i>', 'a&b'].map(v => Utils.raw(Utils.html`<li>${v}</li>`));
    assert.equal(Utils.html`<ul>${items}</ul>`, '<ul><li>&lt;i&gt;</li><li>a&amp;b</li></ul>');
    assert.equal(Utils.html`${['<x>', 1]}`, '&lt;x&gt;1');
});

test('html renders null, undefined and false as empty, keeps 0', () => {
    assert.equal(Utils.html`[${null}][${undefined}][${false}][${0}]`, '[][][][0]');
});

test('raw is not escaped twice and treats null as empty', () => {
    assert.equal(Utils.html`${Utils.raw(null)}`, '');
    assert.equal(String(Utils.raw('<br>')), '<br>');
});
