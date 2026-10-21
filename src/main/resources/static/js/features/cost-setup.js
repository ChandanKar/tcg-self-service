/**
 * Cost setup (E08-T11), the Setup tab of Cost management since U15-T05 (CostSetup.dc.html): how many
 * cost features work, each feature with a status pill and, when AWS refused an action, the fix and
 * a policy to copy; "Check again" asks the server to simulate the IAM permissions and run free
 * probes. The switches, probes, estimate-vs-billing table and notes sit under Details.
 */
const CostSetup = (function() {
    'use strict';

    let status = null;          // GET /setup/status (carries the last check, if any)
    let utilization = null;
    let reconciliation = null;  // estimate vs. AWS billing per environment, null when it failed
    let reconPage = 1;
    let checking = false;

    const RECON_DAYS = 90;
    const RECON_PAGE_SIZE = 10;
    const RESULT_CLASS = { ALLOWED: 'cost-setup-ok', DENIED: 'cost-setup-denied', UNKNOWN: 'cost-setup-unknown' };

    /** Plain name and what each feature does, by the server's feature key. */
    const FEATURE_TEXT = {
        snapshot: ['Daily cost estimate', 'Cost per VM from run time and list prices, every night'],
        actuals: ['AWS billing figures', 'Replaces estimates with what AWS actually billed, daily'],
        tagging: ['Tag reconciliation', 'Keeps the cost tags on every VM, so AWS billing can split by environment'],
        optimizer: ['Rightsizing from Compute Optimizer', "AWS's own size suggestions, daily"],
        reservations: ['Savings Plans and reservations', 'How much of the fleet commitments cover, daily'],
        awsForecast: ['AWS forecast', "AWS's month and 18-month forecast, weekly"],
        carbon: ['Carbon', 'Monthly emissions per environment from AWS Data Exports'],
        weeklyReports: ['Weekly reports', 'A weekly cost summary in the notification bell']
    };

    /**
     * A feature's state: off; no permission (with the refused actions); not sure (AWS could not say);
     * working (checked, or needs no AWS permission); or on but not checked yet.
     */
    function featureState(f, checked) {
        if (!f.enabled) return { key: 'off', label: 'Off', denied: [], unknown: [] };
        const checks = f.checks || [];
        const denied = checks.filter(c => c && c.result === 'DENIED');
        const unknown = checks.filter(c => c && c.result === 'UNKNOWN');
        if (denied.length) return { key: 'bad', label: 'No permission', denied, unknown };
        if (unknown.length) return { key: 'unknown', label: 'Not sure', denied, unknown };
        if (checked || FEATURE_NEEDS_AWS[f.key] === false) return { key: 'ok', label: 'Working', denied, unknown };
        return { key: 'on', label: 'On', denied, unknown };
    }

    /** Features that call no AWS API work as soon as they are on. */
    const FEATURE_NEEDS_AWS = { snapshot: false, weeklyReports: false };

    /** "6 of 8 cost features work" once checked; before that, how many are on. */
    function summaryLine(features, checked) {
        const list = features || [];
        if (!checked) {
            const on = list.filter(f => f.enabled).length;
            return `${on} of ${list.length} cost features are on`;
        }
        const ok = list.filter(f => featureState(f, true).key === 'ok').length;
        return `${ok} of ${list.length} cost features work`;
    }

    /** "AWS refused ce:GetCostAndUsage. Ask your AWS admin to add it to the platform role." */
    function fixSentence(state) {
        if (state.denied.length) {
            const actions = state.denied.map(c => c.action);
            const list = actions.length === 1 ? actions[0] : `${actions.slice(0, -1).join(', ')} and ${actions[actions.length - 1]}`;
            return `AWS refused ${list}. Ask your AWS admin to add ${actions.length === 1 ? 'it' : 'them'} to the platform role.`;
        }
        if (state.unknown.length) return state.unknown[0].hint || 'AWS could not say whether this is allowed.';
        return '';
    }

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const token = ContentRouter.token();
        reconPage = 1;
        $('#content-area').html(shell('<p class="cost-note">Loading&hellip;</p>'));
        bindTabs();
        Promise.all([
            fetchJson(Config.API.costManagement.setupStatus),
            fetchJson(Config.API.costManagement.reservationsUtilization).catch(() => null),
            fetchJson(Config.API.costManagement.reconciliation(RECON_DAYS)).catch(() => null)
        ]).then(([s, u, r]) => {
            if (!ContentRouter.isCurrent(token)) return;
            status = s;
            utilization = u;
            reconciliation = r;
            render();
        }).catch(xhr => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#cost-setup-body').html(UiStates.failed({ what: 'the cost setup', xhr, retryAction: 'cost-setup-reload', className: 'cost-panel-error' }));
        });
    }

    /** The cost page's header and tabs with Setup selected (U15-T05), around this tab's body. */
    function shell(bodyHtml) {
        const nav = typeof CostManagement !== 'undefined' && CostManagement.navHtml
            ? CostManagement.navHtml('setup', '<div id="cost-setup-body">' + bodyHtml + '</div>')
            : '<div id="cost-setup-body">' + bodyHtml + '</div>';
        return `<div class="content-view cost-management-view cost-setup-view">${nav}</div>`;
    }

    function bindTabs() {
        const list = document.getElementById('cost-tabs');
        if (list && typeof Tabs !== 'undefined') {
            Tabs.bind(list, key => {
                if (key !== 'setup') window.location.hash = '#/cost-management?tab=' + encodeURIComponent(key);
            });
        }
    }

    function fetchJson(url) {
        return new Promise((resolve, reject) => ApiClient.get(url, { quietServerErrors: true }).done(resolve).fail(reject));
    }

    function runCheck() {
        if (checking) return;
        checking = true;
        const token = ContentRouter.token();
        $('#cost-setup-check-btn').prop('disabled', true).text('Checking…');
        ApiClient.post(Config.API.costManagement.setupCheck, null)
            .done(result => {
                if (!ContentRouter.isCurrent(token)) return;
                status = result;
                render();
            })
            .always(() => {
                checking = false;
                $('#cost-setup-check-btn').prop('disabled', false).text('Check again');
            });
    }

    function render() {
        const checked = !!status.checkedAt;
        $('#cost-setup-body').html(Utils.html`
            <div class="cost-setup-summary">
                <div>
                    <div class="cost-setup-headline" id="cost-setup-headline">${summaryLine(status.features, checked)}</div>
                    <div class="cost-note">${Utils.raw(checked
                        ? Utils.html`Checked AWS permissions <span class="cost-mono" title="${Utils.formatDate(status.checkedAt)}">${Utils.formatRelativeTime(status.checkedAt)}</span>`
                        : 'AWS permissions not checked yet')}</div>
                </div>
                <button type="button" class="btn btn-outline-primary" id="cost-setup-check-btn" data-action="cost-setup-check"
                        title="Read-only: IAM policy simulation, free probes and at most one billed Cost Explorer call">${checked ? 'Check again' : 'Check AWS permissions'}</button>
            </div>
            ${Utils.raw(buildCredentials())}
            <div class="cost-setup-features">${Utils.raw(status.features.map(f => buildFeature(f, checked)).join(''))}</div>
            <p class="cost-note cost-setup-foot">Switch features on or off in <a href="#/settings?section=cost">Settings › Cost</a>.
                The IAM permissions each one needs are in the setup guide (docs/cost-management-setup.md).</p>
            <details class="cost-setup-details" id="cost-setup-details">
                <summary>Details</summary>
                ${Utils.raw(buildSwitches())}
                ${Utils.raw(buildProbes())}
                ${Utils.raw(buildReconciliation())}
                ${Utils.raw(buildNotes())}
            </details>`);
    }

    function buildCredentials() {
        if (status.credentialsConfigured) {
            return status.principalArn
                ? Utils.html`<p class="cost-note cost-setup-principal">Checked as <code>${status.principalArn}</code>.</p>`
                : '';
        }
        return Utils.html`<div class="alert alert-warning">AWS credentials are not configured
            (<code>aws.access-key</code> / <code>aws.secret-key</code>): the AWS cost features do nothing.</div>`;
    }

    function buildFeature(f, checked) {
        const [name, what] = FEATURE_TEXT[f.key] || [f.label, ''];
        const state = featureState(f, checked);
        const fix = fixSentence(state);
        const copies = state.denied.map(c => Utils.html`<button type="button" class="btn btn-link btn-sm p-0 cost-setup-copy"
                data-action="cost-setup-copy-policy" data-policy-action="${c.action}">Copy policy for ${c.action}</button>`).join('');
        return Utils.html`
            <div class="cost-setup-feature" data-feature="${f.key}">
                <div class="cost-setup-feature-text">
                    <div class="cost-setup-name">${name}</div>
                    <div class="cost-note">${what}${Utils.raw(featureExtra(f))}</div>
                    <div class="cost-note">${f.lastRunAt ? `Last ran ${Utils.formatRelativeTime(f.lastRunAt)}` : (f.enabled ? 'Has not run yet' : '')}</div>
                    ${Utils.raw(fix ? Utils.html`<div class="cost-setup-fix">${fix}</div>` : '')}
                    ${Utils.raw(copies ? `<div class="cost-setup-copies">${copies}</div>` : '')}
                </div>
                <span class="cost-setup-pill pill-${state.key}" data-state="${state.key}">${state.label}</span>
            </div>`;
    }

    function featureExtra(f) {
        if (f.key === 'tagging' && f.extra && f.extra.taggedVmCount != null) {
            return Utils.html` · ${f.extra.taggedVmCount} tagged, ${f.extra.untaggedVmCount} untagged VM(s)`;
        }
        if (f.key === 'reservations' && utilization) {
            const parts = [];
            if (utilization.coveragePercent != null) parts.push(`RI coverage ${utilization.coveragePercent}%`);
            if (utilization.spCoveragePercent != null) parts.push(`SP coverage ${utilization.spCoveragePercent}%`);
            return parts.length ? ` · latest ${parts.join(', ')}` : '';
        }
        return '';
    }

    /** What controls each feature and the result of each AWS action, as before U15. */
    function buildSwitches() {
        const rows = status.features.map(f => Utils.html`
            <tr data-feature="${f.key}">
                <td>${(FEATURE_TEXT[f.key] || [f.label])[0]}</td>
                <td><code>${f.property}</code><br><small class="text-muted">${f.envVar}</small></td>
                <td>${Utils.raw(buildChecks(f))}</td>
            </tr>`);
        return Utils.html`
            <h3 class="cost-setup-sub">Switches and permissions</h3>
            <div class="cost-scroll"><table class="table table-baseline table-sm cost-setup-table">
                <thead><tr><th scope="col">Feature</th><th scope="col">Controlled by</th><th scope="col">AWS actions</th></tr></thead>
                <tbody>${Utils.raw(rows.join(''))}</tbody>
            </table></div>`;
    }

    function buildChecks(f) {
        if (!f.checks || !f.checks.length) {
            return status.checkedAt || FEATURE_NEEDS_AWS[f.key] === false
                ? '<span class="text-muted small">No AWS permission needed</span>'
                : '<span class="text-muted small">Not checked yet</span>';
        }
        return f.checks.filter(Boolean).map(c => Utils.html`
            <div class="cost-setup-check ${RESULT_CLASS[c.result] || ''}">
                <span class="cost-setup-result">${c.result}</span> <code>${c.action}</code>
                ${c.hint ? Utils.raw(Utils.html`<small class="text-muted d-block">${c.hint}</small>`) : ''}
            </div>`).join('');
    }

    function buildProbes() {
        if (!status.probes || !status.probes.length) return '';
        const items = status.probes.map(p => Utils.html`
            <li class="cost-setup-check ${RESULT_CLASS[p.result] || ''}">
                <span class="cost-setup-result">${p.result}</span> ${p.name}
                ${p.detail ? Utils.raw(Utils.html`<small class="text-muted d-block">${p.detail}</small>`) : ''}
            </li>`).join('');
        return Utils.html`<div class="cost-setup-probes"><h3 class="cost-setup-sub">Live probes</h3>
            <ul class="list-unstyled mb-0">${Utils.raw(items)}</ul></div>`;
    }

    /** Estimate against AWS billing per environment, moved here from Overview (U15-T05). */
    function buildReconciliation() {
        const head = Utils.html`<h3 class="cost-setup-sub">Estimate against AWS billing, last ${RECON_DAYS} days</h3>`;
        if (reconciliation === null) return head + '<p class="cost-note">Couldn\'t load the comparison.</p>';
        if (!reconciliation.length) return head + '<p class="cost-note">No AWS billing figures yet to compare with.</p>';
        const rows = Pagination.slice(reconciliation, reconPage, RECON_PAGE_SIZE).map(row => {
            const actual = row.actualCost === null || row.actualCost === undefined ? null : Number(row.actualCost);
            const variance = row.variancePercent === null || row.variancePercent === undefined ? '—'
                : `${row.variancePercent >= 0 ? '+' : ''}${row.variancePercent}%`;
            return Utils.html`<tr>
                <td>${row.environmentName || '-'}</td>
                <td class="cost-num">${Utils.formatCurrency(Number(row.estimatedCost) || 0)}</td>
                <td class="cost-num">${actual === null ? 'Not yet fetched' : Utils.formatCurrency(actual)}</td>
                <td class="cost-num">${variance}</td>
                <td class="cost-num">${row.daysInWindow ? `${row.daysWithActuals || 0}/${row.daysInWindow} days` : '-'}</td></tr>`;
        }).join('');
        return head + Utils.html`
            <p class="cost-note">Variance compares only days that have AWS billing figures.
                <a href="${Config.API.costManagement.reconciliationExport(RECON_DAYS)}" download>Excel</a></p>
            <div class="cost-scroll"><table class="table table-baseline table-sm cost-table" id="cost-reconciliation-table">
                <thead><tr><th scope="col">Environment</th><th scope="col" class="cost-num">Estimated</th><th scope="col" class="cost-num">AWS billing</th>
                    <th scope="col" class="cost-num">Variance</th><th scope="col" class="cost-num">Coverage</th></tr></thead>
                <tbody>${Utils.raw(rows)}</tbody>
            </table></div>
            ${Utils.raw(Pagination.buildPager({ page: reconPage, totalItems: reconciliation.length, pageSize: RECON_PAGE_SIZE,
                action: 'cost-setup-recon-page' }))}`;
    }

    function reconPageTo(page) {
        reconPage = Math.max(1, Number(page) || 1);
        const details = document.getElementById('cost-setup-details');
        const open = details && details.open;
        render();
        if (open) document.getElementById('cost-setup-details').open = true;
    }

    function buildNotes() {
        return `
            <h3 class="cost-setup-sub">Notes</h3>
            <ul class="cost-setup-notes">
                <li>The read-only features (AWS billing figures, Compute Optimizer, Savings Plans and reservations, weekly
                    reports) are on by default. Tag writes and report emails are off until switched on.</li>
                <li>Cost tags take up to 24 hours to become usable in Cost Explorer; until then AWS billing finds no environments.</li>
                <li>Compute Optimizer needs the account opted in and about 14 days of data.</li>
                <li>A refused call is not billed; switch a feature off to stop it calling AWS.</li>
            </ul>`;
    }

    function copyPolicy(action) {
        const policy = JSON.stringify({
            Version: '2012-10-17',
            Statement: [{ Effect: 'Allow', Action: [action], Resource: '*' }]
        }, null, 2);
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(policy)
                .then(() => Notifications.success(`Policy for ${action} copied`))
                .catch(() => Notifications.info(policy));
        } else {
            Notifications.info(policy);
        }
    }

    return { load, runCheck, copyPolicy, reconPageTo, model: { featureState, summaryLine, fixSentence } };
})();

if (typeof window !== 'undefined') window.CostSetup = CostSetup;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'cost-setup-check': () => CostSetup.runCheck(),
    'cost-setup-reload': () => ContentRouter.reload(),
    'cost-setup-copy-policy': el => CostSetup.copyPolicy(el.dataset.policyAction),
    'cost-setup-recon-page': el => CostSetup.reconPageTo(el.dataset.page)
});
