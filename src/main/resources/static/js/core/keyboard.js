/**
 * VM Self-Service Platform - Keyboard Shortcuts
 * Provides keyboard navigation and shortcuts.
 *
 * Shortcuts navigate through ContentRouter so every page goes through the normal page
 * lifecycle. They never fire with Ctrl/Alt/Meta held (browser shortcuts such as Ctrl+R stay the
 * browser's), while typing in a field, or while a modal or slide-out is open. Escape belongs to
 * Bootstrap modals and the Slideout module, not to this file.
 */

const Keyboard = (function() {
    'use strict';

    // Sequence (keys typed one after another, no separators) -> shortcut. Labels use the side
    // menu's names, grouped as in Shortcuts.dc.html (U04-T04).
    const shortcuts = {
        'gd': { keys: ['g', 'd'], action: () => ContentRouter.navigate('dashboard'), group: 'Go to', label: 'Home' },
        'ge': { keys: ['g', 'e'], action: () => ContentRouter.navigate('my-environments'), group: 'Go to', label: 'Environments' },
        'gr': { keys: ['g', 'r'], action: () => ContentRouter.navigate('request-access'), group: 'Go to', label: 'Request access' },
        'ga': { keys: ['g', 'a'], action: () => ContentRouter.navigate('activity-logs'), group: 'Go to', label: 'My activity' },
        'r':  { keys: ['r'], action: () => ContentRouter.reload(), group: 'On any page', label: 'Refresh what you see' },
        '?':  { keys: ['?'], action: () => showShortcutsHelp(), group: 'On any page', label: 'Show this list' }
    };
    // Escape is handled by the dialogs and panels themselves; it is listed so people know it.
    const LISTED_ONLY = [{ keys: ['Esc'], group: 'On any page', label: 'Close a dialog or panel' }];
    const GROUPS = ['Go to', 'On any page'];

    const SEQUENCE_TIMEOUT_MS = 1000;

    // Key sequence tracking
    let keySequence = '';
    let sequenceTimeout = null;

    /**
     * Initialize keyboard shortcuts
     */
    function init() {
        document.addEventListener('keydown', handleKeyDown);
    }

    /**
     * Handle keydown event
     */
    function handleKeyDown(e) {
        // Browser and OS shortcuts (Ctrl+R, Cmd+F, Alt+Left...) are never ours. Shift is allowed
        // so '?' works.
        if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented || e.isComposing) {
            return;
        }
        if (isTyping(e.target) || isUnderOverlay(e.target)) {
            resetSequence();
            return;
        }
        if (e.key.length !== 1) {
            return;
        }

        clearTimeout(sequenceTimeout);
        keySequence += e.key.toLowerCase();

        const shortcut = shortcuts[keySequence];
        if (shortcut) {
            e.preventDefault();
            resetSequence();
            shortcut.action();
            return;
        }

        // Keep waiting only while the typed keys can still become a shortcut.
        const hasPartialMatch = Object.keys(shortcuts).some(s => s.startsWith(keySequence));
        if (hasPartialMatch) {
            sequenceTimeout = setTimeout(resetSequence, SEQUENCE_TIMEOUT_MS);
        } else {
            resetSequence();
        }
    }

    function resetSequence() {
        clearTimeout(sequenceTimeout);
        keySequence = '';
    }

    /**
     * Check if user is typing in an input
     */
    function isTyping(element) {
        if (!element || !element.tagName) return false;
        const tagName = element.tagName.toLowerCase();
        return tagName === 'input' ||
               tagName === 'textarea' ||
               tagName === 'select' ||
               element.isContentEditable;
    }

    /** True while a modal or slide-out is open: shortcuts must not act behind a dialog. */
    function isUnderOverlay(element) {
        if (document.querySelector('.modal.show') || document.querySelector('.slideout-panel.show')) {
            return true;
        }
        return !!(element && element.closest && element.closest('.modal, .slideout-panel'));
    }

    /** The "Keyboard shortcuts" dialog: the shortcuts by group, keys in <kbd>, and when they apply. */
    function showShortcutsHelp() {
        // Focus goes back where it was when the dialog closes (opened from the help menu: its "?").
        const active = document.activeElement;
        const returnTo = active && active.closest && active.closest('#help-menu')
            ? document.getElementById('topbar-help-btn') : active;
        const all = Object.values(shortcuts).concat(LISTED_ONLY);
        const keys = list => list.map(k => Utils.html`<kbd>${k}</kbd>`).join('<span class="shortcut-then">then</span>');
        const groups = GROUPS.map(group => `
            <section class="shortcut-group" aria-labelledby="shortcut-group-${group === 'Go to' ? 'go' : 'any'}">
                <h3 id="shortcut-group-${group === 'Go to' ? 'go' : 'any'}">${Utils.escapeHtml(group)}</h3>
                <ul>
                    ${all.filter(s => s.group === group).map(s => `
                        <li><span>${Utils.escapeHtml(s.label)}</span><span class="shortcut-keys">${keys(s.keys)}</span></li>`).join('')}
                </ul>
            </section>`).join('');

        Modals.show({
            id: 'shortcutsHelpModal',
            title: 'Keyboard shortcuts',
            body: `<div class="shortcuts-list">${groups}</div>
                <p class="shortcuts-note">Shortcuts don't fire while you type in a field or while a dialog is open. Ctrl and Alt combinations stay the browser's.</p>`,
            buttons: [],   // closed with the x or Esc, as in Shortcuts.dc.html
            onHide: () => { if (returnTo && document.body.contains(returnTo)) returnTo.focus(); }
        });
    }

    // Public API
    return {
        init,
        showShortcutsHelp
    };
})();
