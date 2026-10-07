/**
 * Login page: Entra ID sign-in is always offered; the username/password form is shown unless
 * the server reports password login as disabled (GET /api/auth/options).
 * Loaded only by login.html, so it is not part of the dist bundle.
 */
(function () {
    'use strict';

    const SIGN_IN_LABEL = '<i class="fas fa-sign-in-alt" aria-hidden="true"></i> Sign In';
    const SIGNING_IN_LABEL = '<i class="fas fa-spinner fa-spin" aria-hidden="true"></i> Signing in...';
    const DEFAULT_ERROR = 'Invalid credentials or user not found.';

    let errorTimer = null;

    function show(el) {
        if (el) el.classList.remove('d-none');
    }

    function hide(el) {
        if (el) el.classList.add('d-none');
    }

    function showError(message) {
        const errorAlert = document.getElementById('errorAlert');
        const errorMessage = document.getElementById('errorMessage');
        if (!errorAlert || !errorMessage) return;
        errorMessage.textContent = message;
        show(errorAlert);
        clearTimeout(errorTimer);
        errorTimer = setTimeout(() => hide(errorAlert), 5000);
    }

    function hideError() {
        clearTimeout(errorTimer);
        hide(document.getElementById('errorAlert'));
    }

    function setButtonLoading(isLoading) {
        const submitBtn = document.getElementById('submitBtn');
        if (!submitBtn) return;
        submitBtn.disabled = isLoading;
        submitBtn.innerHTML = isLoading ? SIGNING_IN_LABEL : SIGN_IN_LABEL;
    }

    function showLogoutMessage() {
        if (!new URLSearchParams(window.location.search).has('logout')) return;
        const logoutMsg = document.getElementById('logoutMessage');
        show(logoutMsg);
        setTimeout(() => hide(logoutMsg), 5000);
    }

    /** Hide the password form only when the server says it is off; keep it on any error. */
    async function applySignInOptions() {
        try {
            const response = await fetch('/api/auth/options', { credentials: 'same-origin' });
            if (!response.ok) return;
            const options = await response.json();
            if (options.passwordLoginEnabled === false) {
                hide(document.getElementById('loginForm'));
                hide(document.getElementById('loginDivider'));
            }
        } catch (e) {
            // Leave both sign-in options visible.
        }
    }

    async function readMessage(response) {
        try {
            const data = await response.json();
            return data && data.message;
        } catch (e) {
            return null;
        }
    }

    async function submitLogin(e) {
        e.preventDefault();

        const username = document.getElementById('username').value.trim();
        const password = document.getElementById('password').value;
        if (!username || !password) {
            showError('Username and password are required');
            return;
        }

        setButtonLoading(true);
        hideError();
        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({ username: username, password: password })
            });

            if (response.ok) {
                window.location.href = '/home';
                return;
            }
            if (response.status === 404) {
                showError('Password sign-in is not available. Use Sign in with Entra ID.');
                return;
            }
            showError((await readMessage(response)) || DEFAULT_ERROR);
        } catch (error) {
            showError('Login failed. Please try again.');
        } finally {
            setButtonLoading(false);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        showLogoutMessage();
        applySignInOptions();
        const loginForm = document.getElementById('loginForm');
        if (loginForm) loginForm.addEventListener('submit', submitLogin);
    });
})();
