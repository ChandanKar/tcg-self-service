/**
 * VM Self-Service Platform - Help menu (U04-T04)
 * The top bar's "?" opens a small menu: "Help for this page" (when the page on screen has a help
 * topic), "All help topics" and "Keyboard shortcuts". The menu is rebuilt each time it opens, so
 * "Help for this page" follows the current page. Keyboard: arrows move, Escape closes and returns
 * focus to the "?", Tab or a click outside closes (the same pattern as the account menu).
 */
const HelpMenu = (function() {
    'use strict';

    const BUTTON = '#topbar-help-btn';
    const MENU = '#help-menu';

    function init() {
        $(document).on('click', BUTTON, function(e) {
            e.stopPropagation();
            if (isOpen()) close(); else open();
        });
        $(document).on('keydown', MENU, onKeydown);
        $(document).on('click', function(e) {
            if (isOpen() && !$(e.target).closest('.help-menu-wrapper').length) close();
        });
        $(document).on('click', `${MENU} [data-help-menu="shortcuts"]`, function(e) {
            e.preventDefault();
            close(false);
            if (typeof Keyboard !== 'undefined') Keyboard.showShortcutsHelp();
        });
        $(document).on('click', `${MENU} [data-help-menu="ask"]`, function(e) {
            e.preventDefault();
            close(false);
            if (typeof AssistantPanel !== 'undefined') AssistantPanel.open();
        });
        $(document).on('click', `${MENU} a[role="menuitem"]`, function() { close(false); });
    }

    function isOpen() {
        const menu = document.querySelector(MENU);
        return !!menu && !menu.hidden;
    }

    /** The current page's help topic, if it has one. */
    function pageTopic() {
        const page = typeof ContentRouter !== 'undefined' && ContentRouter.current ? ContentRouter.current() : null;
        return page && typeof HelpContent !== 'undefined' ? HelpContent.PAGE_TOPICS[page] || null : null;
    }

    function items() {
        const topic = pageTopic();
        const forPage = topic
            ? Utils.html`<a href="#/help/${topic}" class="dropdown-item" role="menuitem" data-help-menu="page">
                    <i class="far fa-file-lines" aria-hidden="true"></i><span>Help for this page</span></a>`
            : '';
        return forPage + `
            <a href="#/help" class="dropdown-item" role="menuitem" data-help-menu="all">
                <i class="fas fa-book-open" aria-hidden="true"></i><span>All help topics</span></a>
            <button type="button" class="dropdown-item" role="menuitem" data-help-menu="shortcuts">
                <i class="far fa-keyboard" aria-hidden="true"></i><span>Keyboard shortcuts</span><kbd class="ms-auto">?</kbd></button>`
            + (typeof AssistantPanel !== 'undefined' && AssistantPanel.isEnabled()
                ? `<button type="button" class="dropdown-item" role="menuitem" data-help-menu="ask">
                    <i class="far fa-comment-dots" aria-hidden="true"></i><span>Ask about cost and environments</span></button>`
                : '');
    }

    function open() {
        const menu = document.querySelector(MENU);
        if (!menu) return;
        menu.innerHTML = items();
        menu.hidden = false;
        $(BUTTON).attr('aria-expanded', 'true');
        const first = menu.querySelector('[role="menuitem"]');
        if (first) first.focus();
    }

    /** Close the menu; give focus back to the "?" unless focus is moving on (a link or the dialog). */
    function close(returnFocus = true) {
        const menu = document.querySelector(MENU);
        if (!menu || menu.hidden) return;
        menu.hidden = true;
        $(BUTTON).attr('aria-expanded', 'false');
        if (returnFocus) $(BUTTON).trigger('focus');
    }

    function onKeydown(e) {
        const list = Array.from(this.querySelectorAll('[role="menuitem"]'));
        const index = list.indexOf(document.activeElement);
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const step = e.key === 'ArrowDown' ? 1 : -1;
            const next = index === -1 ? 0 : (index + step + list.length) % list.length;
            if (list[next]) list[next].focus();
        } else if (e.key === 'Home' || e.key === 'End') {
            e.preventDefault();
            const target = e.key === 'Home' ? list[0] : list[list.length - 1];
            if (target) target.focus();
        } else if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
            close();
        } else if (e.key === 'Tab') {
            close(false);
        }
    }

    return { init, open, close, isOpen };
})();
