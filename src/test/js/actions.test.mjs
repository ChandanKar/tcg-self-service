// Run with: node --test "src/test/js/**/*.test.mjs"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

class FakeElement {
    constructor(tagName, attrs = {}, parent = null) {
        this.tagName = tagName.toUpperCase();
        this.attrs = attrs;
        this.parentElement = parent;
        this.dataset = {};
        for (const [k, v] of Object.entries(attrs)) {
            if (k.startsWith('data-')) {
                this.dataset[k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = v;
            }
        }
    }

    getAttribute(name) {
        return name in this.attrs ? this.attrs[name] : null;
    }

    closest(selector) {
        const attr = selector.slice(1, -1); // "[data-action]" -> "data-action"
        for (let el = this; el; el = el.parentElement) {
            if (attr in el.attrs) return el;
        }
        return null;
    }
}

function loadActions() {
    const listeners = {};
    const context = vm.createContext({
        Element: FakeElement,
        console,
        document: { addEventListener: (type, fn) => { listeners[type] = fn; } }
    });
    vm.runInContext(readFileSync(join(STATIC, 'js/core/actions.js'), 'utf8') + '\nthis.Actions = Actions;', context);
    const fire = (type, target) => {
        let prevented = false;
        listeners[type]({ target, preventDefault: () => { prevented = true; } });
        return prevented;
    };
    return { Actions: context.Actions, fire };
}

test('click on a child element runs the nearest registered action with the element', () => {
    const { Actions, fire } = loadActions();
    const calls = [];
    Actions.register('vr-edit-vm', (el) => calls.push(el.dataset.vmId));
    const button = new FakeElement('button', { 'data-action': 'vr-edit-vm', 'data-vm-id': 'vm-1' });
    const icon = new FakeElement('i', {}, button);

    fire('click', icon);

    assert.deepEqual(calls, ['vm-1']);
});

test('links are prevented from navigating; buttons are not', () => {
    const { Actions, fire } = loadActions();
    Actions.register('go', () => {});
    assert.equal(fire('click', new FakeElement('a', { 'data-action': 'go' })), true);
    assert.equal(fire('click', new FakeElement('button', { 'data-action': 'go' })), false);
});

test('unregistered data-action values are ignored so module-bound handlers keep working', () => {
    const { fire } = loadActions();
    const link = new FakeElement('a', { 'data-action': 'toggle-admin' });
    assert.equal(fire('click', link), false);
});

test('input and change actions use their own attributes', () => {
    const { Actions, fire } = loadActions();
    const seen = [];
    Actions.register('env-search', (el) => seen.push('input:' + el.dataset.actionInput), 'input');
    Actions.register('vr-reload', () => seen.push('change'), 'change');
    Actions.register('vr-reload', () => seen.push('click'));

    fire('input', new FakeElement('input', { 'data-action-input': 'env-search' }));
    fire('change', new FakeElement('select', { 'data-action-change': 'vr-reload' }));
    fire('click', new FakeElement('select', { 'data-action-change': 'vr-reload' }));

    assert.deepEqual(seen, ['input:env-search', 'change']);
});

test('unsupported event types are rejected', () => {
    const { Actions } = loadActions();
    assert.throws(() => Actions.register('x', () => {}, 'mouseover'));
});

// ---- guard: no inline handlers, inline scripts or javascript: URLs in app sources -----------

function sourceFiles(dir) {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) {
            return ['dist', 'vendor', 'old_backup'].includes(name) ? [] : sourceFiles(path);
        }
        return /\.(js|html)$/.test(name) ? [path] : [];
    });
}

// Legacy prototypes the app does not use (/home forwards to index.html); old_backup/ is skipped too.
const LEGACY = new Set(['home.html', 'home-original.html']);

test('app sources contain no inline event handler attributes', () => {
    const offenders = [];
    for (const file of sourceFiles(STATIC)) {
        if (LEGACY.has(relative(STATIC, file))) continue;
        readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
            if (/\son[a-z]+=["']/.test(line)) offenders.push(`${relative(STATIC, file)}:${i + 1}`);
        });
    }
    assert.deepEqual(offenders, []);
});

test('pages contain no inline <script> blocks and no javascript: URLs', () => {
    const offenders = [];
    for (const file of sourceFiles(STATIC)) {
        if (LEGACY.has(relative(STATIC, file))) continue;
        const text = readFileSync(file, 'utf8');
        if (file.endsWith('.html') && /<script(?![^>]*\bsrc=)[^>]*>/i.test(text)) offenders.push(relative(STATIC, file));
        if (/javascript:/i.test(text)) offenders.push(relative(STATIC, file) + ' (javascript:)');
    }
    assert.deepEqual(offenders, []);
});
