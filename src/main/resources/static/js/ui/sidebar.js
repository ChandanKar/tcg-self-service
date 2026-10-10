/**
 * VM Self-Service Platform - Sidebar UI
 * Handles sidebar toggle, navigation, and menu interactions
 */

const Sidebar = (function() {
    'use strict';

    /**
     * Initialize sidebar functionality
     */
    function init() {
        bindEvents();
    }

    // ─── Phone drawer (E13-T01) ───────────────────────────────────────────────

    const PHONE_QUERY = '(max-width: 768px)';
    const isPhone = () => window.matchMedia(PHONE_QUERY).matches;
    let drawerOpener = null;

    function isDrawerOpen() {
        return $('#sidebar').hasClass('mobile-open');
    }

    /** Slide the sidebar in over a dimmed overlay and focus its first visible link. */
    function openDrawer(opener) {
        drawerOpener = opener || null;
        $('#sidebar').addClass('mobile-open');
        $('#mobile-overlay').addClass('show');
        $('#toggleSidebar, #mobile-more-btn').attr('aria-expanded', 'true');
        const first = $('#sidebar .sidebar-menu-link:visible').first();
        if (first.length) first.trigger('focus');
    }

    /** Close the drawer and give focus back to whatever opened it. */
    function closeDrawer() {
        if (!isDrawerOpen()) return;
        $('#sidebar').removeClass('mobile-open');
        $('#mobile-overlay').removeClass('show');
        $('#toggleSidebar, #mobile-more-btn').attr('aria-expanded', 'false');
        const opener = drawerOpener;
        drawerOpener = null;
        if (opener && document.body.contains(opener)) opener.focus();
    }

    function toggleDrawer(opener) {
        if (isDrawerOpen()) closeDrawer(); else openDrawer(opener);
    }

    /**
     * Bind all sidebar event handlers using event delegation
     * Event delegation ensures handlers work even if DOM changes or modules load late
     */
    function bindEvents() {
        // Toggle sidebar collapse/expand
        $(document).on('click', '#toggleSidebar', toggleSidebar);

        // Phone drawer: More button, overlay, Escape, and growing past the phone breakpoint
        $(document).on('click', '#mobile-more-btn', function () { toggleDrawer(this); });
        $(document).on('click', '#mobile-overlay', closeDrawer);
        $(document).on('keydown', function (e) {
            if (e.key === 'Escape' && isDrawerOpen()) {
                e.preventDefault();
                closeDrawer();
            }
        });
        const phoneMedia = window.matchMedia(PHONE_QUERY);
        const onMediaChange = (e) => { if (!e.matches) closeDrawer(); };
        if (phoneMedia.addEventListener) phoneMedia.addEventListener('change', onMediaChange);
        else if (phoneMedia.addListener) phoneMedia.addListener(onMediaChange);

        // Logo / brand — navigate to dashboard via the router (no page reload)
        $(document).on('click', '#topnav-home-link', handleLogoClick);

        // Section collapse/expand (Accordion behavior)
        $(document).on('click', '.sidebar-section-title', handleSectionClick);

        // Submenu toggle
        $(document).on('click', '[data-toggle="submenu"]', handleSubmenuToggle);

        // Navigation menu items with data-content attribute
        $(document).on('click', '.sidebar-menu-link[data-content]', handleNavigation);

        // Submenu item clicks
        $(document).on('click', '.submenu-item[data-content]', handleSubmenuNavigation);

        // Slide-out panel triggers (Running, Locked panels)
        $(document).on('click', '[data-panel]', handlePanelOpen);
    }

    /**
     * Handle slide-out panel open
     */
    function handlePanelOpen(e) {
        e.preventDefault();
        const panelType = $(this).data('panel');

        if (typeof Slideout !== 'undefined') {
            if (panelType === 'running') {
                Slideout.open('runningPanel');
            } else if (panelType === 'locked') {
                Slideout.open('lockedPanel');
            }
        }
    }

    /**
     * Toggle sidebar collapsed state
     */
    function toggleSidebar() {
        // On a phone the sidebar is an off-screen drawer; collapsing it would do nothing visible.
        if (isPhone()) {
            toggleDrawer(document.getElementById('toggleSidebar'));
            return;
        }
        $('#sidebar').toggleClass('collapsed');
        $('#mainContent').toggleClass('expanded');
    }

    /**
     * Handle section title click (accordion behavior)
     */
    function handleSectionClick() {
        const $clickedSection = $(this);
        const $clickedMenu = $clickedSection.next('.sidebar-menu');
        const isCurrentlyExpanded = !$clickedSection.hasClass('collapsed');

        if (isCurrentlyExpanded) {
            // Collapse clicked section
            $clickedSection.addClass('collapsed').attr('aria-expanded', 'false');
            $clickedMenu.slideUp(200);
        } else {
            // Collapse all other sections
            $('.sidebar-section-title').not($clickedSection).addClass('collapsed').attr('aria-expanded', 'false');
            $('.sidebar-menu').not($clickedMenu).slideUp(200);

            // Expand clicked section
            $clickedSection.removeClass('collapsed').attr('aria-expanded', 'true');
            $clickedMenu.slideDown(200);
        }
    }

    /**
     * Handle submenu toggle
     */
    function handleSubmenuToggle(e) {
        e.preventDefault();
        e.stopPropagation();

        const targetId = $(this).data('target');
        $('#' + targetId).toggleClass('show');
    }

    /**
     * Handle logo/brand click — same SPA navigation as a sidebar link, just
     * outside the sidebar DOM so it isn't matched by the data-content delegate above.
     */
    function handleLogoClick(e) {
        e.preventDefault();

        if (typeof ContentRouter !== 'undefined' && ContentRouter.navigate) {
            ContentRouter.navigate('dashboard');
        }
    }

    /**
     * Handle main navigation clicks — update the hash; the router's hashchange
     * listener handles active-state sync and content loading.
     */
    function handleNavigation(e) {
        e.preventDefault();

        const contentType = $(this).data('content');

        // Immediate visual feedback before hashchange fires
        $('.sidebar-menu-link').removeClass('active');
        $(this).addClass('active');
        if (isPhone()) closeDrawer();

        if (typeof ContentRouter !== 'undefined' && ContentRouter.navigate) {
            ContentRouter.navigate(contentType);
        } else {
            console.error('ContentRouter not available for content type:', contentType);
        }
    }

    /**
     * Handle submenu item navigation (environment-detail links)
     */
    function handleSubmenuNavigation(e) {
        e.preventDefault();
        e.stopPropagation();

        const contentType = $(this).data('content');
        const envName = $(this).data('env');

        $('.sidebar-menu-link').removeClass('active');
        if (isPhone()) closeDrawer();

        if (typeof ContentRouter !== 'undefined') {
            ContentRouter.navigate(contentType, { environmentName: envName });
        }
    }

    /**
     * Set active menu item programmatically
     */
    function setActiveItem(contentType) {
        // Environment Detail lives under My Environments in the menu.
        const key = contentType === 'environment-detail' ? 'my-environments' : contentType;
        $('.sidebar-menu-link').removeClass('active');
        const $link = $(`.sidebar-menu-link[data-content="${key}"]`).addClass('active');

        // Open the section holding the active page, so refreshes and deep links show it (E13-T02).
        const $menu = $link.closest('.sidebar-menu');
        if ($menu.length && $menu.css('display') === 'none') expandSection($menu.attr('id'));

        // Bottom bar: the item for this page, or More for pages only the drawer reaches.
        const $items = $('.mobile-nav-item').removeClass('active').removeAttr('aria-current');
        const hash = typeof ContentRouter !== 'undefined' && ContentRouter.hashFor ? ContentRouter.hashFor(key) : null;
        const $match = $items.filter((_, el) => hash && el.getAttribute('href') === hash);
        ($match.length ? $match : $('#mobile-more-btn')).addClass('active');
        $match.attr('aria-current', 'page');
    }

    /**
     * Expand a specific section
     */
    function expandSection(sectionId) {
        const $section = $(`#${sectionId}`);
        const $title = $section.prev('.sidebar-section-title');

        $title.removeClass('collapsed').attr('aria-expanded', 'true');
        $section.slideDown(200);
    }

    /**
     * Collapse all sections
     */
    function collapseAllSections() {
        $('.sidebar-section-title').addClass('collapsed').attr('aria-expanded', 'false');
        $('.sidebar-menu').slideUp(200);
    }

    /**
     * Initialize keyboard navigation for sidebar (TASK-009)
     * Allows arrow key navigation within the sidebar menu
     */
    function initKeyboardNav() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;

        sidebar.addEventListener('keydown', function(e) {
            const menuLinks = Array.from(
                sidebar.querySelectorAll('.sidebar-menu-link:not([style*="display: none"])')
            ).filter(link => {
                // Only include visible links
                const parent = link.closest('.sidebar-menu');
                return parent && parent.style.display !== 'none';
            });

            const currentIndex = menuLinks.indexOf(document.activeElement);
            if (currentIndex === -1) return;

            switch(e.key) {
                case 'ArrowDown':
                    e.preventDefault();
                    const nextIndex = Math.min(currentIndex + 1, menuLinks.length - 1);
                    menuLinks[nextIndex].focus();
                    break;

                case 'ArrowUp':
                    e.preventDefault();
                    const prevIndex = Math.max(currentIndex - 1, 0);
                    menuLinks[prevIndex].focus();
                    break;

                case 'Enter':
                case ' ':
                    e.preventDefault();
                    document.activeElement.click();
                    break;

                case 'Home':
                    e.preventDefault();
                    menuLinks[0].focus();
                    break;

                case 'End':
                    e.preventDefault();
                    menuLinks[menuLinks.length - 1].focus();
                    break;
            }
        });
    }

    // Initialize keyboard navigation when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initKeyboardNav);
    } else {
        // Small delay to ensure sidebar is rendered
        setTimeout(initKeyboardNav, 100);
    }

    return {
        init,
        toggleSidebar,
        openDrawer,
        closeDrawer,
        setActiveItem,
        expandSection,
        collapseAllSections,
        initKeyboardNav
    };
})();

