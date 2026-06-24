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
        // Toggle dropdown on click
        $(document).on('click', '.user-profile', function(e) {
            e.stopPropagation();
            toggleDropdown();
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

        // Handle profile click
        $(document).on('click', '#my-profile-btn', function(e) {
            e.preventDefault();
            showMyProfile();
        });

        // Handle my access click
        $(document).on('click', '#my-access-btn', function(e) {
            e.preventDefault();
            showMyAccess();
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
                <div class="user-profile-trigger">
                    <div class="user-avatar" title="${user.displayName}">
                        ${initials}
                    </div>
                    <div class="user-info">
                        <div class="user-name">${escapeHtml(user.displayName)}</div>
                        <div class="user-role">
                            <span class="role-badge ${roleBadgeClass}">${roleDisplay}</span>
                        </div>
                    </div>
                    <i class="fas fa-chevron-down dropdown-arrow"></i>
                </div>
                <div class="user-dropdown" style="display: none;">
                    <div class="dropdown-header">
                        <div class="dropdown-user-email">${escapeHtml(user.email)}</div>
                    </div>
                    <div class="dropdown-divider"></div>
                    <a href="#" class="dropdown-item" id="my-profile-btn">
                        <i class="fas fa-user"></i> My Profile
                    </a>
                    <a href="#" class="dropdown-item" id="my-access-btn">
                        <i class="fas fa-key"></i> My Access
                    </a>
                    <div class="dropdown-divider"></div>
                    <a href="#" class="dropdown-item text-danger" id="logout-btn">
                        <i class="fas fa-sign-out-alt"></i> Logout
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
        }
    }

    /**
     * Close dropdown
     */
    function closeDropdown() {
        $('.user-dropdown').slideUp(150);
        $('.dropdown-arrow').removeClass('rotated');
    }

    /**
     * Handle logout
     */
    function handleLogout() {
        closeDropdown();

        // Show confirmation
        if (confirm('Are you sure you want to logout?')) {
            Notifications.info('Logging out...');
            Auth.logout();
        }
    }

    /**
     * Show my profile (placeholder for future implementation)
     */
    function showMyProfile() {
        closeDropdown();

        const user = Auth.getUser();
        if (!user) return;

        // Show profile in slideout
        const content = `
            <div class="profile-details">
                <div class="profile-header">
                    <div class="profile-avatar large">${getInitials(user.displayName)}</div>
                    <h3>${escapeHtml(user.displayName)}</h3>
                    <p class="text-muted">${escapeHtml(user.email)}</p>
                </div>
                <div class="profile-info">
                    <div class="info-row">
                        <label>Role:</label>
                        <span class="role-badge ${getRoleBadgeClass(user)}">${Auth.getPrimaryRole()}</span>
                    </div>
                    <div class="info-row">
                        <label>Status:</label>
                        <span class="badge ${user.isActive ? 'bg-success' : 'bg-danger'}">
                            ${user.isActive ? 'Active' : 'Inactive'}
                        </span>
                    </div>
                    <div class="info-row">
                        <label>Last Login:</label>
                        <span>${user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'N/A'}</span>
                    </div>
                    <div class="info-row">
                        <label>Member Since:</label>
                        <span>${user.createdAt ? formatDateTime(user.createdAt) : 'N/A'}</span>
                    </div>
                </div>
            </div>
        `;

        Slideout.open('My Profile', content);
    }

    /**
     * Show my access (placeholder for future implementation)
     */
    function showMyAccess() {
        closeDropdown();

        // Load user's environment access
        ApiClient.get(Config.API.access.myEnvironments)
            .done(function(accessList) {
                const content = buildMyAccessContent(accessList);
                Slideout.open('My Environment Access', content);
            })
            .fail(function() {
                Notifications.error('Failed to load access information');
            });
    }

    /**
     * Build my access content
     */
    function buildMyAccessContent(accessList) {
        if (!accessList || accessList.length === 0) {
            return `
                <div class="empty-state">
                    <i class="fas fa-lock fa-3x text-muted"></i>
                    <p>You don't have access to any environments yet.</p>
                    <p class="text-muted">Request access from the Environments page.</p>
                </div>
            `;
        }

        const rows = accessList.map(access => `
            <tr>
                <td><strong>${escapeHtml(access.environmentName || 'Unknown')}</strong></td>
                <td>
                    <span class="badge bg-${getAccessLevelColor(access.accessLevel)}">
                        ${access.accessLevel}
                    </span>
                </td>
                <td>${access.grantedAt ? formatDate(access.grantedAt) : 'N/A'}</td>
                <td>${access.expiresAt ? formatDate(access.expiresAt) : 'Never'}</td>
            </tr>
        `).join('');

        return `
            <div class="access-list">
                <table class="table table-sm">
                    <thead>
                        <tr>
                            <th>Environment</th>
                            <th>Access Level</th>
                            <th>Granted</th>
                            <th>Expires</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows}
                    </tbody>
                </table>
            </div>
        `;
    }

    /**
     * Get access level badge color
     */
    function getAccessLevelColor(level) {
        switch (level) {
            case 'ADMIN': return 'danger';
            case 'USER': return 'primary';
            case 'VIEWER': return 'secondary';
            default: return 'secondary';
        }
    }

    /**
     * Format date/time
     */
    function formatDateTime(dateStr) {
        if (!dateStr) return 'N/A';
        const date = new Date(dateStr);
        return date.toLocaleString();
    }

    /**
     * Format date only
     */
    function formatDate(dateStr) {
        if (!dateStr) return 'N/A';
        const date = new Date(dateStr);
        return date.toLocaleDateString();
    }

    /**
     * Escape HTML to prevent XSS
     */
    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Public API
    return {
        init,
        updateUserDisplay,
        closeDropdown
    };
})();

