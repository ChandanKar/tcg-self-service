/**
 * VM Self-Service Platform - Notifications UI
 * Toast notifications and alerts
 */

const Notifications = (function() {
    'use strict';

    // Container for notifications
    let $container;
    let seq = 0;

    /**
     * Initialize notifications
     */
    function init() {
        // Create container if it doesn't exist
        if ($('#notification-container').length === 0) {
            $('body').append('<div id="notification-container"></div>');
        }
        $container = $('#notification-container');
        $container.off('click.notifications').on('click.notifications', '.toast-close', function() {
            dismiss($(this).closest('.notification-toast').attr('id'));
        });
    }

    const ICONS = {
        success: 'fa-check-circle',
        error: 'fa-exclamation-circle',
        warning: 'fa-exclamation-triangle',
        info: 'fa-info-circle'
    };

    function normaliseType(type) {
        if (type === 'danger') return 'error';
        return ICONS[type] ? type : 'info';
    }

    /** Set an element's content as text, or as HTML only when the caller opted in. */
    function setContent($el, content, asHtml) {
        if (asHtml) {
            $el.html(content);
        } else {
            $el.text(content === null || content === undefined ? '' : String(content));
        }
        return $el;
    }

    function closeButton() {
        return $('<button type="button" class="toast-close" aria-label="Dismiss"><i class="fas fa-times" aria-hidden="true"></i></button>');
    }

    /**
     * Show a notification
     * @param {string} message - Notification message, shown as text unless options.html is true
     * @param {string} type - Type: success, error (or danger), warning, info
     * @param {number} duration - Auto-dismiss duration in ms (0 = no auto-dismiss)
     * @param {object} options - { html: true } to render message as HTML (escape any data in it)
     */
    function show(message, type = 'info', duration = 3000, options = {}) {
        if (!$container) init();

        const kind = normaliseType(type);
        const id = 'notif-' + (++seq);

        const $toast = $('<div class="notification-toast" role="status"></div>')
            .attr('id', id)
            .addClass('toast-' + kind)
            .append($('<i class="fas toast-icon" aria-hidden="true"></i>').addClass(ICONS[kind]))
            .append(setContent($('<p class="toast-message"></p>'), message, options.html === true))
            .append(closeButton());

        $container.append($toast);

        // Auto-dismiss
        if (duration > 0) {
            setTimeout(() => dismiss(id), duration);
        }
        return id;
    }

    /**
     * Dismiss a notification
     * @param {string} id - Notification element ID
     */
    function dismiss(id) {
        if (!id) return;
        const $notif = $(document.getElementById(id));
        $notif.addClass('toast-dismissing');
        setTimeout(() => $notif.remove(), 250);
    }

    /**
     * Show success notification
     */
    function success(message, duration = 3000, options = {}) {
        return show(message, 'success', duration, options);
    }

    /**
     * Show error notification
     */
    function error(message, duration = 5000, options = {}) {
        return show(message, 'error', duration, options);
    }

    /**
     * Show warning notification
     */
    function warning(message, duration = 4000, options = {}) {
        return show(message, 'warning', duration, options);
    }

    /**
     * Show info notification
     */
    function info(message, duration = 3000, options = {}) {
        return show(message, 'info', duration, options);
    }

    /**
     * Show error notification with optional retry action (TASK-029)
     * @param {string} message - Error message (text unless options.html is true)
     * @param {object} options - Options
     * @param {string} options.title - Title (default: 'Error'), text unless options.html is true
     * @param {function} options.retryAction - Retry callback function
     * @param {string} options.helpLink - Link to help page
     * @param {number} options.duration - Duration in ms (default: 8000)
     * @param {boolean} options.html - Render title and message as HTML
     */
    function showError(message, options = {}) {
        const {
            title = 'Error',
            retryAction = null,
            helpLink = null,
            duration = 8000,
            html = false
        } = options;

        if (!$container) init();

        const id = 'error-' + (++seq);

        const $body = $('<div class="toast-body"></div>')
            .append(setContent($('<strong class="toast-title"></strong>'), title, html === true))
            .append(setContent($('<p class="toast-message"></p>'), message, html === true));

        if (retryAction || helpLink) {
            const $actions = $('<div class="toast-actions"></div>');
            if (retryAction) {
                $('<button type="button" class="btn btn-sm btn-outline-danger btn-ghost retry-btn"><i class="fas fa-sync me-1" aria-hidden="true"></i> Retry</button>')
                    .on('click', function() {
                        dismiss(id);
                        retryAction();
                    })
                    .appendTo($actions);
            }
            if (helpLink) {
                $('<a class="btn btn-sm btn-ghost" target="_blank" rel="noopener"><i class="fas fa-question-circle me-1" aria-hidden="true"></i> Help</a>')
                    .attr('href', helpLink)
                    .appendTo($actions);
            }
            $body.append($actions);
        }

        const $toast = $('<div class="notification-toast toast-error" role="alert"></div>')
            .attr('id', id)
            .append('<i class="fas fa-exclamation-circle toast-icon" aria-hidden="true"></i>')
            .append($body)
            .append(closeButton());

        $container.append($toast);

        // Auto-dismiss
        if (duration > 0) {
            setTimeout(() => dismiss(id), duration);
        }
        return id;
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

