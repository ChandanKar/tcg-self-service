/**
 * Sign-in page: Sign in with Microsoft (Entra ID) is always offered first; the username form follows
 * unless the server reports password login as disabled (GET /api/auth/options), when the form and
 * its divider are hidden (U09-T01). Messages stay until the user acts: no timers (WCAG 2.2.1).
 * U20-T07: a right initial password signs nobody in; the page asks for the person's own password
 * (POST /api/auth/change-password with the one given) and that signs them in.
 * Loaded only by login.html, so it is not part of the dist bundle.
 */
(function () {
    'use strict';

    const SIGN_IN_LABEL = 'Sign in';
    const SIGNING_IN_LABEL = 'Signing in…';
    const DEFAULT_ERROR = 'Invalid username or password.';

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
    }

    /** The message stays until the user edits a field or tries again (no timer: WCAG 2.2.1). */
    function hideError() {
        hide(document.getElementById('errorAlert'));
    }

    function setButtonLoading(isLoading) {
        const submitBtn = document.getElementById('submitBtn');
        if (!submitBtn) return;
        submitBtn.disabled = isLoading;
        submitBtn.textContent = isLoading ? SIGNING_IN_LABEL : SIGN_IN_LABEL;
    }

    function showLogoutMessage() {
        if (!new URLSearchParams(window.location.search).has('logout')) return;
        // "You are signed out." stays until the user edits a field or signs in again.
        show(document.getElementById('logoutMessage'));
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
            showError('Enter your username and password.');
            return;
        }

        setButtonLoading(true);
        hideError();
        try {
            const headers = { 'Content-Type': 'application/json' };
            const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
            if (csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf[1]);
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: headers,
                credentials: 'same-origin',
                body: JSON.stringify({ username: username, password: password })
            });

            if (response.ok) {
                const data = await response.json().catch(() => ({}));
                if (data && data.passwordChangeRequired) {
                    askForOwnPassword(username, password);
                    return;
                }
                window.location.href = '/home';
                return;
            }
            if (response.status === 404) {
                showError('Signing in with a username is switched off. Use Sign in with Microsoft.');
                return;
            }
            showError((await readMessage(response)) || DEFAULT_ERROR);
        } catch (error) {
            showError('Signing in did not work. Try again.');
        } finally {
            setButtonLoading(false);
        }
    }

    // ---- U20-T07: choose your own password ----

    let pending = null; // {username, password} of the initial sign-in, kept only in memory

    function askForOwnPassword(username, password) {
        pending = { username: username, password: password };
        ['signInHead', 'loginForm', 'loginDivider', 'logoutMessage'].forEach(id => hide(document.getElementById(id)));
        document.querySelectorAll('.btn-entraid').forEach(hide);
        show(document.getElementById('changeForm'));
        document.getElementById('password').value = '';
        document.getElementById('newPassword').focus();
    }

    function changeError(message) {
        const box = document.getElementById('changeError');
        box.textContent = message;
        show(box);
    }

    /** "" when the two fields make a password, else what to fix. */
    function newPasswordProblem(chosen, again) {
        if (!chosen || chosen.length < 12) return 'Use at least 12 characters.';
        if (chosen.length > 72) return 'Use at most 72 characters.';
        if (chosen !== again) return "The two passwords don't match.";
        return '';
    }

    async function submitChange(e) {
        e.preventDefault();
        if (!pending) return;
        const chosen = document.getElementById('newPassword').value;
        const problem = newPasswordProblem(chosen, document.getElementById('confirmPassword').value);
        if (problem) { changeError(problem); return; }
        hide(document.getElementById('changeError'));
        const button = document.getElementById('changeBtn');
        button.disabled = true;
        try {
            const headers = { 'Content-Type': 'application/json' };
            const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
            if (csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf[1]);
            const response = await fetch('/api/auth/change-password', {
                method: 'POST', headers: headers, credentials: 'same-origin',
                body: JSON.stringify({ username: pending.username, password: pending.password, newPassword: chosen })
            });
            if (response.ok) {
                pending = null;
                window.location.href = '/home';
                return;
            }
            changeError((await readMessage(response)) || 'Your password was not saved. Try again.');
        } catch (error) {
            changeError('Your password was not saved. Try again.');
        } finally {
            button.disabled = false;
        }
    }

    /** Spring Security redirects a failed Entra sign-in (e.g. account_conflict) to /login?error. */
    function showSignInError() {
        const params = new URLSearchParams(window.location.search);
        if (params.has('expired')) {
            // CurrentUserRefreshFilter: session lifetime reached or account deactivated.
            showError('Your session ended. Sign in again to carry on.');
        } else if (params.has('error')) {
            showError('Signing in with Microsoft did not work. If this keeps happening, ask your platform administrator.');
        }
    }

    // A deep link opened while signed out (e.g. a Teams action button) arrives here with its hash:
    // keep it so the app opens it after sign-in (E24-T04).
    if (/^#\/[A-Za-z]/.test(window.location.hash || '')) {
        try {
            sessionStorage.setItem('vmcontrol.intendedRoute', window.location.hash);
        } catch (e) { /* storage blocked: the user lands on the dashboard */ }
    }

    document.addEventListener('DOMContentLoaded', function () {
        showLogoutMessage();
        showSignInError();
        applySignInOptions();
        const loginForm = document.getElementById('loginForm');
        if (loginForm) loginForm.addEventListener('submit', submitLogin);
        const changeForm = document.getElementById('changeForm');
        if (changeForm) changeForm.addEventListener('submit', submitChange);
        ['username', 'password'].forEach(function (id) {
            const field = document.getElementById(id);
            if (field) field.addEventListener('input', function () {
                hideError();
                hide(document.getElementById('logoutMessage'));
            });
        });
    });
})();
