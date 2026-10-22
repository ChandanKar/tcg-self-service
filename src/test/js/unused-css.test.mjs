// Run with: node --test "src/test/js/**/*.test.mjs"
// U17-T08: every class a stylesheet styles is used somewhere in the front end (static/js/**/*.js,
// index.html or login.html), by name or built at run time from a prefix ("tone-${x}", 'chip-' + kind).
// Bootstrap's and Font Awesome's own class families are not checked. KEEP lists the deliberate
// exceptions with their reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

const KEEP = {
    // U04-T02 layout primitives: shared building blocks, exercised by layout-primitives.spec.
    'page-fill': 'layout primitive', 'page-frame': 'layout primitive (U18-T01)', 'master-detail': 'layout primitive', 'md-list': 'layout primitive',
    'md-list-title': 'layout primitive', 'with-side-col': 'layout primitive', 'side-col': 'layout primitive',
    // Theme utilities kept for pages to opt into.
    'tabular-nums': 'theme utility', 'custom-table': 'theme utility',
};

/** Bootstrap, Font Awesome and state classes the stylesheets restyle but do not own. */
const FRAMEWORK = /^(fa|fas|far|fab|btn|col|row|d|text|bg|border|m[trblxy]?|p[trblxy]?|g|gap|w|h|form|modal|dropdown|nav|navbar|card|table|alert|badge|show|active|fade|collapse|collapsing|disabled|spinner|visually|sr|list|input|offcanvas|toast|placeholder|is|was|has|open|tooltip|popover|progress|shadow|rounded|position|top|bottom|start|end|align|justify|flex|fw|fs|small|lead|display|h[1-6]|container|ratio|link|icon|me|ms|mt|mb|ps|pe|pt|pb|mx|my|px|py)(-|$)/;

function walk(dir, ext, out = []) {
    for (const f of readdirSync(dir)) {
        const p = path.join(dir, f);
        if (statSync(p).isDirectory()) {
            if (!['old_backup', 'vendor', 'webfonts', 'dist'].includes(f)) walk(p, ext, out);
        } else if (p.endsWith(ext)) {
            out.push(p);
        }
    }
    return out;
}

function used(name, code) {
    const escaped = name.replace(/-/g, '\\-');
    if (new RegExp(`(^|[^\\w-])${escaped}($|[^\\w-])`).test(code)) return true;
    const parts = name.split('-');
    for (let i = 1; i < parts.length; i++) {
        const prefix = parts.slice(0, i).join('-') + '-';
        if (code.includes(prefix + '${') || code.includes(prefix + "' +") || code.includes(prefix + '" +') || code.includes(prefix + "'+")) return true;
    }
    return false;
}

test('every styled class is used by the front end', () => {
    const code = walk(path.join(STATIC, 'js'), '.js').map(f => readFileSync(f, 'utf8')).join('\n')
        + readFileSync(path.join(STATIC, 'index.html'), 'utf8')
        + (existsSync(path.join(STATIC, 'login.html')) ? readFileSync(path.join(STATIC, 'login.html'), 'utf8') : '');
    const unused = [];
    for (const file of walk(path.join(STATIC, 'css'), '.css')) {
        const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
        const classes = new Set();
        for (const m of text.matchAll(/([^{}]+)\{/g)) {
            if (/^\s*@/.test(m[1])) continue;
            const selector = m[1].replace(/:not\([^)]*\)/g, '');
            for (const c of selector.matchAll(/\.([a-zA-Z][\w-]*)/g)) classes.add(c[1]);
        }
        for (const c of classes) {
            if (FRAMEWORK.test(c) || KEEP[c] || used(c, code)) continue;
            unused.push(`${path.relative(STATIC, file).replace(/\\/g, '/')}: .${c}`);
        }
    }
    assert.deepEqual(unused, [], `Styled but never used:\n${unused.join('\n')}`);
});
