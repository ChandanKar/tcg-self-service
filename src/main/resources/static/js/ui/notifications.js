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
            $('body').append(`
                <div id="notification-container"
                     style="position: fixed; top: 80px; right: 20px; z-index: 9999; max-width: 350px;">
                </div>
            `);
        }
        $container = $('#notification-container');
    }

    /**
     * Show a notification
     * @param {string} message - Notification message
     * @param {string} type - Type: success, error, warning, info
     * @param {number} duration - Auto-dismiss duration in ms (0 = no auto-dismiss)
     */
    function show(message, type = 'info', duration = 3000) {
        if (!$container) init();

        const icons = {
            success: 'fa-check-circle',
            error: 'fa-exclamation-circle',
            warning: 'fa-exclamation-triangle',
            info: 'fa-info-circle'
        };

        const bgColors = {
            success: '#10b981',
            error: '#ef4444',
            warning: '#f59e0b',
            info: '#3b82f6'
        };

        const id = 'notif-' + Date.now();

        const html = `
            <div id="${id}" class="notification-toast"
                 style="background: ${bgColors[type]}; color: white; padding: 1rem;
                        border-radius: 8px; margin-bottom: 10px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);
                        display: flex; align-items: center; gap: 0.75rem; animation: slideIn 0.3s ease;">
                <i class="fas ${icons[type]}"></i>
                <span style="flex: 1;">${message}</span>
                <button onclick="Notifications.dismiss('${id}')"
                        style="background: none; border: none; color: white; cursor: pointer; padding: 0;">
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
        $notif.css('animation', 'slideOut 0.3s ease');
        setTimeout(() => $notif.remove(), 300);
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
            actionsHtml = '<div style="margin-top: 0.5rem; display: flex; gap: 0.5rem;">';
            if (retryAction) {
                actionsHtml += `
                    <button class="btn btn-sm btn-light retry-btn" data-id="${id}">
                        <i class="fas fa-sync me-1"></i> Retry
                    </button>
                `;
            }
            if (helpLink) {
                actionsHtml += `
                    <a href="${helpLink}" class="btn btn-sm btn-outline-light" target="_blank">
                        <i class="fas fa-question-circle me-1"></i> Help
                    </a>
                `;
            }
            actionsHtml += '</div>';
        }

        const html = `
            <div id="${id}" class="notification-toast" role="alert"
                 style="background: #dc2626; color: white; padding: 1rem;
                        border-radius: 8px; margin-bottom: 10px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.15);
                        animation: slideIn 0.3s ease;">
                <div style="display: flex; align-items: flex-start; gap: 0.75rem;">
                    <i class="fas fa-exclamation-circle" style="margin-top: 2px;"></i>
                    <div style="flex: 1;">
                        <strong>${title}</strong>
                        <p style="margin: 0.25rem 0 0 0; font-size: 0.9rem;">${message}</p>
                        ${actionsHtml}
                    </div>
                    <button onclick="Notifications.dismiss('${id}')"
                            style="background: none; border: none; color: white;
                                   cursor: pointer; padding: 0; opacity: 0.8;"
                            aria-label="Dismiss">
                        <i class="fas fa-times"></i>
                    </button>
                </div>
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

    // Add CSS animation styles
    $(document).ready(function() {
        if ($('#notification-styles').length === 0) {
            $('head').append(`
                <style id="notification-styles">
                    @keyframes slideIn {
                        from { transform: translateX(100%); opacity: 0; }
                        to { transform: translateX(0); opacity: 1; }
                    }
                    @keyframes slideOut {
                        from { transform: translateX(0); opacity: 1; }
                        to { transform: translateX(100%); opacity: 0; }
                    }
                </style>
            `);
        }
    });

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

