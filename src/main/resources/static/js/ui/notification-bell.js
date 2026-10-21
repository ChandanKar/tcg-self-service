/**
 * NotificationBell — the top bar's notifications popover (U09-T06, Notifications.dc.html).
 * The unread count is polled by RealTime ('notificationCount', every 60 s, paused while the tab
 * is hidden); App.init() calls init() once the user is signed in. The bell opens a dialog of the
 * 8 newest notifications, read and unread: unread ones bold with a coloured dot (gold for
 * something ending, red for a failure, grey otherwise), title, one line and the time. Opening an
 * item marks it read and goes where it points; "Mark all as read" shows while something is
 * unread; the footer has Notification settings and See all activity. Tab stays inside, Escape
 * closes and gives focus back to the bell.
 */
const NotificationBell = (function () {
    'use strict';

    const LIST_SIZE = 8;
    const FOCUSABLE = 'a[href], button:not([disabled]):not([hidden])';
    let $wrapper, $btn, $dropdown, $list, $badge, $markAllBtn, $empty;
    let isOpen = false;
    let items = [];

    // ---- pure model (node tests: src/test/js/notification-bell.test.mjs) ----

    const RED = ['OPERATION_FAILED', 'LOCK_BROKEN', 'STATE_DRIFT_DETECTED', 'ACCESS_EXPIRED', 'APPROVAL_DENIED',
        'ACCESS_REQUEST_DENIED', 'BUDGET_FREEZE', 'COST_ANOMALY'];
    const GOLD = ['ACCESS_EXPIRING', 'LOCK_EXPIRING', 'LEASE_EXPIRING', 'LEASE_EXPIRED', 'ENVIRONMENT_STOP_NOTICE',
        'ACCESS_REQUESTED', 'LEASE_EXTENSION_REQUESTED', 'APPROVAL_REQUESTED', 'BUDGET_THRESHOLD', 'BUDGET_FORECAST',
        'BUDGET_ACTION_PENDING', 'AUTOMATION_RULE_SKIPPED', 'LOCK_EXPIRED', 'IDLE_AUTO_STOPPED'];

    /** The dot: red for a failure, gold for something ending or waiting on you, grey otherwise. */
    function typeTone(type) {
        if (RED.includes(type)) return 'red';
        if (GOLD.includes(type)) return 'gold';
        return 'grey';
    }

    /** "17:45" today, "Fri 14:22" within the week, "17 Oct" before that. */
    function timeText(ts, now) {
        if (!ts) return '';
        const d = new Date(ts);
        const n = now || new Date();
        const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate());
        if (d >= startOfToday) return hm;
        if (startOfToday - d < 6 * 86400000) return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${hm}`;
        return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }

    function bellLabel(count) {
        return count > 0 ? `Notifications, ${count} unread` : 'Notifications';
    }

    /**
     * Where a notification leads (E11-T10): its environment's page, My Environments for access,
     * Pending Requests for a request, the registry for a VM, Cost Management for the cost report.
     * Returns [contentType, params] for ContentRouter.navigate, or null when there is nothing to open.
     */
    function linkFor(n, isAdmin) {
        const entityType = (n.entityType || '').toUpperCase();
        if (entityType === 'ENVIRONMENT' && n.entityId) return ['environment-detail', { environmentId: n.entityId }];
        if (entityType === 'ACCESS') return ['my-environments'];
        if (entityType === 'BUDGET_ACTION' || entityType === 'BUDGET') return ['budgets']; // E20
        if (entityType === 'COST_ANOMALY') return isAdmin ? ['cost-management'] : ['my-environments']; // E21
        if (entityType === 'ACCESS_REQUEST') return ['pending-requests'];
        if (entityType === 'APPROVAL_REQUEST') return ['approvals']; // E25
        if (entityType === 'VM') return ['vm-registry'];
        if (n.type === 'WEEKLY_COST_REPORT') return ['cost-management'];
        return null;
    }

    // ---- popover ----

    function init() {
        $wrapper   = $('#notification-bell-wrapper');
        $btn       = $('#notification-bell-btn');
        $dropdown  = $('#notification-dropdown');
        $list      = $('#notification-list');
        $badge     = $('#notification-count');
        $markAllBtn = $('#notification-mark-all');
        $empty     = $('#notification-empty');
        $badge.attr('aria-hidden', 'true').removeAttr('aria-label');

        $btn.on('click', toggleDropdown);
        $markAllBtn.on('click', markAllRead);
        $dropdown.on('keydown', onKeydown);
        $list.on('click', '.notification-item', function () {
            const n = items.find(x => x.notificationId === this.getAttribute('data-id'));
            if (n) openItem(n);
        });
        $(document).on('click', '#notification-activity-link', () => closeDropdown(false));

        $(document).on('click', function (e) {
            if (isOpen && !$wrapper[0].contains(e.target)) {
                closeDropdown(false);
            }
        });
    }

    /** Background refresh of the unread badge: silent on failure (RealTime.pollGet). */
    function refreshCount() {
        if (!$badge) return;
        RealTime.pollGet(Config.API.notifications.count)
            .done(function (data) {
                const n = (data && data.count) || 0;
                if (n > 0) {
                    $badge.text(n > 99 ? '99+' : n).show();
                } else {
                    $badge.hide();
                }
                $btn.attr('aria-label', bellLabel(n));
            });
    }

    function toggleDropdown() {
        if (isOpen) {
            closeDropdown(true);
        } else {
            openDropdown();
        }
    }

    function openDropdown() {
        isOpen = true;
        $btn.attr('aria-expanded', 'true');
        $dropdown.prop('hidden', false);
        $dropdown.attr('tabindex', '-1').trigger('focus');
        loadNotifications();
    }

    /** The bell's "Notification settings" link opens My account on its Notifications tab (E29-T06). */
    $(document).off('click.notifSettings').on('click.notifSettings', '#notification-settings-link', function() {
        closeDropdown(false);
        if (typeof MyAccount !== 'undefined') MyAccount.open('notifications');
    });

    /** On a phone the list is a full-screen sheet with its own Close (U16-T04). */
    $(document).off('click.notifClose').on('click.notifClose', '#notification-close', function() {
        closeDropdown(true);
    });

    function closeDropdown(returnFocus) {
        if (!isOpen) return;
        isOpen = false;
        $btn.attr('aria-expanded', 'false');
        $dropdown.prop('hidden', true);
        if (returnFocus) $btn.trigger('focus');
    }

    /** Escape closes and returns focus to the bell; Tab and Shift+Tab stay inside the dialog. */
    function onKeydown(e) {
        if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
            closeDropdown(true);
            return;
        }
        if (e.key !== 'Tab') return;
        const focusables = $dropdown.find(FOCUSABLE).filter(':visible').toArray();
        if (!focusables.length) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && (document.activeElement === first || document.activeElement === $dropdown[0])) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    function loadNotifications() {
        ApiClient.get(`${Config.API.notifications.list}?page=0&size=${LIST_SIZE}`, { suppressGlobalError: true })
            .then(function (page) {
                items = (page && page.content) || [];
                draw();
            })
            .catch(function () {
                items = [];
                draw();
                $empty.text("Notifications didn't load. Open the bell again to retry.");
            });
    }

    function draw() {
        $list.find('.notification-item').remove();
        $empty.text('No notifications yet.').prop('hidden', items.length > 0);
        $markAllBtn.prop('hidden', !items.some(n => !n.read));
        items.forEach(n => $list.append(buildItem(n)));
    }

    function openItem(n) {
        const link = linkFor(n, typeof Auth !== 'undefined' && Auth.isAdmin());
        if (!n.read) markOneRead(n);
        if (!link) return;
        closeDropdown(false);
        ContentRouter.navigate(link[0], link[1]);
    }

    function buildItem(n) {
        const unread = !n.read;
        return Utils.html`
            <button type="button" class="notification-item${unread ? ' unread' : ''}" data-id="${n.notificationId}"
                    data-tone="${unread ? typeTone(n.type) : 'read'}">
                <span class="notification-dot" aria-hidden="true"></span>
                <span class="notification-item-body">
                    <span class="notification-item-title">${unread ? Utils.raw('<span class="visually-hidden">Unread: </span>') : ''}${n.title}</span>
                    <span class="notification-item-msg">${n.message}</span>
                    <time class="notification-item-time" datetime="${n.createdAt || ''}">${timeText(n.createdAt)}</time>
                </span>
            </button>`;
    }

    function markOneRead(n) {
        n.read = true;
        draw();
        ApiClient.patch(Config.API.notifications.markRead(n.notificationId), {}, { suppressGlobalError: true })
            .then(refreshCount)
            .catch(function () {});
    }

    function markAllRead() {
        ApiClient.patch(Config.API.notifications.markAllRead, {}, { suppressGlobalError: true })
            .then(function () {
                items.forEach(n => { n.read = true; });
                draw();
                refreshCount();
                $dropdown.trigger('focus');
            })
            .catch(function () {});
    }

    return { init, refreshCount, model: { typeTone, timeText, bellLabel, linkFor } };
})();
