// Run with: node --test "src/test/js/**/*.test.mjs"
// U03-T04: the old Tailwind-like palette is retired. Stylesheets use the style-guide tokens from the
// :root block of main.css; a retired colour anywhere else fails here. Brand colours of other
// products (Microsoft sign-in, Excel export) are not on the list and need no exception.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const CSS = fileURLToPath(new URL('../../main/resources/static/css/', import.meta.url));

const RETIRED_HEX = [
    // blues
    '#2563eb', '#1d4ed8', '#1e40af', '#3b82f6', '#0a58ca', '#1e3a8a', '#60a5fa', '#93c5fd', '#bfdbfe',
    '#dbeafe', '#eff6ff', '#e0e7ff', '#f8fbff', '#3730a3', '#4f46e5', '#6366f1', '#c7d2fe',
    // greens
    '#10b981', '#059669', '#16a34a', '#22c55e', '#047857', '#166534', '#15803d', '#065f46', '#dcfce7',
    '#d1fae5', '#ecfdf5', '#f0fdf4', '#bbf7d0', '#86efac', '#a7f3d0',
    // reds
    '#ef4444', '#dc2626', '#b91c1c', '#991b1b', '#7f1d1d', '#f87171', '#fee2e2', '#fef2f2', '#fecaca', '#fca5a5',
    // ambers
    '#f59e0b', '#d97706', '#fbbf24', '#fcd34d', '#fde68a', '#b45309', '#92400e', '#78350f', '#8a5a00',
    '#fef3c7', '#fffbeb', '#fef9c3', '#ffc107',
    // slate greys
    '#0f172a', '#1e293b', '#334155', '#111827', '#1f2937', '#374151', '#475569', '#64748b', '#4b5563',
    '#6b7280', '#6c757d', '#5c636a', '#94a3b8', '#9ca3af', '#cbd5e1', '#d1d5db', '#e2e8f0', '#e5e7eb',
    '#dee2e6', '#f1f5f9', '#f3f4f6', '#eef2f7', '#f8fafc', '#f9fafb', '#fafbfc', '#f4f6f8', '#f8f9fa',
];
const RETIRED_RGB = ['37, 99, 235', '59, 130, 246', '16, 185, 129', '239, 68, 68', '245, 158, 11', '100, 116, 139'];

// Files allowed to keep a retired colour, with the reason. Keep this empty unless there is a reason.
const ALLOW = {};

function sheets(dir) {
    return readdirSync(dir).flatMap(name => {
        const full = path.join(dir, name);
        return statSync(full).isDirectory() ? sheets(full) : name.endsWith('.css') ? [full] : [];
    });
}

/** The stylesheet without comments and, for main.css, without its :root token block. */
function rules(file) {
    let text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    if (path.basename(file) === 'main.css') {
        const start = text.indexOf(':root {');
        const end = text.indexOf('\n}', start);
        text = text.slice(0, start) + text.slice(end);
    }
    return text.toLowerCase();
}

test('no stylesheet uses a retired palette colour', () => {
    const found = [];
    for (const file of sheets(CSS)) {
        const rel = path.relative(CSS, file).replace(/\\/g, '/');
        const text = rules(file);
        for (const hex of RETIRED_HEX) {
            if (new RegExp(hex + '(?![0-9a-f])').test(text) && !(ALLOW[rel] || []).includes(hex)) found.push(`${rel}: ${hex}`);
        }
        for (const rgb of RETIRED_RGB) {
            if (text.replace(/\s+/g, ' ').includes(`rgba(${rgb},`) && !(ALLOW[rel] || []).includes(rgb)) found.push(`${rel}: rgba(${rgb})`);
        }
    }
    assert.deepEqual(found, []);
});

test('every token a stylesheet uses is defined (no rule leans on a hard-coded fallback)', () => {
    const all = sheets(CSS).map(f => readFileSync(f, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')).join('\n');
    const defined = new Set([...all.matchAll(/(--[\w-]+)\s*:/g)].map(m => m[1]));
    const used = new Set([...all.matchAll(/var\(\s*(--[\w-]+)/g)].map(m => m[1]));
    const missing = [...used].filter(name => !name.startsWith('--bs-') && !defined.has(name)).sort();
    assert.deepEqual(missing, []);
});

test('the app is light only', () => {
    const main = readFileSync(path.join(CSS, 'main.css'), 'utf8');
    assert.match(main, /:root \{\s*color-scheme: light;/);
    for (const file of sheets(CSS)) {
        assert.doesNotMatch(readFileSync(file, 'utf8'), /prefers-color-scheme/, path.relative(CSS, file));
    }
});

test('css/index.css (an unused architecture note) is gone', () => {
    assert.ok(!sheets(CSS).some(f => path.relative(CSS, f) === 'index.css'));
});
