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
        warning,
        info,
        clearAll
    };
})();

