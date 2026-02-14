/**
 * VM Self-Service Platform - Keyboard Shortcuts
 * Provides keyboard navigation and shortcuts
 */

const Keyboard = (function() {
    'use strict';

    // Shortcut definitions
    const shortcuts = {
        'g d': { action: () => Dashboard.load(), description: 'Go to Dashboard' },
        'g e': { action: () => Environments.loadList(), description: 'Go to Environments' },
        'g r': { action: () => AccessRequests.loadRequestAccessPage(), description: 'Go to Request Access' },
        'g a': { action: () => AuditLogs.loadMyActivityLogs(), description: 'Go to Activity Logs' },
        'r': { action: () => refreshCurrentView(), description: 'Refresh current view' },
        '?': { action: () => showShortcutsHelp(), description: 'Show shortcuts help' },
        'Escape': { action: () => closeActiveModal(), description: 'Close modal/slideout' }
    };

    // Key sequence tracking
    let keySequence = '';
    let sequenceTimeout = null;

    /**
     * Initialize keyboard shortcuts
     */
    function init() {
        document.addEventListener('keydown', handleKeyDown);
        console.log('Keyboard shortcuts initialized');
    }

    /**
     * Handle keydown event
     */
    function handleKeyDown(e) {
        // Ignore if typing in input/textarea
        if (isTyping(e.target)) {
            return;
        }

        // Handle Escape separately
        if (e.key === 'Escape') {
            closeActiveModal();
            return;
        }

        // Track key sequence for multi-key shortcuts
        clearTimeout(sequenceTimeout);

        if (e.key.length === 1) {
            keySequence += e.key.toLowerCase();

            // Check for matching shortcut
            const matchingShortcut = shortcuts[keySequence];
            if (matchingShortcut) {
                e.preventDefault();
                matchingShortcut.action();
                keySequence = '';
                return;
            }

            // Check if any shortcut starts with current sequence
            const hasPartialMatch = Object.keys(shortcuts).some(s => s.startsWith(keySequence));

            if (!hasPartialMatch) {
                keySequence = '';
            } else {
                // Reset after delay if no completion
                sequenceTimeout = setTimeout(() => {
                    keySequence = '';
                }, 1000);
            }
        }
    }

    /**
     * Check if user is typing in an input
     */
    function isTyping(element) {
        const tagName = element.tagName.toLowerCase();
        return tagName === 'input' ||
               tagName === 'textarea' ||
               tagName === 'select' ||
               element.isContentEditable;
    }

    /**
     * Refresh current view
     */
    function refreshCurrentView() {
        const $dashboardView = $('#dashboard-view');
        if ($dashboardView.length) {
            Dashboard.refresh();
        }
        // Add other view refreshes as needed
    }

    /**
     * Close active modal or slideout
     */
    function closeActiveModal() {
        // Close any open Bootstrap modal
        const openModal = document.querySelector('.modal.show');
        if (openModal) {
            const modal = bootstrap.Modal.getInstance(openModal);
            if (modal) {
                modal.hide();
                return;
            }
        }

        // Close slideout
        if (typeof Slideout !== 'undefined') {
            Slideout.close();
        }
    }

    /**
     * Show shortcuts help modal
     */
    function showShortcutsHelp() {
        const shortcutsList = Object.entries(shortcuts)
            .filter(([key]) => key !== '?')
            .map(([key, config]) => `
                <tr>
                    <td><kbd>${key.split(' ').map(k => `<span class="key">${k}</span>`).join(' ')}</kbd></td>
                    <td>${config.description}</td>
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
                    <small>Press <kbd>?</kbd> anytime to show this help</small>
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

