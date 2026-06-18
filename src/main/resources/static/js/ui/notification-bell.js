/**
 * NotificationBell — navbar dropdown with read/unread in-app notifications.
 * Polls /api/v1/notifications/count every 60 s; opens a dropdown on click.
 */
const NotificationBell = (function () {
    'use strict';

    const POLL_MS = 60000;

    let $wrapper, $btn, $dropdown, $list, $badge, $markAllBtn, $empty;
    let pollTimer = null;
    let isOpen = false;

    function init() {
        $wrapper   = $('#notification-bell-wrapper');
        $btn       = $('#notification-bell-btn');
        $dropdown  = $('#notification-dropdown');
        $list      = $('#notification-list');
        $badge     = $('#notification-count');
        $markAllBtn = $('#notification-mark-all');
        $empty     = $('#notification-empty');

        $btn.on('click', toggleDropdown);
        $markAllBtn.on('click', markAllRead);

        $(document).on('click', function (e) {
            if (isOpen && !$wrapper[0].contains(e.target)) {
                closeDropdown();
            }
        });

        refreshCount();
        pollTimer = setInterval(refreshCount, POLL_MS);
    }

    function refreshCount() {
        ApiClient.get(Config.API.notifications.count)
            .then(function (data) {
                const n = data.count || 0;
                if (n > 0) {
                    $badge.text(n > 99 ? '99+' : n).show();
                } else {
                    $badge.hide();
                }
            })
            .catch(function () { /* silently ignore — background poll */ });
    }

    function toggleDropdown() {
        if (isOpen) {
            closeDropdown();
        } else {
            openDropdown();
        }
    }

    function openDropdown() {
        isOpen = true;
        $btn.attr('aria-expanded', 'true');
        $dropdown.show();
        loadNotifications();
    }

    function closeDropdown() {
        isOpen = false;
        $btn.attr('aria-expanded', 'false');
        $dropdown.hide();
    }

    function loadNotifications() {
        $list.find('.notification-item').remove();
        $empty.show();
        $markAllBtn.hide();

        ApiClient.get(Config.API.notifications.list + '?page=0&size=15')
            .then(function (page) {
                const items = page.content || [];
                if (items.length === 0) {
                    $empty.show();
                    return;
                }
                $empty.hide();

                const hasUnread = items.some(function (n) { return !n.read; });
                if (hasUnread) $markAllBtn.show();

                items.forEach(function (n) {
                    $list.append(buildItem(n));
                });
            })
            .catch(function () {
                $empty.show();
            });
    }

    function buildItem(n) {
        const ago    = Utils.timeAgo ? Utils.timeAgo(n.createdAt) : formatAgo(n.createdAt);
        const unread = !n.read;
        const icon   = typeIcon(n.type);

        const $item = $(`
            <div class="notification-item ${unread ? 'unread' : ''}"
                 data-id="${n.notificationId}" role="menuitem">
                <div class="notification-item-icon">
                    <i class="fas ${icon}"></i>
                </div>
                <div class="notification-item-body">
                    <div class="notification-item-title">${escHtml(n.title)}</div>
                    <div class="notification-item-msg">${escHtml(n.message)}</div>
                    <div class="notification-item-time">${ago}</div>
                </div>
                ${unread ? '<button class="notification-read-btn" title="Mark as read"><i class="fas fa-check"></i></button>' : ''}
            </div>
        `);

        if (unread) {
            $item.find('.notification-read-btn').on('click', function (e) {
                e.stopPropagation();
                markOneRead(n.notificationId, $item);
            });
        }

        return $item;
    }

    function markOneRead(id, $item) {
        ApiClient.patch(Config.API.notifications.markRead(id), {})
            .then(function () {
                $item.removeClass('unread').find('.notification-read-btn').remove();
                refreshCount();
                if ($list.find('.unread').length === 0) $markAllBtn.hide();
            })
            .catch(function () {});
    }

    function markAllRead() {
        ApiClient.patch(Config.API.notifications.markAllRead, {})
            .then(function () {
                $list.find('.notification-item').removeClass('unread')
                     .find('.notification-read-btn').remove();
                $markAllBtn.hide();
                refreshCount();
            })
            .catch(function () {});
    }

    function typeIcon(type) {
        const icons = {
            LOCK_BROKEN:             'fa-lock-open',
            ACCESS_GRANTED:          'fa-user-check',
            ACCESS_REVOKED:          'fa-user-times',
            ACCESS_REQUEST_APPROVED: 'fa-thumbs-up',
            ACCESS_REQUEST_DENIED:   'fa-thumbs-down',
            OPERATION_COMPLETED:     'fa-check-circle',
            OPERATION_FAILED:        'fa-times-circle'
        };
        return icons[type] || 'fa-bell';
    }

    function formatAgo(ts) {
        if (!ts) return '';
        const diff = Date.now() - new Date(ts).getTime();
        const m = Math.floor(diff / 60000);
        if (m < 1)  return 'just now';
        if (m < 60) return m + 'm ago';
        const h = Math.floor(m / 60);
        if (h < 24) return h + 'h ago';
        return Math.floor(h / 24) + 'd ago';
    }

    function escHtml(str) {
        return String(str || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    return { init, refreshCount };
})();

$(document).ready(function () {
    NotificationBell.init();
});
