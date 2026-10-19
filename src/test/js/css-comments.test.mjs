// Run with: node --test "src/test/js/**/*.test.mjs"
// U03-T02: a "*/" inside a CSS comment (for example "btn-outline-*/solid-*") ends the comment early and
// the browser drops the next rule. buttons.css lost its .btn-action rule that way, so every row icon
// rendered as a filled button. Every stylesheet must open and close the same number of comments.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const CSS = fileURLToPath(new URL('../../main/resources/static/css/', import.meta.url));

function sheets(dir) {
    return readdirSync(dir).flatMap(name => {
        const full = path.join(dir, name);
        return statSync(full).isDirectory() ? sheets(full) : name.endsWith('.css') ? [full] : [];
    });
}

test('every stylesheet closes each comment exactly once', () => {
    const broken = [];
    for (const file of sheets(CSS)) {
        const text = readFileSync(file, 'utf8');
        let i = 0;
        while ((i = text.indexOf('/*', i)) !== -1) {
            const end = text.indexOf('*/', i + 2);
            if (end === -1) { broken.push(`${path.relative(CSS, file)}: unclosed comment`); break; }
            i = end + 2;
        }
        // Outside comments there must be no stray "*/" left over from a comment that closed early.
        const outside = text.replace(/\/\*[\s\S]*?\*\//g, '');
        if (outside.includes('*/')) broken.push(`${path.relative(CSS, file)}: a comment closes early`);
    }
    assert.deepEqual(broken, []);
});
