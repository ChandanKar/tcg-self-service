/**
 * VM Self-Service Platform - Page header (U04-T03)
 * The header of every screen in the mockup: breadcrumb, title, one summary line, actions on the
 * right ("Title, one summary line, actions on the right, then content" - StyleGuide.dc.html).
 *
 *   PageHeader.markup({title, summaryHtml, breadcrumb: [{label, href}], actionsHtml, helpPage,
 *                      helpLabel, actionsClass, id, sectionNav: {label, items: [{label, href, current}]}})
 *   PageHeader.costNav('savings')   the Cost pages' switcher beside the title (Spend · Savings · Waste · Chargeback)
 *
 * The title and breadcrumb labels are text (escaped); summaryHtml and actionsHtml are markup the
 * caller built safely. The last crumb is the current page (aria-current, no link). The outer
 * element keeps the .content-header class, so existing selectors still find the title.
 * markup() also names the browser tab "<title> · Self-Service" (the router resets it to
 * "Self-Service" for pages that do not use this header yet).
 */
const PageHeader = (function() {
    'use strict';

    const APP = 'Self-Service';

    function setDocumentTitle(title) {
        if (typeof document === 'undefined') return;
        document.title = title ? `${title} · ${APP}` : APP;
    }

    function breadcrumbHtml(crumbs) {
        const list = (crumbs || []).filter(c => c && c.label);
        if (!list.length) return '';
        const parts = list.map((c, i) => {
            const last = i === list.length - 1;
            if (last) return Utils.html`<span aria-current="page">${c.label}</span>`;
            return c.href ? Utils.html`<a href="${c.href}">${c.label}</a>` : Utils.html`<span>${c.label}</span>`;
        });
        return '<nav class="page-breadcrumb" aria-label="Breadcrumb">'
            + parts.join('<span class="page-breadcrumb-sep" aria-hidden="true">/</span>') + '</nav>';
    }

    /**
     * The pages of one menu entry, beside the title (U21-T07): {label, items: [{label, href, current}]}.
     * Links to the other pages; the current one is plain text with aria-current. A compact segmented
     * control (U21-T17) on the title's line, so it costs no height on pages fitted to the window.
     */
    function sectionNavHtml(nav) {
        const items = (nav && nav.items || []).filter(i => i && i.label);
        if (!items.length) return '';
        const parts = items.map(i => i.current ? Utils.html`<span class="page-section-item" aria-current="page">${i.label}</span>`
            : Utils.html`<a class="page-section-item" href="${i.href}">${i.label}</a>`);
        return Utils.html`<nav class="page-section-nav" aria-label="${nav.label || 'Pages'}">` + parts.join('') + '</nav>';
    }

    function markup(opts) {
        const { title = '', summaryHtml = '', breadcrumb, sectionNav, actionsHtml = '', helpPage, helpLabel, actionsClass = '', id } = opts || {};
        const help = helpPage && typeof HelpLink !== 'undefined' ? HelpLink.button(helpPage, helpLabel || title) : '';
        const actions = (actionsHtml || help)
            ? `<div class="page-actions${actionsClass ? ' ' + Utils.escapeHtml(actionsClass) : ''}">${actionsHtml || ''}${help}</div>`
            : '';
        setDocumentTitle(title);
        return `<div class="content-header page-header"${id ? Utils.html` id="${id}"` : ''}>
                ${breadcrumbHtml(breadcrumb)}
                <div class="page-header-row">
                    <div class="page-heading">
                        ${sectionNav ? `<div class="page-title-line">${Utils.html`<h1>${title}</h1>`}${sectionNavHtml(sectionNav)}</div>`
                            : Utils.html`<h1>${title}</h1>`}
                        ${summaryHtml ? `<div class="page-summary">${summaryHtml}</div>` : ''}
                    </div>
                    ${actions}
                </div>
            </div>`;
    }

    /** One Cost entry in the menu (U21-T07): its four pages switch beside the title. 'Spend', not 'Overview',
     * which is Cost management's first tab (U21-T17). */
    const COST_PAGES = [['cost-management', 'Spend'], ['savings', 'Savings'], ['waste', 'Waste'], ['chargeback', 'Chargeback']];

    function costNav(current) {
        return { label: 'Cost pages', items: COST_PAGES.map(([key, label]) => ({ label, href: '#/' + key, current: key === current })) };
    }

    return { markup, costNav, setDocumentTitle, APP };
})();
