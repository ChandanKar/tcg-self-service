/**
 * VM Self-Service Platform - Loading States Module
 * A page-wide "Loading…" while a page fetches, and a blocking overlay while a change is saved.
 * Lists and panels use the delayed outlines of UiStates.loading (js/ui/states.js, U17-T01).
 */

const Loading = (function() {
    'use strict';

    /** The content area shows a spinner and the message until the page draws itself. */
    function showPage(message) {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
                <p class="mt-3 text-muted">${Utils.escapeHtml(message || 'Loading...')}</p>
            </div>
        `);
    }

    /** A blocking overlay over the whole app with the message (non-destructive). */
    function show(message) {
        hide(); // remove any existing
        $('body').append(`
            <div class="global-loading-overlay" id="globalLoadingOverlay"
                 style="position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.3);z-index:9999;display:flex;align-items:center;justify-content:center;">
                <div style="background:#fff;padding:24px 40px;border-radius:8px;text-align:center;box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                    <div class="spinner-border text-primary" role="status"></div>
                    <p class="mt-2 mb-0 text-muted">${Utils.escapeHtml(message || 'Loading...')}</p>
                </div>
            </div>
        `);
    }

    function hide() {
        $('#globalLoadingOverlay').remove();
    }

    return { show, hide, showPage };
})();
