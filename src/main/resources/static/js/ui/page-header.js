/**
 * VM Self-Service Platform - Page header (U04-T03)
 * The header of every screen in the mockup: breadcrumb, title, one summary line, actions on the
 * right ("Title, one summary line, actions on the right, then content" - StyleGuide.dc.html).
 *
 *   PageHeader.markup({title, summaryHtml, breadcrumb: [{label, href}], actionsHtml, helpPage,
 *                      helpLabel, actionsClass, id})
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

    function markup(opts) {
        const { title = '', summaryHtml = '', breadcrumb, actionsHtml = '', helpPage, helpLabel, actionsClass = '', id } = opts || {};
        const help = helpPage && typeof HelpLink !== 'undefined' ? HelpLink.button(helpPage, helpLabel || title) : '';
        const actions = (actionsHtml || help)
            ? `<div class="page-actions${actionsClass ? ' ' + Utils.escapeHtml(actionsClass) : ''}">${actionsHtml || ''}${help}</div>`
            : '';
        setDocumentTitle(title);
        return `<div class="content-header page-header"${id ? Utils.html` id="${id}"` : ''}>
                ${breadcrumbHtml(breadcrumb)}
                <div class="page-header-row">
                    <div class="page-heading">
                        ${Utils.html`<h1>${title}</h1>`}
                        ${summaryHtml ? `<div class="page-summary">${summaryHtml}</div>` : ''}
                    </div>
                    ${actions}
                </div>
            </div>`;
    }

    return { markup, setDocumentTitle, APP };
})();
