// Run with: node --test "src/test/js/**/*.test.mjs"
// U20-T08: an info tip names its term on the button and is the button's description.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/ui/info-tip.js', 'utf8') + '\nthis.T = InfoTip;', context);
const T = context.T;

test('the button names the term and is described by the text', () => {
    const html = T.markup('cc-tip', 'Cost centre', T.TERMS.costCentre);
    assert.match(html, /<button type="button" class="info-tip-btn" aria-label="What Cost centre means" aria-describedby="cc-tip">/);
    assert.match(html, /<span class="info-tip-text" role="tooltip" id="cc-tip">The finance code/);
});

test('cost centre says where it is used', () => {
    assert.match(T.TERMS.costCentre, /Chargeback/);
    assert.match(T.TERMS.costCentre, /FOCUS export/);
    assert.match(T.TERMS.costCentre, /cost-center tag/);
});

test('text is escaped', () => {
    assert.match(T.markup('x', '<b>', '"a" & <i>'), /What &lt;b&gt; means.*&quot;a&quot; &amp; &lt;i&gt;/s);
});
