/**
 * VM Self-Service Platform - Slideout Panel UI
 * Handles slide-out panels for Running VMs, Locked Environments, etc.
 */

const Slideout = (function() {
    'use strict';

    // Regions hidden from focus and screen readers while a panel is open (E13-T03).
    const PAGE_REGIONS = '#mainContent, .top-navbar, #sidebar, #mobile-nav';
    const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
        + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const SUPPORTS_INERT = 'inert' in HTMLElement.prototype;
    let lastFocus = null;

    /**
     * Initialize slideout functionality
     */
    function init() {
        $('.slideout-panel').each(function() { setClosed(this, true); });
        bindEvents();
    }

    /** A closed panel is inert (out of the Tab order and the accessibility tree). */
    function setClosed(panel, closed) {
        panel.inert = closed;
        if (closed) panel.setAttribute('inert', ''); else panel.removeAttribute('inert');
        if (!SUPPORTS_INERT) {
            if (closed) panel.setAttribute('aria-hidden', 'true'); else panel.removeAttribute('aria-hidden');
        }
    }

    function setPageInert(inert) {
        $(PAGE_REGIONS).each(function() {
            this.inert = inert;
            if (inert) this.setAttribute('inert', ''); else this.removeAttribute('inert');
        });
    }

    function focusables(panel) {
        return Array.from(panel.querySelectorAll(FOCUSABLE))
            .filter(el => el.offsetParent !== null || el === document.activeElement);
    }

    /** Typing in a field must not close the panel (an Escape would lose the draft). */
    function isTextField(el) {
        if (!el) return false;
        const tag = el.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
    }

    /**
     * Bind event handlers using event delegation
     */
    function bindEvents() {
        // Close button (use delegation)
        $(document).on('click', '.slideout-panel .close-btn', close);

        // Overlay click
        $(document).on('click', '#slideoutOverlay', close);

        // ESC closes the open panel, but not while typing or while a Bootstrap modal is on top.
        $(document).on('keydown', function(e) {
            if (e.key !== 'Escape' || !isOpen()) return;
            if (isTextField(e.target) || $('.modal.show').length) return;
            close();
        });

        // Keep Tab / Shift+Tab inside the open panel.
        $(document).on('keydown', '.slideout-panel.show', function(e) {
            if (e.key !== 'Tab') return;
            const items = focusables(this);
            if (!items.length) return;
            const first = items[0];
            const last = items[items.length - 1];
            if (e.shiftKey && (document.activeElement === first || !this.contains(document.activeElement))) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        });
    }

    /**
     * Open a slideout panel.
     * Supports either an existing panel id, or dynamic content as (title, html).
     * @param {string} panelIdOrTitle - Panel element ID or dynamic panel title
     * @param {string=} html - Optional dynamic panel body
     * @param {{variant?: string, html?: boolean}=} options - Dynamic panels only: `variant` is set
     *        as the panel's data-variant so a feature can restyle it (e.g. a narrower width);
     *        `html: true` renders the title as markup (it is text by default)
     */
    function open(panelIdOrTitle, html, options) {
        if (typeof html === 'string') {
            openDynamic(panelIdOrTitle, html, options);
            return;
        }

        const panelId = panelIdOrTitle;
        const panel = document.getElementById(panelId);
        if (!panel) return;

        // Remember the opener once (re-rendering an open panel must not overwrite it)
        if (!isOpen()) lastFocus = document.activeElement;

        // Close any open panels first
        $('.slideout-panel').not(panel).removeClass('show').each(function() { setClosed(this, true); });

        // Open the requested panel as a modal dialog
        $(panel).addClass('show');
        setClosed(panel, false);
        $('#slideoutOverlay').addClass('show');
        setPageInert(true);

        // Prevent body scroll
        $('body').css('overflow', 'hidden');

        // Move focus into the dialog unless it is already there
        requestAnimationFrame(() => {
            if (!panel.classList.contains('show') || panel.contains(document.activeElement)) return;
            const content = panel.querySelector('.slideout-panel-content');
            const target = (content && focusables(content)[0]) || panel.querySelector('.close-btn');
            if (target) target.focus();
        });
    }

    function openDynamic(title, html, options = {}) {
        const panelId = 'dynamicSlideoutPanel';
        let $panel = $(`#${panelId}`);

        if ($panel.length === 0) {
            $panel = $(`
                <div class="slideout-panel dynamic-slideout-panel" id="${panelId}" role="dialog"
                     aria-modal="true" aria-labelledby="dynamicSlideoutTitle" inert>
                    <div class="slideout-panel-header">
                        <h3 id="dynamicSlideoutTitle"></h3>
                        <button type="button" class="close-btn" aria-label="Close">&times;</button>
                    </div>
                    <div class="slideout-panel-content"></div>
                </div>
            `);
            $('body').append($panel);
        }

        if (typeof VmCharts !== 'undefined') {
            VmCharts.disposeWithin($panel[0]);
        }

        $panel.attr('data-variant', options.variant || null);
        const $title = $panel.find('.slideout-panel-header h3');
        if (options.html === true) $title.html(title); else $title.text(title);
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
        const wasOpen = isOpen();
        $('.slideout-panel').removeClass('show').each(function() { setClosed(this, true); });
        $('#slideoutOverlay').removeClass('show');
        setPageInert(false);

        // Restore body scroll
        $('body').css('overflow', '');

        // Give focus back to whatever opened the panel
        if (wasOpen && lastFocus && lastFocus.isConnected) lastFocus.focus();
        if (wasOpen) lastFocus = null;

        // Lets panel owners (e.g. My Account) drop state so late responses cannot re-render.
        $(document).trigger('slideout:closed');
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

