/**
 * VM Self-Service Platform - Loading States Module
 * Provides consistent loading indicators and skeleton screens
 */

const Loading = (function() {
    'use strict';

    /**
     * Show full page loading
     */
    function showPage(message) {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
                <p class="mt-3 text-muted">${message || 'Loading...'}</p>
            </div>
        `);
    }

    /**
     * Show inline loading (replaces element content)
     */
    function showInline(selector, message) {
        $(selector).html(`
            <div class="loading-inline text-center py-3">
                <div class="spinner-border spinner-border-sm text-primary" role="status"></div>
                <span class="ms-2 text-muted">${message || 'Loading...'}</span>
            </div>
        `);
    }

    /**
     * Show button loading state
     */
    function showButton($button, text) {
        const originalText = $button.html();
        $button.data('original-text', originalText)
               .prop('disabled', true)
               .html(`<i class="fas fa-spinner fa-spin me-1"></i>${text || 'Loading...'}`);
    }

    /**
     * Reset button state
     */
    function resetButton($button) {
        const originalText = $button.data('original-text');
        if (originalText) {
            $button.prop('disabled', false).html(originalText);
        }
    }

    /**
     * Show skeleton loader for cards
     */
    function showCardSkeleton(count) {
        let skeletons = '';
        for (let i = 0; i < (count || 3); i++) {
            skeletons += `
                <div class="col-md-4 mb-3">
                    <div class="card skeleton-card">
                        <div class="card-body">
                            <div class="skeleton-line skeleton-title"></div>
                            <div class="skeleton-line skeleton-text"></div>
                            <div class="skeleton-line skeleton-text short"></div>
                        </div>
                    </div>
                </div>
            `;
        }
        return `<div class="row">${skeletons}</div>`;
    }

    /**
     * Show skeleton loader for table
     */
    function showTableSkeleton(rows, cols) {
        let headerCells = '';
        for (let i = 0; i < (cols || 5); i++) {
            headerCells += '<th><div class="skeleton-line skeleton-header"></div></th>';
        }

        let bodyRows = '';
        for (let r = 0; r < (rows || 5); r++) {
            let cells = '';
            for (let c = 0; c < (cols || 5); c++) {
                cells += '<td><div class="skeleton-line skeleton-text"></div></td>';
            }
            bodyRows += `<tr>${cells}</tr>`;
        }

        return `
            <table class="table skeleton-table">
                <thead><tr>${headerCells}</tr></thead>
                <tbody>${bodyRows}</tbody>
            </table>
        `;
    }

    /**
     * Show skeleton loader for metrics
     */
    function showMetricsSkeleton(count) {
        let skeletons = '';
        const colClass = count === 4 ? 'col-md-3' : count === 3 ? 'col-md-4' : 'col-md-6';

        for (let i = 0; i < (count || 4); i++) {
            skeletons += `
                <div class="${colClass}">
                    <div class="metric-card skeleton-metric">
                        <div class="skeleton-line skeleton-label"></div>
                        <div class="skeleton-line skeleton-value"></div>
                        <div class="skeleton-line skeleton-subtitle"></div>
                    </div>
                </div>
            `;
        }
        return `<div class="row mb-4">${skeletons}</div>`;
    }

    /**
     * Show overlay loading on element
     */
    function showOverlay($element) {
        $element.addClass('loading-overlay-container');
        $element.append(`
            <div class="loading-overlay">
                <div class="spinner-border text-primary" role="status"></div>
            </div>
        `);
    }

    /**
     * Hide overlay loading
     */
    function hideOverlay($element) {
        $element.removeClass('loading-overlay-container');
        $element.find('.loading-overlay').remove();
    }

    /**
     * Show progress bar
     */
    function showProgress(percent, message) {
        return `
            <div class="loading-progress">
                <div class="progress" style="height: 20px;">
                    <div class="progress-bar progress-bar-striped progress-bar-animated"
                         role="progressbar" style="width: ${percent}%">
                        ${percent}%
                    </div>
                </div>
                ${message ? `<p class="text-center mt-2 text-muted">${message}</p>` : ''}
            </div>
        `;
    }

    // Public API
    return {
        showPage,
        showInline,
        showButton,
        resetButton,
        showCardSkeleton,
        showTableSkeleton,
        showMetricsSkeleton,
        showOverlay,
        hideOverlay,
        showProgress
    };
})();

