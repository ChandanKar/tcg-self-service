/**
 * "Create Jira ticket" / "View PROJ-123" cells (E24-T07). Tables render a placeholder
 * <span class="jira-cell" data-type="IDLE|RIGHTSIZING|ANOMALY" data-id="…" data-key="…" data-url="…">
 * and this module fills it once it knows Jira is on; with Jira off the cells stay empty. Anomaly
 * cells look up their existing tickets in one request.
 */
const JiraTickets = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let statusPromise = null;
    let scheduled = false;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function status() {
        if (!statusPromise) {
            statusPromise = call('GET', Config.API.integrations.jiraStatus).catch(() => {
                statusPromise = null;
                return { enabled: false };
            });
        }
        return statusPromise;
    }

    /** The placeholder a table puts in a cell. */
    function cell(type, id, key, url) {
        return Utils.html`<span class="jira-cell" data-type="${type}" data-id="${id}" data-key="${key || ''}" data-url="${url || ''}" data-pending="1"></span>`;
    }

    function linkHtml(key, url) {
        return Utils.html`<a class="jira-link small" href="${url}" target="_blank" rel="noopener noreferrer"
            title="Open ${key} in Jira"><i class="fas fa-ticket-alt me-1" aria-hidden="true"></i>${key}</a>`;
    }

    function buttonHtml(type, id) {
        return Utils.html`<button type="button" class="btn btn-sm btn-ghost jira-create" data-action="jira-create"
            data-type="${type}" data-id="${id}"><i class="fas fa-ticket-alt me-1" aria-hidden="true"></i>Create Jira ticket</button>`;
    }

    function fill(el, key, url) {
        el.removeAttribute('data-pending');
        el.innerHTML = key && url ? linkHtml(key, url) : buttonHtml(el.dataset.type, el.dataset.id);
    }

    /** Fills every pending cell on the page. */
    function hydrate() {
        const pending = Array.from(document.querySelectorAll('.jira-cell[data-pending]'));
        if (!pending.length) return;
        status().then(s => {
            if (!s || !s.enabled) {
                pending.forEach(el => el.removeAttribute('data-pending'));
                return;
            }
            const anomalies = pending.filter(el => el.dataset.type === 'ANOMALY' && !el.dataset.key);
            pending.filter(el => !anomalies.includes(el)).forEach(el => fill(el, el.dataset.key, el.dataset.url));
            if (!anomalies.length) return;
            const ids = [...new Set(anomalies.map(el => el.dataset.id))];
            call('GET', Config.API.integrations.jiraTickets('ANOMALY', ids))
                .then(found => anomalies.forEach(el => {
                    const t = found && found[el.dataset.id];
                    fill(el, t && t.issueKey, t && t.url);
                }))
                .catch(() => anomalies.forEach(el => fill(el, null, null)));
        });
    }

    function schedule() {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => { scheduled = false; hydrate(); });
    }

    function create(el) {
        const type = el.dataset.type;
        const id = el.dataset.id;
        const $btn = $(el).prop('disabled', true);
        call('POST', Config.API.integrations.jiraCreate, { sourceType: type, sourceId: id })
            .then(t => {
                document.querySelectorAll(`.jira-cell[data-type="${type}"][data-id="${CSS.escape(id)}"]`).forEach(c => {
                    c.dataset.key = t.issueKey;
                    c.dataset.url = t.url;
                    fill(c, t.issueKey, t.url);
                });
                Notifications.success(t.created ? `Created ${t.issueKey} in Jira.` : `Already tracked as ${t.issueKey}.`);
            })
            .catch(e => {
                $btn.prop('disabled', false);
                Notifications.showError((e && e.responseJSON && e.responseJSON.message) || 'Could not create the Jira ticket.');
            });
    }

    function init() {
        const area = document.getElementById('content-area');
        if (area && typeof MutationObserver !== 'undefined') {
            new MutationObserver(schedule).observe(area, { childList: true, subtree: true });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return { cell, hydrate, create, status };
})();

window.JiraTickets = JiraTickets;

Actions.registerAll({
    'jira-create': el => JiraTickets.create(el)
});
