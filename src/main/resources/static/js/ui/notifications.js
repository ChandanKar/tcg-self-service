/**
 * VM Self-Service Platform - Notifications UI
 * Toast notifications and alerts
 */

const Notifications = (function() {
    'use strict';

    // Container for notifications
    let $container;

    /**
     * Initialize notifications
     */
    function init() {
        // Create container if it doesn't exist
        if ($('#notification-container').length === 0) {
            $('body').append('<div id="notification-container"></div>');
        }
        $container = $('#notification-container');
    }

    const ICONS = {
        success: 'fa-check-circle',
        error: 'fa-exclamation-circle',
        warning: 'fa-exclamation-triangle',
        info: 'fa-info-circle'
    };

    /**
     * Show a notification
     * @param {string} message - Notification message
     * @param {string} type - Type: success, error, warning, info
     * @param {number} duration - Auto-dismiss duration in ms (0 = no auto-dismiss)
     */
    function show(message, type = 'info', duration = 3000) {
        if (!$container) init();

        const id = 'notif-' + Date.now();

        const html = `
            <div id="${id}" class="notification-toast toast-${type}" role="status">
                <i class="fas ${ICONS[type]} toast-icon"></i>
                <p class="toast-message">${message}</p>
                <button class="toast-close" onclick="Notifications.dismiss('${id}')" aria-label="Dismiss">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `;

        $container.append(html);

        // Auto-dismiss
        if (duration > 0) {
            setTimeout(() => dismiss(id), duration);
        }
    }

    /**
     * Dismiss a notification
     * @param {string} id - Notification element ID
     */
    function dismiss(id) {
        const $notif = $(`#${id}`);
        $notif.addClass('toast-dismissing');
        setTimeout(() => $notif.remove(), 250);
    }

    /**
     * Show success notification
     */
    function success(message, duration = 3000) {
        show(message, 'success', duration);
    }

    /**
     * Show error notification
     */
    function error(message, duration = 5000) {
        show(message, 'error', duration);
    }

    /**
     * Show warning notification
     */
    function warning(message, duration = 4000) {
        show(message, 'warning', duration);
    }

    /**
     * Show info notification
     */
    function info(message, duration = 3000) {
        show(message, 'info', duration);
    }

    /**
     * Show error notification with optional retry action (TASK-029)
     * @param {string} message - Error message
     * @param {object} options - Options
     * @param {string} options.title - Title (default: 'Error')
     * @param {function} options.retryAction - Retry callback function
     * @param {string} options.helpLink - Link to help page
     * @param {number} options.duration - Duration in ms (default: 8000)
     */
    function showError(message, options = {}) {
        const {
            title = 'Error',
            retryAction = null,
            helpLink = null,
            duration = 8000
        } = options;

        if (!$container) init();

        const id = 'error-' + Date.now();

        let actionsHtml = '';
        if (retryAction || helpLink) {
            actionsHtml = '<div class="toast-actions">';
            if (retryAction) {
                actionsHtml += `
                    <button class="btn btn-sm btn-outline-danger btn-ghost retry-btn" data-id="${id}">
                        <i class="fas fa-sync me-1"></i> Retry
                    </button>
                `;
            }
            if (helpLink) {
                actionsHtml += `
                    <a href="${helpLink}" class="btn btn-sm btn-ghost" target="_blank">
                        <i class="fas fa-question-circle me-1"></i> Help
                    </a>
                `;
            }
            actionsHtml += '</div>';
        }

        const html = `
            <div id="${id}" class="notification-toast toast-error" role="alert">
                <i class="fas fa-exclamation-circle toast-icon"></i>
                <div class="toast-body">
                    <strong class="toast-title">${title}</strong>
                    <p class="toast-message">${message}</p>
                    ${actionsHtml}
                </div>
                <button class="toast-close" onclick="Notifications.dismiss('${id}')" aria-label="Dismiss">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `;

        $container.append(html);

        // Bind retry action
        if (retryAction) {
            $(`#${id} .retry-btn`).on('click', function() {
                dismiss(id);
                retryAction();
            });
        }

        // Auto-dismiss
        if (duration > 0) {
            setTimeout(() => dismiss(id), duration);
        }
    }

    /**
     * Clear all notifications
     */
    function clearAll() {
        if ($container) {
            $container.empty();
        }
    }

    return {
        init,
        show,
        dismiss,
        success,
        error,
        showError,
        warning,
        info,
        clearAll
    };
})();

