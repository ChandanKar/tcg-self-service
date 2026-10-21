/**
 * VM Self-Service Platform - Menu button (U15-T01)
 * A button that opens a short list of actions, for toolbars that would otherwise be a row of buttons.
 *
 *   Menu.markup({id, label, items: [{id, text, action, href, download, hidden}]})
 *
 * The button has aria-haspopup=menu and aria-expanded; the list is role=menu with role=menuitem
 * buttons (or links with href). ArrowDown/ArrowUp/Home/End move, Escape closes and returns focus to
 * the button, Tab or a click elsewhere closes, and choosing an item closes the menu before the
 * item's own handler (data-action, an id-bound click, or the link) runs. One document listener
 * serves every menu on the page.
 */
const Menu = (function() {
    'use strict';

    const CHEVRON = '<svg width="12" height="12" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 8 5 5 5-5"></path></svg>';

    function markup(opts) {
        const { id, label, items = [] } = opts;
        const rows = items.filter(i => !i.hidden).map(i => {
            const attrs = Utils.html` role="menuitem" class="menu-item" tabindex="-1"${Utils.raw(i.id ? Utils.html` id="${i.id}"` : '')}${Utils.raw(i.action ? Utils.html` data-action="${i.action}"` : '')}`;
            return i.href
                ? Utils.html`<a${Utils.raw(attrs)} href="${i.href}"${Utils.raw(i.download ? Utils.html` download="${i.download}"` : '')}>${i.text}</a>`
                : Utils.html`<button type="button"${Utils.raw(attrs)}>${i.text}</button>`;
        }).join('');
        return Utils.html`<div class="menu-wrap"><button type="button" class="btn btn-secondary btn-sm menu-button" id="${id}-btn"
                aria-haspopup="menu" aria-expanded="false" aria-controls="${id}">${label} ${Utils.raw(CHEVRON)}</button>
            <div class="menu-list" role="menu" id="${id}" aria-label="${label}" hidden>${Utils.raw(rows)}</div></div>`;
    }

    function items(list) {
        return Array.from(list.querySelectorAll('[role="menuitem"]'));
    }

    function close(wrap, focusButton) {
        if (!wrap) return;
        const button = wrap.querySelector('.menu-button');
        const list = wrap.querySelector('[role="menu"]');
        if (!list || list.hidden) return;
        list.hidden = true;
        button.setAttribute('aria-expanded', 'false');
        if (focusButton) button.focus();
    }

    function closeAll(except) {
        document.querySelectorAll('.menu-wrap').forEach(w => { if (w !== except) close(w, false); });
    }

    function open(wrap, focusIndex) {
        closeAll(wrap);
        const button = wrap.querySelector('.menu-button');
        const list = wrap.querySelector('[role="menu"]');
        list.hidden = false;
        button.setAttribute('aria-expanded', 'true');
        const all = items(list);
        if (focusIndex !== null && all.length) all[(focusIndex + all.length) % all.length].focus();
    }

    function listen() {
        document.addEventListener('click', e => {
            const target = e.target instanceof Element ? e.target : null;
            const button = target && target.closest('.menu-button');
            if (button) {
                const wrap = button.closest('.menu-wrap');
                if (button.getAttribute('aria-expanded') === 'true') close(wrap, false);
                else open(wrap, null);
                return;
            }
            const item = target && target.closest('.menu-wrap [role="menuitem"]');
            if (item) {
                close(item.closest('.menu-wrap'), false);
                return;
            }
            if (!target || !target.closest('.menu-wrap')) closeAll(null);
        });
        document.addEventListener('keydown', e => {
            const target = e.target instanceof Element ? e.target : null;
            const wrap = target && target.closest('.menu-wrap');
            if (!wrap) return;
            const list = wrap.querySelector('[role="menu"]');
            if (target.classList.contains('menu-button')) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                    e.preventDefault();
                    open(wrap, e.key === 'ArrowDown' ? 0 : -1);
                } else if (e.key === 'Escape') {
                    close(wrap, true);
                }
                return;
            }
            const all = items(list);
            const i = all.indexOf(target);
            if (i === -1) return;
            let next = null;
            if (e.key === 'ArrowDown') next = all[(i + 1) % all.length];
            else if (e.key === 'ArrowUp') next = all[(i - 1 + all.length) % all.length];
            else if (e.key === 'Home') next = all[0];
            else if (e.key === 'End') next = all[all.length - 1];
            else if (e.key === 'Escape') { e.preventDefault(); close(wrap, true); return; }
            else if (e.key === 'Tab') { close(wrap, false); return; }
            if (next) {
                e.preventDefault();
                next.focus();
            }
        });
    }

    if (typeof document !== 'undefined') listen();

    return { markup, closeAll };
})();

if (typeof window !== 'undefined') window.Menu = Menu;
