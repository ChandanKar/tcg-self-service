/**
 * VM Self-Service Platform - Tabs (U04-T02)
 * One accessible tab bar for the new screens, instead of each page building its own nav-tabs.
 *
 *   Tabs.markup({id, label, tabs: [{key, label, count}], active})  role=tablist of role=tab buttons
 *   Tabs.panel({id, key, active}, html)                            the matching role=tabpanel
 *   Tabs.bind(root, onChange)                                      click and ArrowLeft/Right, Home/End
 *
 * Roving tabindex: only the selected tab is in the Tab order; the arrows move focus and select.
 * The underline look comes from .tabs (css/layout/content.css).
 */
const Tabs = (function() {
    'use strict';

    const tabId = (id, key) => `${id}-tab-${key}`;
    const panelId = (id, key) => `${id}-panel-${key}`;

    function markup(opts) {
        const { id, label, tabs = [], active } = opts;
        const current = tabs.some(t => t.key === active) ? active : (tabs[0] && tabs[0].key);
        const buttons = tabs.map(t => {
            const selected = t.key === current;
            const count = t.count == null ? '' : Utils.html`<span class="tab-count">${t.count}</span>`;
            return Utils.html`<button type="button" role="tab" class="tab" id="${tabId(id, t.key)}" data-tab="${t.key}"
                aria-selected="${String(selected)}" aria-controls="${panelId(id, t.key)}" tabindex="${selected ? '0' : '-1'}">`
                + Utils.escapeHtml(t.label) + count + '</button>';
        }).join('');
        return Utils.html`<div class="tabs" role="tablist" id="${id}" aria-label="${label}">` + buttons + '</div>';
    }

    function panel(opts, html) {
        const { id, key, active } = opts;
        return Utils.html`<div role="tabpanel" class="tab-panel" id="${panelId(id, key)}" aria-labelledby="${tabId(id, key)}" tabindex="0"`
            + (key === active ? '' : ' hidden') + '>' + (html || '') + '</div>';
    }

    /** Select a tab by key inside a tablist element: ARIA state, tabindex and the matching panel. */
    function select(list, key) {
        const tabs = Array.from(list.querySelectorAll('[role="tab"]'));
        tabs.forEach(tab => {
            const on = tab.dataset.tab === key;
            tab.setAttribute('aria-selected', String(on));
            tab.tabIndex = on ? 0 : -1;
            const panelEl = document.getElementById(tab.getAttribute('aria-controls'));
            if (panelEl) panelEl.hidden = !on;
        });
    }

    /**
     * Wire every tablist inside root (or root itself): click or arrow keys select a tab and call
     * onChange(key, tablist). Safe to call again after re-rendering root.
     */
    function bind(root, onChange) {
        const el = root && root.jquery ? root[0] : root;
        if (!el) return;
        const lists = el.matches && el.matches('[role="tablist"]') ? [el] : Array.from(el.querySelectorAll('[role="tablist"]'));
        lists.forEach(list => {
            if (list.dataset.tabsBound) return;
            list.dataset.tabsBound = '1';
            const choose = tab => {
                if (!tab || tab.getAttribute('aria-selected') === 'true') return;
                select(list, tab.dataset.tab);
                if (typeof onChange === 'function') onChange(tab.dataset.tab, list);
            };
            list.addEventListener('click', e => {
                const tab = e.target.closest('[role="tab"]');
                if (tab && list.contains(tab)) choose(tab);
            });
            list.addEventListener('keydown', e => {
                const tabs = Array.from(list.querySelectorAll('[role="tab"]'));
                const i = tabs.indexOf(document.activeElement);
                if (i === -1) return;
                let next = null;
                if (e.key === 'ArrowRight') next = tabs[(i + 1) % tabs.length];
                else if (e.key === 'ArrowLeft') next = tabs[(i - 1 + tabs.length) % tabs.length];
                else if (e.key === 'Home') next = tabs[0];
                else if (e.key === 'End') next = tabs[tabs.length - 1];
                if (!next) return;
                e.preventDefault();
                next.focus();
                choose(next);
            });
        });
    }

    return { markup, panel, bind, select };
})();
