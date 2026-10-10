/**
 * VM Self-Service Platform - User Menu Component
 * Handles user profile dropdown in the top navigation
 */

const UserMenu = (function() {
    'use strict';

    /**
     * Initialize user menu
     */
    function init() {
        bindEvents();
    }

    /**
     * Bind event handlers
     */
    function bindEvents() {
        // Toggle dropdown on click (the whole padded profile area, but not the menu itself)
        $(document).on('click', '.user-profile', function(e) {
            if ($(e.target).closest('.user-dropdown').length) return;
            e.stopPropagation();
            toggleDropdown();
        });

        // Menu keyboard (E13-T02): arrows move between items, Escape closes and returns focus, Tab closes.
        $(document).on('keydown', '.user-dropdown', function(e) {
            const items = $(this).find('[role="menuitem"]').toArray();
            const index = items.indexOf(document.activeElement);
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                const step = e.key === 'ArrowDown' ? 1 : -1;
                const next = index === -1 ? 0 : (index + step + items.length) % items.length;
                if (items[next]) items[next].focus();
            } else if (e.key === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                closeDropdown();
                $('.user-profile-trigger').trigger('focus');
            } else if (e.key === 'Tab') {
                closeDropdown();
            }
        });

        // Close dropdown when clicking outside
        $(document).on('click', function(e) {
            if (!$(e.target).closest('.user-profile-wrapper').length) {
                closeDropdown();
            }
        });

        // Handle logout click
        $(document).on('click', '#logout-btn', function(e) {
            e.preventDefault();
            handleLogout();
        });

        // Handle my account click
        $(document).on('click', '#my-account-btn', function(e) {
            e.preventDefault();
            closeDropdown();
            MyAccount.open();
        });
    }

    /**
     * Update user display in navbar
     */
    function updateUserDisplay(user) {
        if (!user) {
            console.warn('UserMenu: No user data to display');
            return;
        }

        const $userProfile = $('.user-profile');
        const initials = getInitials(user.displayName);
        const roleDisplay = Auth.getPrimaryRole();
        const roleBadgeClass = getRoleBadgeClass(user);

        // Build user profile HTML with dropdown
        const html = `
            <div class="user-profile-wrapper">
                <button type="button" class="user-profile-trigger" aria-haspopup="menu" aria-expanded="false"
                        aria-controls="user-dropdown" aria-label="${Utils.escapeHtml('Account menu for ' + (user.displayName || ''))}">
                    <div class="user-avatar" title="${Utils.escapeHtml(user.displayName)}">
                        ${Utils.escapeHtml(initials)}
                    </div>
                    <div class="user-info">
                        <div class="user-name">${Utils.escapeHtml(user.displayName)}</div>
                        <div class="user-role">
                            <span class="role-badge ${roleBadgeClass}">${Utils.escapeHtml(roleDisplay)}</span>
                        </div>
                    </div>
                    <i class="fas fa-chevron-down dropdown-arrow" aria-hidden="true"></i>
                </button>
                <div class="user-dropdown" id="user-dropdown" role="menu" aria-label="Account" style="display: none;">
                    <div class="dropdown-header">
                        <div class="dropdown-user-email">${Utils.escapeHtml(user.email)}</div>
                    </div>
                    <div class="dropdown-divider"></div>
                    <a href="#" class="dropdown-item" id="my-account-btn" role="menuitem">
                        <i class="fas fa-user" aria-hidden="true"></i>
                        <span class="dropdown-item-text">
                            <span>My Account</span>
                            <span class="dropdown-item-hint">Profile, access and activity</span>
                        </span>
                    </a>
                    <div class="dropdown-divider"></div>
                    <a href="#" class="dropdown-item text-danger" id="logout-btn" role="menuitem">
                        <i class="fas fa-sign-out-alt" aria-hidden="true"></i> Logout
                    </a>
                </div>
            </div>
        `;

        $userProfile.html(html);
    }

    /**
     * Get initials from display name
     */
    function getInitials(displayName) {
        if (!displayName) return '?';

        const parts = displayName.trim().split(/\s+/);
        if (parts.length >= 2) {
            return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
        }
        return displayName.substring(0, 2).toUpperCase();
    }

    /**
     * Get role badge CSS class
     */
    function getRoleBadgeClass(user) {
        if (user.admin) return 'role-badge-admin';
        if (user.envAdmin) return 'role-badge-env-admin';
        return 'role-badge-user';
    }

    /**
     * Toggle dropdown visibility
     */
    function toggleDropdown() {
        const $dropdown = $('.user-dropdown');
        const $arrow = $('.dropdown-arrow');

        if ($dropdown.is(':visible')) {
            closeDropdown();
        } else {
            $dropdown.slideDown(150);
            $arrow.addClass('rotated');
            $('.user-profile-trigger').attr('aria-expanded', 'true');
            $dropdown.find('[role="menuitem"]').first().trigger('focus');
        }
    }

    /**
     * Close dropdown
     */
    function closeDropdown() {
        $('.user-dropdown').slideUp(150);
        $('.dropdown-arrow').removeClass('rotated');
        $('.user-profile-trigger').attr('aria-expanded', 'false');
    }

    /**
     * Handle logout
     */
    function handleLogout() {
        closeDropdown();
        Notifications.info('Logging out...');
        Auth.logout();
    }

    // Public API
    return {
        init,
        updateUserDisplay,
        closeDropdown
    };
})();

