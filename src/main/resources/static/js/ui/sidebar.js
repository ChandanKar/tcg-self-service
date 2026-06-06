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

    /**
     * Bind all sidebar event handlers using event delegation
     * Event delegation ensures handlers work even if DOM changes or modules load late
     */
    function bindEvents() {
        // Toggle sidebar collapse/expand
        $(document).on('click', '#toggleSidebar', toggleSidebar);

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
            $clickedSection.addClass('collapsed');
            $clickedMenu.slideUp(200);
        } else {
            // Collapse all other sections
            $('.sidebar-section-title').not($clickedSection).addClass('collapsed');
            $('.sidebar-menu').not($clickedMenu).slideUp(200);

            // Expand clicked section
            $clickedSection.removeClass('collapsed');
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
     * Handle main navigation clicks
     */
    function handleNavigation(e) {
        e.preventDefault();

        const $link = $(this);
        const contentType = $link.data('content');

        console.log('Navigation clicked:', contentType);

        // Remove active class from all
        $('.sidebar-menu-link').removeClass('active');

        // Add active class to clicked item
        $link.addClass('active');

        // Load content via router
        if (typeof ContentRouter !== 'undefined' && ContentRouter.loadContent) {
            ContentRouter.loadContent(contentType);
        } else {
            console.error('ContentRouter not available for content type:', contentType);
        }
    }

    /**
     * Handle submenu item navigation
     */
    function handleSubmenuNavigation(e) {
        e.preventDefault();
        e.stopPropagation();

        const contentType = $(this).data('content');
        const envName = $(this).data('env');

        // Remove active from all menu links
        $('.sidebar-menu-link').removeClass('active');

        if (typeof ContentRouter !== 'undefined') {
            ContentRouter.loadContent(contentType, { environmentName: envName });
        }
    }

    /**
     * Set active menu item programmatically
     */
    function setActiveItem(contentType) {
        $('.sidebar-menu-link').removeClass('active');
        $(`.sidebar-menu-link[data-content="${contentType}"]`).addClass('active');
    }

    /**
     * Expand a specific section
     */
    function expandSection(sectionId) {
        const $section = $(`#${sectionId}`);
        const $title = $section.prev('.sidebar-section-title');

        $title.removeClass('collapsed');
        $section.slideDown(200);
    }

    /**
     * Collapse all sections
     */
    function collapseAllSections() {
        $('.sidebar-section-title').addClass('collapsed');
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
        setActiveItem,
        expandSection,
        collapseAllSections,
        initKeyboardNav
    };
})();

