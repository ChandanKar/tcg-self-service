// Run with: node --test "src/test/js/**/*.test.mjs"
// Checks that toasts and modals render titles and messages as text unless { html: true }.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const js = (rel) => readFileSync(
    fileURLToPath(new URL('../../main/resources/static/js/' + rel, import.meta.url)), 'utf8');

const PAYLOAD = '<img src=x onerror=alert(1)>';

/** Minimal recording stand-in for the jQuery calls these modules make. */
function makeJQuery() {
    const appendedToBody = [];
    const elements = [];

    function wrap(source) {
        const el = {
            source, classes: [], attrs: {}, children: [], textValue: null, htmlValue: null, handlers: {},
            length: source === '#notification-container' && elements.some(e => e.attrs.id === 'notification-container') ? 1 : 0,
            attr(name, value) { if (value === undefined) return this.attrs[name]; this.attrs[name] = value; return this; },
            addClass(c) { this.classes.push(c); return this; },
            append(child) { this.children.push(child); return this; },
            appendTo(parent) { parent.children.push(this); return this; },
            text(v) { this.textValue = v; return this; },
            html(v) { this.htmlValue = v; return this; },
            off() { return this; },
            on(event, a, b) { this.handlers[event] = b || a; return this; },
            empty() { this.children = []; return this; },
            remove() { return this; },
            closest() { return this; }
        };
        if (typeof source === 'string' && source.startsWith('<div id="notification-container"')) {
            el.attrs.id = 'notification-container';
        }
        elements.push(el);
        return el;
    }

    const $ = (source) => {
        if (source === 'body') {
            return { append(markup) { appendedToBody.push(markup); if (String(markup).includes('notification-container')) wrap(markup); return this; } };
        }
        return wrap(source);
    };
    return { $, appendedToBody, elements };
}

function loadNotifications() {
    const jq = makeJQuery();
    const context = vm.createContext({ $: jq.$, document: { getElementById: () => null }, setTimeout: () => 0 });
    vm.runInContext(js('ui/notifications.js') + '\nthis.Notifications = Notifications;', context);
    return { Notifications: context.Notifications, jq };
}

function toastMessage(jq) {
    return jq.elements.filter(e => e.source === '<p class="toast-message"></p>').at(-1);
}

test('toast message is set as text by default', () => {
    const { Notifications, jq } = loadNotifications();
    Notifications.error(PAYLOAD, 0);
    const msg = toastMessage(jq);
    assert.equal(msg.textValue, PAYLOAD);
    assert.equal(msg.htmlValue, null);
});

test('toast message is HTML only when the caller opts in', () => {
    const { Notifications, jq } = loadNotifications();
    Notifications.warning('Locked by <strong>x</strong>', 0, { html: true });
    const msg = toastMessage(jq);
    assert.equal(msg.htmlValue, 'Locked by <strong>x</strong>');
    assert.equal(msg.textValue, null);
});

test('showError title and message are text by default', () => {
    const { Notifications, jq } = loadNotifications();
    Notifications.showError(PAYLOAD, { title: PAYLOAD, duration: 0 });
    const title = jq.elements.filter(e => e.source === '<strong class="toast-title"></strong>').at(-1);
    assert.equal(title.textValue, PAYLOAD);
    assert.equal(toastMessage(jq).textValue, PAYLOAD);
});

test('danger maps to the error style and icon; ids are unique', () => {
    const { Notifications, jq } = loadNotifications();
    const a = Notifications.show('one', 'danger', 0);
    const b = Notifications.show('two', 'danger', 0);
    assert.notEqual(a, b);
    const toast = jq.elements.filter(e => e.attrs.id === b)[0];
    assert.ok(toast.classes.includes('toast-error'));
    const icon = jq.elements.filter(e => e.source === '<i class="fas toast-icon" aria-hidden="true"></i>').at(-1);
    assert.ok(icon.classes.includes('fa-exclamation-circle'));
});

test('toast markup has no inline event handlers', () => {
    const source = js('ui/notifications.js');
    assert.doesNotMatch(source, /onclick=/);
});

function loadModals() {
    const jq = makeJQuery();
    const fakeEl = { addEventListener() {} };
    const context = vm.createContext({
        $: jq.$,
        document: { readyState: 'complete', querySelectorAll: () => [], getElementById: () => fakeEl, addEventListener() {} },
        bootstrap: { Modal: class { show() {} static getInstance() { return null; } } }
    });
    vm.runInContext(js('core/utils.js') + '\n' + js('ui/modals.js') + '\nthis.Modals = Modals;', context);
    return { Modals: context.Modals, jq };
}

test('confirm escapes title and message by default', () => {
    const { Modals, jq } = loadModals();
    Modals.confirm(PAYLOAD, PAYLOAD, () => {});
    const markup = jq.appendedToBody.at(-1);
    assert.doesNotMatch(markup, /<img/);
    assert.match(markup, /&lt;img src=x onerror=alert\(1\)&gt;/);
});

test('confirm keeps markup when the caller opts in', () => {
    const { Modals, jq } = loadModals();
    Modals.confirm('Delete', 'Delete <strong>env</strong>?', () => {}, { html: true });
    assert.match(jq.appendedToBody.at(-1), /<p>Delete <strong>env<\/strong>\?<\/p>/);
});

test('show escapes the title and button text unless told otherwise', () => {
    const { Modals, jq } = loadModals();
    Modals.show({ title: `Lock History: ${PAYLOAD}`, buttons: [{ text: PAYLOAD }] });
    const markup = jq.appendedToBody.at(-1);
    assert.doesNotMatch(markup, /<img/);

    Modals.show({ title: '<i class="fas fa-key"></i>Set Password', html: true });
    assert.match(jq.appendedToBody.at(-1), /<i class="fas fa-key"><\/i>Set Password/);
});

test('prompt escapes label, placeholder and default value', () => {
    const { Modals, jq } = loadModals();
    Modals.prompt('Rename', PAYLOAD, () => {}, { placeholder: '" autofocus onfocus="x', defaultValue: PAYLOAD });
    const markup = jq.appendedToBody.at(-1);
    assert.doesNotMatch(markup, /<img/);
    assert.doesNotMatch(markup, /" autofocus/);
});
