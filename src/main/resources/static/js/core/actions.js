/**
 * VM Self-Service Platform - Delegated UI actions
 *
 * Replaces inline event handler attributes (onclick, oninput, ...), which a strict
 * Content-Security-Policy blocks and which put data into a JavaScript string context.
 *
 *   <button data-action="vr-edit-vm" data-vm-id="${Utils.escapeHtml(vm.vmId)}">
 *   <input data-action-input="env-search">
 *   <select data-action-change="vr-filter-status">
 *
 *   Actions.register('vr-edit-vm', el => VmRegistry.editVm(el.dataset.vmId));
 *
 * One listener per event type on document; handlers receive (element, event). Names are
 * prefixed per module. Elements whose data-action has no registered handler are ignored, so
 * modules that bind their own [data-action="..."] handlers keep working.
 */
const Actions = (function() {
    'use strict';

    const handlers = { click: {}, input: {}, change: {} };
    const ATTRIBUTES = { click: 'data-action', input: 'data-action-input', change: 'data-action-change' };

    function register(name, handler, eventType = 'click') {
        if (!handlers[eventType]) {
            throw new Error('Unsupported action event: ' + eventType);
        }
        if (handlers[eventType][name] && handlers[eventType][name] !== handler) {
            console.warn('Action handler replaced:', eventType, name);
        }
        handlers[eventType][name] = handler;
    }

    function registerAll(map, eventType = 'click') {
        Object.keys(map).forEach(name => register(name, map[name], eventType));
    }

    function listen(eventType) {
        const attribute = ATTRIBUTES[eventType];
        document.addEventListener(eventType, function(event) {
            const target = event.target instanceof Element ? event.target : event.target && event.target.parentElement;
            const el = target && target.closest('[' + attribute + ']');
            if (!el) return;
            const handler = handlers[eventType][el.getAttribute(attribute)];
            if (!handler) return;
            if (eventType === 'click' && el.tagName === 'A') {
                event.preventDefault();
            }
            handler(el, event);
        });
    }

    Object.keys(ATTRIBUTES).forEach(listen);

    return { register, registerAll };
})();
