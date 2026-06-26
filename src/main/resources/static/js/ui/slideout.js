/**
 * VM Self-Service Platform - Slideout Panel UI
 * Handles slide-out panels for Running VMs, Locked Environments, etc.
 */

const Slideout = (function() {
    'use strict';

    /**
     * Initialize slideout functionality
     */
    function init() {
        bindEvents();
    }

    /**
     * Bind event handlers using event delegation
     */
    function bindEvents() {
        // Close button (use delegation)
        $(document).on('click', '.slideout-panel .close-btn', close);

        // Overlay click
        $(document).on('click', '#slideoutOverlay', close);

        // ESC key to close
        $(document).on('keydown', function(e) {
            if (e.key === 'Escape') {
                close();
            }
        });
    }

    /**
     * Open a slideout panel.
     * Supports either an existing panel id, or dynamic content as (title, html).
     * @param {string} panelIdOrTitle - Panel element ID or dynamic panel title
     * @param {string=} html - Optional dynamic panel body
     */
    function open(panelIdOrTitle, html) {
        if (typeof html === 'string') {
            openDynamic(panelIdOrTitle, html);
            return;
        }

        const panelId = panelIdOrTitle;

        // Close any open panels first
        $('.slideout-panel').removeClass('show');

        // Open the requested panel
        $(`#${panelId}`).addClass('show');
        $('#slideoutOverlay').addClass('show');

        // Prevent body scroll
        $('body').css('overflow', 'hidden');
    }

    function openDynamic(title, html) {
        const panelId = 'dynamicSlideoutPanel';
        let $panel = $(`#${panelId}`);

        if ($panel.length === 0) {
            $panel = $(`
                <div class="slideout-panel dynamic-slideout-panel" id="${panelId}">
                    <div class="slideout-panel-header">
                        <h3></h3>
                        <button class="close-btn" aria-label="Close">&times;</button>
                    </div>
                    <div class="slideout-panel-content"></div>
                </div>
            `);
            $('body').append($panel);
        }

        if (typeof VmCharts !== 'undefined') {
            VmCharts.disposeWithin($panel[0]);
        }

        $panel.find('.slideout-panel-header h3').html(title);
        $panel.find('.slideout-panel-content').html(html);
        open(panelId);
        $(document).trigger('slideout:opened', [$panel[0]]);
    }

    /**
     * Close all slideout panels
     */
    function close() {
        if (typeof VmCharts !== 'undefined') {
            $('.slideout-panel.show').each(function() {
                VmCharts.disposeWithin(this);
            });
        }
        $('.slideout-panel').removeClass('show');
        $('#slideoutOverlay').removeClass('show');

        // Restore body scroll
        $('body').css('overflow', '');
    }

    /**
     * Toggle a specific panel
     * @param {string} panelId - Panel element ID
     */
    function toggle(panelId) {
        const $panel = $(`#${panelId}`);

        if ($panel.hasClass('show')) {
            close();
        } else {
            open(panelId);
        }
    }

    /**
     * Update panel content
     * @param {string} panelId - Panel element ID
     * @param {string} html - HTML content for panel body
     */
    function updateContent(panelId, html) {
        $(`#${panelId} .slideout-panel-content`).html(html);
    }

    /**
     * Check if any panel is open
     * @returns {boolean}
     */
    function isOpen() {
        return $('.slideout-panel.show').length > 0;
    }

    return {
        init,
        open,
        close,
        toggle,
        updateContent,
        isOpen
    };
})();

// Global function for inline onclick handlers (backward compatibility)
function openSlideout(panelId) {
    Slideout.open(panelId);
}

function closeSlideout() {
    Slideout.close();
}

