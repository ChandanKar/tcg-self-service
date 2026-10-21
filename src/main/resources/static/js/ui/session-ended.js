/**
 * VM Self-Service Platform - "You've been signed out" (U17-T04, States.dc.html)
 *
 * When the session ends (any request or poll answers 401) one alert dialog says so and offers
 * "Sign in again", instead of a toast and a timed jump to the login page that loses what was typed.
 * The dialog cannot be dismissed with Escape; signing in again returns to the page the user was on
 * (vmcontrol.intendedRoute, read by the router after sign-in).
 */
const SessionEnded = (function() {
    'use strict';

    const INTENDED_ROUTE_KEY = 'vmcontrol.intendedRoute';
    let shown = false;

    function idleMinutes() {
        const m = typeof Config !== 'undefined' && Config.SESSION_IDLE_MINUTES;
        return Number(m) > 0 ? Number(m) : 60;
    }

    /** The page to come back to: the hash on /home. */
    function rememberRoute() {
        try {
            if (/^\/home\/?$/.test(window.location.pathname) && window.location.hash) {
                sessionStorage.setItem(INTENDED_ROUTE_KEY, window.location.hash);
            }
        } catch (e) { /* storage blocked: sign-in lands on Home */ }
    }

    function signInAgain() {
        rememberRoute();
        window.location.href = '/login.html?expired';
    }

    /** Opens the dialog once; later calls do nothing. */
    function show() {
        if (shown) return;
        shown = true;
        if (typeof RealTime !== 'undefined' && RealTime.stopAllPolling) RealTime.stopAllPolling();
        const minutes = idleMinutes();
        const $root = $(`<div class="session-ended-backdrop" id="session-ended">
                <div class="session-ended" role="alertdialog" aria-modal="true" aria-labelledby="session-ended-title" aria-describedby="session-ended-text">
                    <h2 id="session-ended-title">You've been signed out</h2>
                    <p id="session-ended-text"></p>
                    <button type="button" class="btn btn-primary" id="session-ended-sign-in">Sign in again</button>
                </div>
            </div>`);
        $root.find('#session-ended-text').text(`After ${minutes} minutes without activity. Sign in again to carry on where you were.`);
        $('body').append($root);
        // The page behind cannot be reached until the user signs in again.
        $('#mainContent, .top-navbar, #sidebar, #mobile-nav').attr('inert', '');
        const button = document.getElementById('session-ended-sign-in');
        $(button).on('click', signInAgain);
        // One control: Tab stays on it and Escape does not close the dialog.
        $root.on('keydown', e => {
            if (e.key === 'Tab' || e.key === 'Escape') {
                e.preventDefault();
                button.focus();
            }
        });
        button.focus();
    }

    function isShown() {
        return shown;
    }

    return { show, isShown };
})();

window.SessionEnded = SessionEnded;
