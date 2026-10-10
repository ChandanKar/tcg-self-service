/**
 * Cost Setup page (E08-T11, admin): which cost features are on, the property that controls each
 * and when its job last ran, without any AWS call. "Run pre-flight check" asks the server to
 * simulate the IAM permissions each feature needs and run free probes; a denied action shows a
 * policy snippet to copy.
 */
const CostSetup = (function() {
    'use strict';

    let status = null;     // GET /setup/status
    let check = null;      // POST /setup/check (null until run)
    let utilization = null;
    let checking = false;

    const RESULT_CLASS = { ALLOWED: 'cost-setup-ok', DENIED: 'cost-setup-denied', UNKNOWN: 'cost-setup-unknown' };

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const token = ContentRouter.token();
        check = null;
        $('#content-area').html(Utils.html`
            <div class="content-view cost-setup-view">
                <div class="content-header"><h1>Cost Setup</h1><p>Loading&hellip;</p></div>
            </div>`);
        Promise.all([
            fetchJson(Config.API.costManagement.setupStatus),
            fetchJson(Config.API.costManagement.reservationsUtilization).catch(() => null)
        ]).then(([s, u]) => {
            if (!ContentRouter.isCurrent(token)) return;
            status = s;
            utilization = u;
            render();
        }).catch(() => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#content-area').html(Utils.html`
                <div class="content-view cost-setup-view">
                    <div class="content-header"><h1>Cost Setup</h1></div>
                    <div class="cost-panel-error" role="alert">Couldn't load the cost setup.
                        <button type="button" class="btn btn-sm btn-ghost" data-action="cost-setup-reload">Retry</button>
                    </div>
                </div>`);
        });
    }

    function fetchJson(url) {
        return new Promise((resolve, reject) => ApiClient.get(url, { quietServerErrors: true }).done(resolve).fail(reject));
    }

    function runCheck() {
        if (checking) return;
        checking = true;
        const token = ContentRouter.token();
        $('#cost-setup-check-btn').prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Checking&hellip;');
        ApiClient.post(Config.API.costManagement.setupCheck, null)
            .done(result => {
                if (!ContentRouter.isCurrent(token)) return;
                check = result;
                render();
            })
            .always(() => {
                checking = false;
                $('#cost-setup-check-btn').prop('disabled', false).html('<i class="fas fa-stethoscope"></i> Run pre-flight check');
            });
    }

    function render() {
        const data = check || status;
        $('#content-area').html(Utils.html`
            <div class="content-view cost-setup-view">
                <div class="content-header d-flex justify-content-between align-items-start flex-wrap gap-2">
                    <div>
                        <h1>Cost Setup</h1>
                        <p>Which cost features are on, and whether the AWS credential allows what they need.</p>
                    </div>
                    <div class="d-flex gap-2">
                        <a class="btn btn-ghost btn-sm" href="#/cost-management"><i class="fas fa-dollar-sign"></i> Cost Management</a>
                        <button type="button" class="btn btn-primary btn-sm" id="cost-setup-check-btn" data-action="cost-setup-check"
                                title="Read-only: IAM policy simulation, free probes and at most one billed Cost Explorer call">
                            <i class="fas fa-stethoscope"></i> Run pre-flight check
                        </button>
                    </div>
                </div>
                ${Utils.raw(buildCredentials(data))}
                ${Utils.raw(buildFeatures(data))}
                ${Utils.raw(buildProbes())}
                ${Utils.raw(buildNotes())}
            </div>`);
    }

    function buildCredentials(data) {
        if (data.credentialsConfigured) {
            return check && check.principalArn
                ? Utils.html`<p class="cost-setup-principal">Checked as <code>${check.principalArn}</code>, ${Utils.formatRelativeTime(check.checkedAt)}.</p>`
                : '';
        }
        return Utils.html`<div class="alert alert-warning">AWS credentials are not configured
            (<code>aws.access-key</code> / <code>aws.secret-key</code>): the AWS cost features do nothing.</div>`;
    }

    function buildFeatures(data) {
        const rows = data.features.map(f => Utils.html`
            <tr data-feature="${f.key}">
                <td><strong>${f.label}</strong>${Utils.raw(featureExtra(f))}</td>
                <td><span class="cost-setup-pill ${f.enabled ? 'on' : 'off'}">${f.enabled ? 'On' : 'Off'}</span></td>
                <td><code>${f.property}</code><br><small class="text-muted">${f.envVar}</small></td>
                <td title="${f.lastRunAt ? Utils.formatDate(f.lastRunAt) : ''}">${f.lastRunAt ? Utils.formatRelativeTime(f.lastRunAt) : 'Never'}</td>
                <td>${Utils.raw(buildChecks(f))}</td>
            </tr>`);
        return Utils.html`
            <section class="cost-table-card">
                <div class="table-responsive">
                    <table class="table table-baseline cost-setup-table">
                        <thead><tr><th>Feature</th><th>Status</th><th>Controlled by</th><th>Last run</th><th>Permissions</th></tr></thead>
                        <tbody>${Utils.raw(rows.join(''))}</tbody>
                    </table>
                </div>
            </section>`;
    }

    function featureExtra(f) {
        if (f.key === 'tagging' && f.extra) {
            return Utils.html`<br><small class="text-muted">${f.extra.taggedVmCount} tagged, ${f.extra.untaggedVmCount} untagged VM(s)</small>`;
        }
        if (f.key === 'reservations' && utilization) {
            const parts = [];
            if (utilization.coveragePercent != null) parts.push(`RI coverage ${utilization.coveragePercent}%`);
            if (utilization.spCoveragePercent != null) parts.push(`SP coverage ${utilization.spCoveragePercent}%`);
            if (utilization.coveredCost != null) parts.push(`covered ${Utils.formatCurrency(Number(utilization.coveredCost))}`);
            return parts.length ? Utils.html`<br><small class="text-muted">Latest: ${parts.join(', ')}</small>` : '';
        }
        return '';
    }

    function buildChecks(f) {
        if (!f.checks || !f.checks.length) {
            if (!check) return '<span class="text-muted small">Run the pre-flight check</span>';
            return '<span class="text-muted small">No AWS permission needed</span>';
        }
        return f.checks.map(c => Utils.html`
            <div class="cost-setup-check ${RESULT_CLASS[c.result] || ''}">
                <span class="cost-setup-result">${c.result}</span> <code>${c.action}</code>
                ${c.hint ? Utils.raw(Utils.html`<small class="text-muted d-block">${c.hint}</small>`) : ''}
                ${c.result === 'DENIED' ? Utils.raw(Utils.html`<button type="button" class="btn btn-link btn-sm p-0"
                        data-action="cost-setup-copy-policy" data-policy-action="${c.action}">Copy policy</button>`) : ''}
            </div>`).join('');
    }

    function buildProbes() {
        if (!check || !check.probes || !check.probes.length) return '';
        const items = check.probes.map(p => Utils.html`
            <li class="cost-setup-check ${RESULT_CLASS[p.result] || ''}">
                <span class="cost-setup-result">${p.result}</span> ${p.name}
                ${p.detail ? Utils.raw(Utils.html`<small class="text-muted d-block">${p.detail}</small>`) : ''}
            </li>`).join('');
        return Utils.html`
            <section class="cost-table-card cost-setup-probes">
                <div class="dashboard-panel-head"><h2>Live probes</h2><small>Read-only</small></div>
                <ul class="list-unstyled mb-0">${Utils.raw(items)}</ul>
            </section>`;
    }

    function buildNotes() {
        return `
            <section class="cost-table-card cost-setup-notes">
                <div class="dashboard-panel-head"><h2>Notes</h2></div>
                <ul>
                    <li>The read-only features (actual costs, Compute Optimizer, RI/SP coverage, weekly bell reports) are on by
                        default. Tag writes and report emails are off; start with <code>SPRING_PROFILES_ACTIVE=prod,cost</code> to turn them on.</li>
                    <li>Cost-allocation tags take up to 24 hours to become usable in Cost Explorer; until then actual costs find no environments.</li>
                    <li>Compute Optimizer needs the account opted in and about 14 days of data.</li>
                    <li>A denied call is not billed; set the feature's environment variable to <code>false</code> to stop a feature.</li>
                    <li>Full guide: <code>docs/cost-management-setup.md</code> in the repository.</li>
                </ul>
            </section>`;
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

    return { load, runCheck, copyPolicy };
})();

window.CostSetup = CostSetup;

Actions.registerAll({
    'cost-setup-check': () => CostSetup.runCheck(),
    'cost-setup-reload': () => ContentRouter.reload(),
    'cost-setup-copy-policy': el => CostSetup.copyPolicy(el.dataset.policyAction)
});
