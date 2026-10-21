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

    /** How long a toast stays (States.dc.html): 6 s, errors until closed (U17-T03). */
    const STAY_MS = 6000;

    /** Auto-dismiss that pauses while the toast is hovered or focused (WCAG 2.2.1) and restarts after. */
    function autoDismiss($toast, id, duration) {
        if (!(duration > 0)) return;
        let timer = setTimeout(() => dismiss(id), duration);
        const pause = () => { clearTimeout(timer); timer = null; };
        const resume = () => { if (!timer) timer = setTimeout(() => dismiss(id), duration); };
        $toast.on('mouseenter', pause).on('focusin', pause).on('mouseleave', resume).on('focusout', resume);
    }

    /** The toast's one optional action: {label, onClick} (a button that also closes it) or {label, href}. */
    function actionElement(action, id) {
        if (!action || !action.label) return null;
        if (action.href) {
            return $('<a class="toast-action"></a>').attr('href', action.href).text(String(action.label));
        }
        return $('<button type="button" class="toast-action"></button>').text(String(action.label))
            .on('click', function() {
                dismiss(id);
                if (typeof action.onClick === 'function') action.onClick();
            });
    }

    /**
     * Show a notification: what happened, in one sentence, with at most one action (U17-T03).
     * @param {string} message - Shown as text unless options.html is true
     * @param {string} type - success, error (or danger), warning, info
     * @param {number} [duration] - ms before it goes; default 6000, and errors stay until closed. 0 = stay.
     * @param {object} [options] - { html: true } renders message (and title) as HTML (escape any data in it);
     *        { title } a bold first line; { action: {label, onClick | href} } one link, e.g. 'Watch' or 'Details'
     */
    function show(message, type = 'info', duration, options = {}) {
        if (!$container) init();

        const kind = normaliseType(type);
        const id = 'notif-' + (++seq);
        const stay = duration === undefined || duration === null ? (kind === 'error' ? 0 : STAY_MS) : duration;

        const $body = $('<div class="toast-body"></div>');
        if (options.title) $body.append(setContent($('<strong class="toast-title"></strong>'), options.title, options.html === true));
        $body.append(setContent($('<p class="toast-message"></p>'), message, options.html === true));
        const $action = actionElement(options.action, id);
        if ($action) $body.append($('<div class="toast-actions"></div>').append($action));

        // Errors and warnings interrupt a screen reader; the rest wait politely (E13-T08)
        const $toast = $('<div class="notification-toast"></div>')
            .attr('role', kind === 'error' || kind === 'warning' ? 'alert' : 'status')
            .attr('id', id)
            .addClass('toast-' + kind)
            .append($('<i class="fas toast-icon" aria-hidden="true"></i>').addClass(ICONS[kind]))
            .append($body)
            .append(closeButton());

        $container.append($toast);
        autoDismiss($toast, id, stay);
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

    function success(message, duration, options = {}) {
        return show(message, 'success', duration, options);
    }

    /** Errors stay until closed unless a duration is given. */
    function error(message, duration, options = {}) {
        return show(message, 'error', duration, options);
    }

    function warning(message, duration, options = {}) {
        return show(message, 'warning', duration, options);
    }

    function info(message, duration, options = {}) {
        return show(message, 'info', duration, options);
    }

    /**
     * An error with a title and one action: Try again (retryAction) or Help (helpLink). Stays until closed.
     * @param {string} message - Text unless options.html is true
     * @param {object} options - { title = 'Error', retryAction, helpLink, duration = 0, html }
     */
    function showError(message, options = {}) {
        const { title = 'Error', retryAction = null, helpLink = null, duration = 0, html = false } = options;
        const action = retryAction ? { label: 'Try again', onClick: retryAction }
            : helpLink ? { label: 'Help', href: helpLink } : null;
        return show(message, 'error', duration, { title, html, action });
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

