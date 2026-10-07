/**
 * NotificationBell — navbar dropdown with read/unread in-app notifications.
 * The unread count is polled by RealTime ('notificationCount', every 60 s, paused while the tab
 * is hidden); App.init() calls init() once the user is signed in. Opens a dropdown on click.
 */
const NotificationBell = (function () {
    'use strict';

    let $wrapper, $btn, $dropdown, $list, $badge, $markAllBtn, $empty;
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
            });
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

        ApiClient.get(Config.API.notifications.unread + '?page=0&size=15')
            .then(function (page) {
                const items = page.content || [];
                if (items.length === 0) {
                    $empty.show();
                    return;
                }
                $empty.hide();
                $markAllBtn.show();

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
        const tone   = typeTone(n.type);

        const $item = $(`
            <div class="notification-item ${unread ? 'unread' : ''} ${tone}"
                 data-id="${n.notificationId}" role="menuitem">
                <div class="notification-item-icon">
                    <i class="fas ${icon}"></i>
                </div>
                <div class="notification-item-body">
                    <div class="notification-item-title">${Utils.escapeHtml(n.title)}</div>
                    <div class="notification-item-msg">${Utils.escapeHtml(n.message)}</div>
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
                $item.remove();
                refreshCount();
                if ($list.find('.notification-item').length === 0) {
                    $markAllBtn.hide();
                    $empty.show();
                }
            })
            .catch(function () {});
    }

    function markAllRead() {
        ApiClient.patch(Config.API.notifications.markAllRead, {})
            .then(function () {
                $list.find('.notification-item').remove();
                $markAllBtn.hide();
                $empty.show();
                refreshCount();
            })
            .catch(function () {});
    }

    function typeIcon(type) {
        const icons = {
            LOCK_ACQUIRED:           'fa-lock',
            LOCK_RELEASED:           'fa-unlock',
            LOCK_BROKEN:             'fa-lock-open',
            ACCESS_REQUESTED:        'fa-user-clock',
            ACCESS_GRANTED:          'fa-user-check',
            ACCESS_REVOKED:          'fa-user-times',
            ACCESS_REQUEST_APPROVED: 'fa-thumbs-up',
            ACCESS_REQUEST_DENIED:   'fa-thumbs-down',
            ACCESS_EXPIRING:         'fa-hourglass-half',
            ACCESS_EXPIRED:          'fa-hourglass-end',
            OPERATION_REQUESTED:     'fa-play-circle',
            OPERATION_COMPLETED:     'fa-check-circle',
            OPERATION_FAILED:        'fa-times-circle',
            STATE_DRIFT_DETECTED:    'fa-exclamation-triangle',
            EKS_SYNC_CHANGED:        'fa-dharmachakra'
        };
        return icons[type] || 'fa-bell';
    }

    function typeTone(type) {
        if (['OPERATION_FAILED', 'LOCK_BROKEN', 'STATE_DRIFT_DETECTED', 'ACCESS_EXPIRED'].includes(type)) {
            return 'tone-danger';
        }
        if (['ACCESS_EXPIRING', 'ACCESS_REQUESTED', 'LOCK_ACQUIRED', 'OPERATION_REQUESTED'].includes(type)) {
            return 'tone-warning';
        }
        if (['OPERATION_COMPLETED', 'ACCESS_GRANTED', 'ACCESS_REQUEST_APPROVED', 'EKS_SYNC_CHANGED'].includes(type)) {
            return 'tone-success';
        }
        return 'tone-info';
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


    return { init, refreshCount };
})();
