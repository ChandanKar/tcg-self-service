// Run with: node --test "src/test/js/**/*.test.mjs"
// U17-T05: the copy rules, checked over every front-end file (js/**/*.js and index.html):
//   - no "please", "sorry", "successfully", "oops", and no "Are you sure" (a dialog names the action);
//   - no gendered pronouns for people;
//   - sentence case in headings, labels, table headers, buttons and dialog titles.
// Comments are skipped. Justified exceptions go in ALLOW with a reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

/** Proper names and product terms that keep their capitals inside a sentence-case label. */
const PROPER = new Set(['AWS', 'EKS', 'API', 'VM', 'VMs', 'IP', 'CSV', 'Teams', 'Microsoft', 'Entra', 'ID', 'IDs', 'EC2', 'Excel',
    'FOCUS', 'Spot', 'Graviton', 'Home', 'Ask', 'UTC', 'MCP', 'SMTP', 'ARN', 'IAM', 'CPU', 'GB', 'URL', 'JSON', 'RDS', 'Organizations',
    'Jira', 'Slack', 'Kubernetes', 'MySQL', 'Cost', 'Explorer', 'Compute', 'Optimizer', 'Savings', 'Plans', 'Reserved', 'Instances',
    'My', 'Operator', 'Admin', 'ENV_ADMIN', 'USER', 'VIEWER', 'ADMIN']);

/** Lines that may break a rule, with why. file:text fragment. */
const ALLOW = [
    // AWS's own product names in the cost pages.
    ['cost-management.js', 'Savings Plans'],
];

function files() {
    const out = [];
    (function walk(dir) {
        for (const f of readdirSync(dir)) {
            const p = path.join(dir, f);
            if (statSync(p).isDirectory()) {
                if (!['old_backup', 'vendor', 'lib', 'dist'].includes(f)) walk(p);
            } else if (/\.js$/.test(f) && p.includes(`${path.sep}js${path.sep}`)) {
                out.push(p);
            }
        }
    })(STATIC);
    out.push(path.join(STATIC, 'index.html'));
    return out;
}

/** The lines of a file outside comments. */
function codeLines(file) {
    const lines = readFileSync(file, 'utf8').split('\n');
    let inBlock = false;
    return lines.map((line, i) => {
        let text = line;
        if (inBlock) {
            const end = text.indexOf('*/');
            if (end < 0) return { n: i + 1, text: '' };
            text = text.slice(end + 2);
            inBlock = false;
        }
        const trimmed = text.trim();
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('<!--')) return { n: i + 1, text: '' };
        const start = text.indexOf('/*');
        if (start >= 0 && !text.includes('*/', start)) { inBlock = true; text = text.slice(0, start); }
        // A trailing // comment (not in a URL).
        text = text.replace(/\s\/\/\s.*$/, '');
        return { n: i + 1, text };
    });
}

function allowed(file, text) {
    return ALLOW.some(([f, frag]) => file.endsWith(f) && text.includes(frag));
}

function rel(file) {
    return path.relative(STATIC, file).replace(/\\/g, '/');
}

test('no please, sorry, successfully, oops or "Are you sure"', () => {
    const found = [];
    for (const file of files()) {
        for (const { n, text } of codeLines(file)) {
            // Only text people read: string literals and markup.
            const strings = text.match(/'[^']*'|"[^"]*"|`[^`]*`|>[^<>]+</g) || [];
            for (const s of strings) {
                if (/\b(please|sorry|successfully|oops)\b|are you sure/i.test(s) && !allowed(file, s)) found.push(`${rel(file)}:${n}: ${s.trim()}`);
            }
        }
    }
    assert.deepEqual(found, [], found.join('\n'));
});

test('no gendered pronouns in what people read', () => {
    const found = [];
    for (const file of files()) {
        for (const { n, text } of codeLines(file)) {
            const strings = text.match(/'[^']*'|"[^"]*"|`[^`]*`|>[^<>]+</g) || [];
            for (const s of strings) {
                if (/\b(he|she|him|his|her|hers|himself|herself)\b/i.test(s) && !/\$\{/.test(s.replace(/\$\{[^}]*\}/g, '')) && !allowed(file, s)) {
                    found.push(`${rel(file)}:${n}: ${s.trim()}`);
                }
            }
        }
    }
    assert.deepEqual(found, [], found.join('\n'));
});

test('sentence case in headings, labels, table headers, buttons and dialog titles', () => {
    const found = [];
    const pattern = /<(h[1-3]|label|th|button)\b[^>]*>\s*([^<$]{3,80}?)\s*<\/\1>|\btitle: '([^'$]{3,80})'|\btitle: "([^"$]{3,80})"|Modals\.(?:confirm|prompt)\(\s*'([^'$]{3,80})'|^\s*'([^'$]{3,60})',\s*$/g;
    for (const file of files()) {
        let prev = '';
        for (const { n, text } of codeLines(file)) {
            for (const m of text.matchAll(pattern)) {
                // A lone 'Title', line counts only as the first argument of Modals.confirm( / prompt(.
                if (m[6] !== undefined && !/Modals\.(confirm|prompt)\(\s*$/.test(prev)) continue;
                const label = (m[2] || m[3] || m[4] || m[5] || m[6] || '').trim();
                const words = label.split(/\s+/).filter(w => /^[A-Za-z]/.test(w));
                const capitalised = words.slice(1).filter(w => /^[A-Z][a-z]{2,}/.test(w) && !PROPER.has(w.replace(/[^A-Za-z0-9_]/g, '')));
                if (words.length >= 2 && capitalised.length && !allowed(file, label)) found.push(`${rel(file)}:${n}: ${label}`);
            }
            if (text.trim()) prev = text;
        }
    }
    assert.deepEqual(found, [], found.join('\n'));
});
