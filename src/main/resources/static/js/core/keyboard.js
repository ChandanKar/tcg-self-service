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

    // Sequence (keys typed one after another, no separators) -> shortcut
    const shortcuts = {
        'gd': { keys: ['g', 'd'], action: () => ContentRouter.navigate('dashboard'), description: 'Go to Dashboard' },
        'ge': { keys: ['g', 'e'], action: () => ContentRouter.navigate('my-environments'), description: 'Go to My Environments' },
        'gr': { keys: ['g', 'r'], action: () => ContentRouter.navigate('request-access'), description: 'Go to Request Access' },
        'ga': { keys: ['g', 'a'], action: () => ContentRouter.navigate('activity-logs'), description: 'Go to Activity Logs' },
        'r':  { keys: ['r'], action: () => ContentRouter.reload(), description: 'Refresh current view' },
        '?':  { keys: ['?'], action: () => showShortcutsHelp(), description: 'Show shortcuts help' }
    };

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

    /**
     * Show shortcuts help modal
     */
    function showShortcutsHelp() {
        const shortcutsList = Object.values(shortcuts)
            .filter(config => config.keys[0] !== '?')
            .map(config => `
                <tr>
                    <td>${config.keys.map(k => `<kbd>${Utils.escapeHtml(k)}</kbd>`).join(' then ')}</td>
                    <td>${Utils.escapeHtml(config.description)}</td>
                </tr>
            `).join('');

        Modals.show({
            id: 'shortcutsHelpModal',
            title: 'Keyboard Shortcuts',
            body: `
                <table class="table table-sm shortcuts-table">
                    <thead>
                        <tr>
                            <th>Shortcut</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${shortcutsList}
                    </tbody>
                </table>
                <p class="text-muted mt-3">
                    <small>Press <kbd>?</kbd> anytime to show this help. Press <kbd>Esc</kbd> to close dialogs and panels.</small>
                </p>
            `,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true }
            ]
        });
    }

    // Public API
    return {
        init,
        showShortcutsHelp
    };
})();
