/**
 * VM Self-Service Platform - Authentication Module
 * Handles OAuth2 authentication, user session, and authorization
 */

const Auth = (function() {
    'use strict';

    // Current user data
    let currentUser = null;
    let isInitialized = false;

    /**
     * Initialize authentication
     * Checks if user is authenticated and loads user data
     */
    async function init() {
        console.log('Auth: Initializing...');

        try {
            currentUser = await loadCurrentUser();
            isInitialized = true;

            // Update UI with user info
            UserMenu.updateUserDisplay(currentUser);

            // Update sidebar based on roles
            updateUIForRoles();

            console.log('Auth: User authenticated:', currentUser.email);
            return currentUser;
        } catch (error) {
            console.warn('Auth: Not authenticated, redirecting to login...');
            redirectToLogin();
            return null;
        }
    }

    /**
     * Load current user from API
     */
    async function loadCurrentUser() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.auth.currentUser)
                .done(function(user) {
                    resolve(user);
                })
                .fail(function(xhr) {
                    if (xhr.status === 401 || xhr.status === 403) {
                        reject(new Error('Not authenticated'));
                    } else {
                        reject(new Error('Failed to load user'));
                    }
                });
        });
    }

    /**
     * Redirect to OAuth2 login
     */
    function redirectToLogin() {
        const loginUrl = Config.AUTH.loginUrl || '/oauth2/authorization/azure';
        if (/^\/home\/?$/.test(window.location.pathname) && window.location.hash) {
            sessionStorage.setItem('vmcontrol.intendedRoute', window.location.hash);
        }
        window.location.href = loginUrl;
    }

    /**
     * Logout user
     */
    function logout() {
        const logoutUrl = Config.AUTH.logoutUrl || '/logout';
        window.location.href = logoutUrl;
    }

    /**
     * Get current user
     */
    function getUser() {
        return currentUser;
    }

    /**
     * Get current user ID
     */
    function getUserId() {
        return currentUser ? currentUser.userId : null;
    }

    /**
     * Get current user email
     */
    function getUserEmail() {
        return currentUser ? currentUser.email : null;
    }

    /**
     * Get current user display name
     */
    function getUserDisplayName() {
        return currentUser ? currentUser.displayName : 'Unknown User';
    }

    /**
     * Check if user is authenticated
     */
    function isAuthenticated() {
        return isInitialized && currentUser !== null;
    }

    /**
     * Check if user is admin
     */
    function isAdmin() {
        return currentUser && currentUser.admin === true;
    }

    /**
     * Check if user is environment admin
     */
    function isEnvAdmin() {
        return currentUser && (currentUser.admin === true || currentUser.envAdmin === true);
    }

    /**
     * Check if user has a specific role
     */
    function hasRole(role) {
        if (!currentUser) return false;

        switch (role.toUpperCase()) {
            case 'ADMIN':
                return isAdmin();
            case 'ENV_ADMIN':
                return isEnvAdmin();
            case 'USER':
                return true; // All authenticated users have USER role
            default:
                return false;
        }
    }

    /**
     * Get user's primary role for display
     */
    function getPrimaryRole() {
        if (isAdmin()) return 'Administrator';
        if (isEnvAdmin()) return 'Environment Admin';
        return 'User';
    }

    /**
     * Update UI elements based on user roles
     */
    function updateUIForRoles() {
        // Show/hide admin-only elements
        if (isAdmin()) {
            $('.admin-only').show();
            $('.env-admin-only').show();
        } else if (isEnvAdmin()) {
            $('.admin-only').hide();
            $('.env-admin-only').show();
        } else {
            $('.admin-only').hide();
            $('.env-admin-only').hide();
        }

        // Add role class to body for CSS targeting
        $('body').removeClass('role-admin role-env-admin role-user');
        if (isAdmin()) {
            $('body').addClass('role-admin');
        } else if (isEnvAdmin()) {
            $('body').addClass('role-env-admin');
        } else {
            $('body').addClass('role-user');
        }
    }

    /**
     * Require authentication - redirects if not authenticated
     */
    function requireAuth() {
        if (!isAuthenticated()) {
            redirectToLogin();
            return false;
        }
        return true;
    }

    /**
     * Require admin role - shows error if not admin
     */
    function requireAdmin() {
        if (!requireAuth()) return false;

        if (!isAdmin()) {
            Notifications.error('This action requires administrator privileges');
            return false;
        }
        return true;
    }

    /**
     * Require env admin role - shows error if not env admin
     */
    function requireEnvAdmin() {
        if (!requireAuth()) return false;

        if (!isEnvAdmin()) {
            Notifications.error('This action requires environment administrator privileges');
            return false;
        }
        return true;
    }

    // Public API
    return {
        init,
        logout,
        getUser,
        getUserId,
        getUserEmail,
        getUserDisplayName,
        isAuthenticated,
        isAdmin,
        isEnvAdmin,
        hasRole,
        getPrimaryRole,
        requireAuth,
        requireAdmin,
        requireEnvAdmin,
        redirectToLogin
    };
})();

