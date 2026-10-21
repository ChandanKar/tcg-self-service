/**
 * VM Self-Service Platform - "No access" page (U02-T01, NoAccess.dc.html)
 * Shown instead of a blank or half-working page when someone opens a page their role cannot use,
 * a switched-off feature, an environment they cannot see, or an address that does not exist.
 * Plain words, no apology, and a way on.
 */
const NoAccessPage = (function() {
    'use strict';

    const HOME = { label: 'Go to Home', href: '#/dashboard', primary: true };
    const ENVIRONMENTS = { label: 'Your environments', href: '#/my-environments' };

    function variant(kind, opts) {
        const page = opts.label || 'This page';
        switch (kind) {
            case 'role-env-admin':
                return {
                    title: `${page} is for environment admins`,
                    body: "You followed a link to a page your role can't use. Nothing went wrong, and nothing was changed.",
                    hint: 'If you need to manage environments, ask your platform administrator to make you an environment admin.',
                    actions: [HOME, ENVIRONMENTS]
                };
            case 'role-admin':
                return {
                    title: `${page} is for administrators`,
                    body: "You followed a link to a page your role can't use. Nothing went wrong, and nothing was changed.",
                    hint: 'If you need it, ask your platform administrator.',
                    actions: [HOME, ENVIRONMENTS]
                };
            case 'feature-off':
                return {
                    title: `${page} is switched off`,
                    body: 'This part of the platform is not turned on here, so there is nothing to show.',
                    hint: 'An administrator can turn it on in Settings.',
                    actions: [HOME, ENVIRONMENTS]
                };
            case 'environment-admin':
                return {
                    title: `${page} are for the environment's admins`,
                    body: 'You can see this environment, but only the people who look after it can change its settings. Nothing was changed.',
                    hint: 'If you need to change it, ask one of its admins or your platform administrator.',
                    actions: [HOME, ENVIRONMENTS]
                };
            case 'environment':
                return {
                    title: "You can't open this environment",
                    body: "It doesn't exist any more, or you don't have access to it. Nothing was changed.",
                    hint: 'To use it, ask for access. An environment admin will review your request.',
                    actions: [{ label: 'Request access', href: '#/request-access', primary: true }, ENVIRONMENTS]
                };
            default:
                return {
                    title: "This page doesn't exist",
                    body: 'The address may be mistyped, or the page has moved.',
                    hint: '',
                    actions: [HOME, ENVIRONMENTS]
                };
        }
    }

    /**
     * Render into #content-area.
     * @param {'role-env-admin'|'role-admin'|'feature-off'|'environment'|'not-found'} kind
     * @param {{label?: string}} opts - label: the page's name, e.g. 'VM registry'
     */
    function render(kind, opts = {}) {
        const v = variant(kind, opts);
        const actions = v.actions.map(a =>
            `<a class="no-access-btn${a.primary ? ' no-access-btn-primary' : ''}" href="${Utils.escapeHtml(a.href)}">${Utils.escapeHtml(a.label)}</a>`
        ).join('');
        $('#content-area').html(`
            <section class="no-access" data-no-access="${Utils.escapeHtml(kind)}">
                <div class="no-access-strip" aria-hidden="true"><span></span><span></span><span></span></div>
                <h1 tabindex="-1">${Utils.escapeHtml(v.title)}</h1>
                <p>${Utils.escapeHtml(v.body)}</p>
                ${v.hint ? `<p>${Utils.escapeHtml(v.hint)}</p>` : ''}
                <div class="no-access-actions">${actions}</div>
            </section>
        `);
        document.title = `${v.title} - VM Self-Service`;
        const heading = document.querySelector('#content-area .no-access h1');
        if (heading) heading.focus({ preventScroll: true });
    }

    return { render };
})();

window.NoAccessPage = NoAccessPage;
