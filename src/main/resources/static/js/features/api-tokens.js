/**
 * API tokens in the UI (E34-T05).
 *   ApiTokensUi - the "API tokens" tab of My Account: the user's own tokens, create (name, scopes the
 *                 role allows, lifetime), a one-time reveal with Copy, revoke. Hidden while tokens are off.
 *   ApiAccess   - #/api-access, admins only: every active token with its owner (revoke any), and
 *                 service accounts (create, issue and revoke their tokens, deactivate).
 * A token is shown once, in the reveal dialog; closing it removes the dialog and the token from the page.
 */
const ApiTokenScopes = [
    ['environments:read', 'Read environments, VMs and operations'],
    ['operations:write', 'Start, stop and lock environments'],
    ['schedules:read', 'Read schedules'],
    ['schedules:write', 'Change schedules'],
    ['cost:read', 'Read cost and savings'],
    ['audit:read', 'Read the audit log'],
    ['access:write', 'Grant access and decide requests', 'envAdmin'],
    ['admin', 'Administration (everything)', 'admin']
];

const ApiTokensUi = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const LIFETIMES = [7, 30, 90, 180, 365];
    let tokens = null;
    let mountEl = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function date(ts) {
        return ts ? new Date(ts).toLocaleDateString() : '—';
    }

    /** Whether tokens are on for this user: the list answers (404 while off). */
    function probe() {
        return call('GET', Config.API.apiTokens.mine).then(list => { tokens = list || []; return true; }).catch(() => false);
    }

    function mount(el) {
        mountEl = el;
        if (tokens === null) {
            el.innerHTML = '<div class="text-muted small"><i class="fas fa-spinner fa-spin" aria-hidden="true"></i> Loading…</div>';
            reload();
            return;
        }
        draw();
    }

    function reload() {
        return call('GET', Config.API.apiTokens.mine).then(list => { tokens = list || []; draw(); })
            .catch(e => { if (mountEl) mountEl.innerHTML = Utils.html`<div class="text-danger small" role="alert">${message(e, "Couldn't load your tokens.")}</div>`; });
    }

    function status(t) {
        if (t.revokedAt) return '<span class="badge text-bg-light border">Revoked</span>';
        if (new Date(t.expiresAt).getTime() <= Date.now()) return '<span class="badge text-bg-light border">Expired</span>';
        return '<span class="badge text-bg-success">Active</span>';
    }

    function draw() {
        if (!mountEl || !document.body.contains(mountEl)) return;
        const rows = (tokens || []).map(t => Utils.html`<tr data-token="${t.id}">
                <td data-label="Name"><strong>${t.name}</strong><div class="small text-muted"><code>vmc_${t.prefix}_…</code></div></td>
                <td data-label="Scopes" class="small">${(t.scopes || []).join(', ')}</td>
                <td data-label="Last used" class="small">${t.lastUsedAt ? date(t.lastUsedAt) : 'Never'}</td>
                <td data-label="Expires" class="small">${date(t.expiresAt)}</td>
                <td data-label="Status">${Utils.raw(status(t))}</td>
                <td class="td-actions">${Utils.raw(!t.revokedAt && new Date(t.expiresAt).getTime() > Date.now()
                    ? Utils.html`<button type="button" class="btn btn-sm btn-ghost text-danger" data-action="api-token-revoke" data-id="${t.id}">Revoke</button>` : '')}</td>
            </tr>`).join('');
        mountEl.innerHTML = `
            <p class="small text-muted">Tokens let scripts and CI call the API as you: <code>Authorization: Bearer vmc_…</code>.
                A token can never do more than you can, and you see it only once.</p>
            <div class="d-flex justify-content-end mb-2">
                <button type="button" class="btn btn-sm btn-primary" data-action="api-token-new"><i class="fas fa-plus" aria-hidden="true"></i> New token</button>
            </div>
            ${rows ? `<div class="table-responsive"><table class="table table-sm table-cards" id="my-api-tokens">
                <thead><tr><th>Name</th><th>Scopes</th><th>Last used</th><th>Expires</th><th>Status</th><th></th></tr></thead>
                <tbody>${rows}</tbody></table></div>` : '<p class="text-muted small" id="my-api-tokens-empty">You have no API tokens.</p>'}`;
    }

    /**
     * The create dialog, shared with service accounts: the scopes offered follow the role.
     * @param {{admin:boolean, envAdmin:boolean}} role
     * @param {function(object):Promise} send - posts the body and resolves with the created token
     */
    function openCreate(title, role, send, onDone) {
        const scopes = ApiTokenScopes.filter(([, , need]) => !need || (need === 'admin' ? role.admin : role.admin || role.envAdmin));
        Modals.show({
            id: 'apiTokenCreateModal',
            title,
            body: `<form id="api-token-form" novalidate>
                <div class="mb-2"><label class="form-label small mb-1" for="api-token-name">Name</label>
                    <input class="form-control form-control-sm" id="api-token-name" maxlength="100" required placeholder="e.g. nightly-ci"></div>
                <fieldset class="mb-2"><legend class="form-label small mb-1">Scopes</legend>
                    ${scopes.map(([value, label]) => Utils.html`<div class="form-check">
                        <input class="form-check-input api-token-scope" type="checkbox" id="api-scope-${value.replace(':', '-')}" value="${value}">
                        <label class="form-check-label small" for="api-scope-${value.replace(':', '-')}"><code>${value}</code> ${label}</label></div>`).join('')}
                </fieldset>
                <div class="mb-2"><label class="form-label small mb-1" for="api-token-days">Expires after</label>
                    <select class="form-select form-select-sm" id="api-token-days">${LIFETIMES.map(d =>
                        `<option value="${d}" ${d === 30 ? 'selected' : ''}>${d} days</option>`).join('')}</select></div>
                <div class="small text-danger" id="api-token-error" role="alert"></div>
            </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Create token', class: 'btn-primary', id: 'api-token-create' }
            ]
        });
        $('#api-token-create').on('click', () => {
            const body = {
                name: String($('#api-token-name').val() || '').trim(),
                scopes: $('#api-token-form .api-token-scope:checked').map(function() { return this.value; }).get(),
                expiresInDays: Number($('#api-token-days').val())
            };
            if (!body.name) { $('#api-token-error').text('Give the token a name.'); return; }
            if (!body.scopes.length) { $('#api-token-error').text('Choose at least one scope.'); return; }
            $('#api-token-create').prop('disabled', true);
            send(body).then(created => {
                Modals.hideWhenReady(document.getElementById('apiTokenCreateModal'));
                reveal(created.token);
                if (onDone) onDone();
            }).catch(e => {
                $('#api-token-create').prop('disabled', false);
                $('#api-token-error').text(message(e, 'The token could not be created.'));
            });
        });
    }

    /** Shows a new token once; the dialog and the token leave the page when it closes. */
    function reveal(token) {
        Modals.show({
            id: 'apiTokenRevealModal',
            title: 'Your new API token',
            body: `<div class="alert alert-warning small" role="alert"><i class="fas fa-exclamation-triangle me-1" aria-hidden="true"></i>
                    Copy it now. You will not see this again.</div>
                <div class="input-group input-group-sm">
                    <input class="form-control font-monospace" id="api-token-value" readonly aria-label="API token">
                    <button type="button" class="btn btn-outline-secondary" id="api-token-copy"><i class="fas fa-copy" aria-hidden="true"></i> Copy</button>
                </div>`,
            buttons: [{ text: 'Done', class: 'btn-primary', dismiss: true }]
        });
        const modal = document.getElementById('apiTokenRevealModal');
        $('#api-token-value').val(token);
        $('#api-token-copy').on('click', () => {
            const input = document.getElementById('api-token-value');
            input.select();
            (navigator.clipboard ? navigator.clipboard.writeText(input.value) : Promise.reject())
                .then(() => Notifications.success('Token copied.'))
                .catch(() => { try { document.execCommand('copy'); } catch (e) { /* select is enough */ } });
        });
        if (modal) {
            modal.addEventListener('hide.bs.modal', () => { $('#api-token-value').val(''); }, { once: true });
        }
    }

    function create() {
        openCreate('New API token', { admin: Auth.isAdmin(), envAdmin: Auth.isEnvAdmin() },
            body => call('POST', Config.API.apiTokens.mine, body), () => reload());
    }

    function revoke(el) {
        const t = (tokens || []).find(x => x.id === el.dataset.id);
        if (!t) return;
        Modals.confirm('Revoke token', `Revoke '${t.name}'? Scripts using it stop working at once.`, () => {
            call('DELETE', Config.API.apiTokens.mineItem(t.id)).then(() => reload())
                .catch(e => Notifications.showError(message(e, 'Could not revoke the token.')));
        }, { confirmText: 'Revoke', confirmClass: 'btn-danger' });
    }

    return { probe, mount, create, revoke, openCreate, reveal };
})();

const ApiAccess = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;
    let page = 0;
    let tokenPage = null;
    let accounts = [];

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function date(ts) {
        return ts ? new Date(ts).toLocaleDateString() : '—';
    }

    function load() {
        pageToken = ContentRouter.token();
        page = 0;
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        $('#content-area').html(`
            <div class="content-view api-access-page" id="api-access-view">
                <div class="content-header">
                    <h1><i class="fas fa-key me-2" aria-hidden="true"></i>API access</h1>
                    <p class="text-muted mb-0">Every active API token and the service accounts CI and automation use</p>
                </div>
                <section class="card mb-3"><div class="card-body">
                    <h2 class="h6">Active tokens</h2>
                    <div id="api-access-tokens"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading…</div></div>
                </div></section>
                <section class="card"><div class="card-body">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <h2 class="h6 mb-0">Service accounts</h2>
                        <button type="button" class="btn btn-sm btn-primary" data-action="sa-new"><i class="fas fa-plus" aria-hidden="true"></i> New service account</button>
                    </div>
                    <div id="api-access-accounts"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading…</div></div>
                </div></section>
            </div>`);
        loadTokens();
        loadAccounts();
    }

    function off(e) {
        return e && e.status === 404
            ? '<div class="alert alert-info small mb-0">API tokens are off. Set api-tokens.enabled (with API_TOKEN_PEPPER) to use them.</div>'
            : Utils.html`<div class="text-danger small" role="alert">${message(e, "Couldn't load this list.")}</div>`;
    }

    function loadTokens() {
        const token = pageToken;
        call('GET', Config.API.apiTokens.all(page)).then(data => {
            if (!ContentRouter.isCurrent(token)) return;
            tokenPage = data;
            drawTokens();
        }).catch(e => { if (ContentRouter.isCurrent(token)) $('#api-access-tokens').html(off(e)); });
    }

    function drawTokens() {
        const items = (tokenPage && tokenPage.content) || [];
        const meta = (tokenPage && tokenPage.page) || {};
        if (!items.length) {
            $('#api-access-tokens').html('<p class="text-muted small mb-0">No active tokens.</p>');
            return;
        }
        const rows = items.map(v => Utils.html`<tr data-token="${v.token.id}">
                <td data-label="Owner">${v.ownerName || v.ownerUserId}${Utils.raw(v.ownerServiceAccount ? ' <span class="badge text-bg-light border">Service</span>' : '')}
                    <div class="small text-muted">${v.ownerEmail || ''}</div></td>
                <td data-label="Token"><strong>${v.token.name}</strong><div class="small text-muted"><code>vmc_${v.token.prefix}_…</code></div></td>
                <td data-label="Scopes" class="small">${(v.token.scopes || []).join(', ')}</td>
                <td data-label="Last used" class="small">${v.token.lastUsedAt ? date(v.token.lastUsedAt) : 'Never'}</td>
                <td data-label="Expires" class="small">${date(v.token.expiresAt)}</td>
                <td class="td-actions"><button type="button" class="btn btn-sm btn-ghost text-danger" data-action="api-admin-revoke" data-id="${v.token.id}">Revoke</button></td>
            </tr>`).join('');
        const pager = (meta.totalPages || 1) > 1 ? Utils.html`<div class="d-flex justify-content-end gap-2 align-items-center small">
                <button type="button" class="btn btn-sm btn-ghost" data-action="api-admin-page" data-page="${page - 1}" ${page === 0 ? 'disabled' : ''}>Previous</button>
                <span>Page ${page + 1} of ${meta.totalPages}</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="api-admin-page" data-page="${page + 1}" ${page + 1 >= meta.totalPages ? 'disabled' : ''}>Next</button></div>` : '';
        $('#api-access-tokens').html(`<div class="table-responsive"><table class="table table-sm table-cards" id="api-admin-tokens">
            <thead><tr><th>Owner</th><th>Token</th><th>Scopes</th><th>Last used</th><th>Expires</th><th></th></tr></thead>
            <tbody>${rows}</tbody></table></div>${pager}`);
    }

    function setPage(el) {
        page = Math.max(0, Number(el.dataset.page) || 0);
        loadTokens();
    }

    function revokeAny(el) {
        const v = ((tokenPage && tokenPage.content) || []).find(x => x.token.id === el.dataset.id);
        if (!v) return;
        Modals.confirm('Revoke token', `Revoke '${v.token.name}' of ${v.ownerName || v.ownerUserId}? It stops working at once.`, () => {
            call('DELETE', Config.API.apiTokens.item(v.token.id)).then(() => {
                Notifications.success('Token revoked.');
                loadTokens();
                loadAccounts();
            }).catch(e => Notifications.showError(message(e, 'Could not revoke the token.')));
        }, { confirmText: 'Revoke', confirmClass: 'btn-danger' });
    }

    function loadAccounts() {
        const token = pageToken;
        call('GET', Config.API.serviceAccounts.list).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            accounts = list || [];
            drawAccounts();
        }).catch(e => { if (ContentRouter.isCurrent(token)) $('#api-access-accounts').html(off(e)); });
    }

    function drawAccounts() {
        if (!accounts.length) {
            $('#api-access-accounts').html('<p class="text-muted small mb-0">No service accounts yet.</p>');
            return;
        }
        const rows = accounts.map(a => Utils.html`<tr data-account="${a.userId}">
                <td data-label="Name"><strong>${a.name}</strong><div class="small text-muted">${a.email}</div></td>
                <td data-label="Role">${a.role === 'ENV_ADMIN' ? 'Environment admin' : 'User'}</td>
                <td data-label="Owner">${a.ownerName || '—'}</td>
                <td data-label="Tokens">${a.activeTokens}</td>
                <td data-label="State">${Utils.raw(a.active ? '<span class="badge text-bg-success">Active</span>' : '<span class="badge text-bg-light border">Deactivated</span>')}</td>
                <td class="td-actions">${Utils.raw(a.active ? Utils.html`
                    <button type="button" class="btn btn-sm btn-ghost" data-action="sa-token-new" data-id="${a.userId}">New token</button>
                    <button type="button" class="btn btn-sm btn-ghost text-danger" data-action="sa-deactivate" data-id="${a.userId}">Deactivate</button>` : '')}</td>
            </tr>`).join('');
        $('#api-access-accounts').html(`<p class="small text-muted">Grant a service account environments in Access Management like any user.</p>
            <div class="table-responsive"><table class="table table-sm table-cards" id="api-admin-accounts">
            <thead><tr><th>Name</th><th>Role</th><th>Owner</th><th>Active tokens</th><th>State</th><th></th></tr></thead>
            <tbody>${rows}</tbody></table></div>`);
    }

    function newAccount() {
        call('GET', Config.API.users.list).then(users => {
            const people = (users || []).filter(u => u.active && !u.serviceAccount);
            Modals.show({
                id: 'serviceAccountModal',
                title: 'New service account',
                body: `<form id="sa-form" novalidate>
                    <div class="mb-2"><label class="form-label small mb-1" for="sa-name">Name</label>
                        <input class="form-control form-control-sm" id="sa-name" maxlength="60" placeholder="e.g. ci-deployer">
                        <div class="form-text">Lower-case letters, digits and '-'.</div></div>
                    <div class="mb-2"><label class="form-label small mb-1" for="sa-role">Role</label>
                        <select class="form-select form-select-sm" id="sa-role"><option value="USER">User</option><option value="ENV_ADMIN">Environment admin</option></select></div>
                    <div class="mb-2"><label class="form-label small mb-1" for="sa-owner">Owner (responsible person)</label>
                        <select class="form-select form-select-sm" id="sa-owner">${people.map(u =>
                            Utils.html`<option value="${u.userId}">${u.displayName} (${u.email})</option>`).join('')}</select></div>
                    <div class="small text-danger" id="sa-error" role="alert"></div>
                </form>`,
                buttons: [
                    { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                    { text: 'Create', class: 'btn-primary', id: 'sa-create' }
                ]
            });
            $('#sa-create').on('click', () => {
                const body = { name: String($('#sa-name').val() || '').trim(), role: $('#sa-role').val(), ownerUserId: $('#sa-owner').val() };
                $('#sa-create').prop('disabled', true);
                call('POST', Config.API.serviceAccounts.list, body).then(() => {
                    Modals.hideWhenReady(document.getElementById('serviceAccountModal'));
                    Notifications.success('Service account created. Grant it environments in Access Management.');
                    loadAccounts();
                }).catch(e => {
                    $('#sa-create').prop('disabled', false);
                    $('#sa-error').text(message(e, 'The service account could not be created.'));
                });
            });
        }).catch(e => Notifications.showError(message(e, "Couldn't load the users.")));
    }

    function newToken(el) {
        const a = accounts.find(x => x.userId === el.dataset.id);
        if (!a) return;
        ApiTokensUi.openCreate('New token for ' + a.name, { admin: false, envAdmin: a.role === 'ENV_ADMIN' },
            body => call('POST', Config.API.serviceAccounts.tokens(a.userId), body), () => { loadTokens(); loadAccounts(); });
    }

    function deactivate(el) {
        const a = accounts.find(x => x.userId === el.dataset.id);
        if (!a) return;
        Modals.confirm('Deactivate service account', `Deactivate '${a.name}'? Its ${a.activeTokens} token(s) are revoked at once.`, () => {
            call('DELETE', Config.API.serviceAccounts.item(a.userId)).then(() => { loadAccounts(); loadTokens(); })
                .catch(e => Notifications.showError(message(e, 'Could not deactivate it.')));
        }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
    }

    return { load, setPage, revokeAny, newAccount, newToken, deactivate };
})();

window.ApiTokensUi = ApiTokensUi;
window.ApiAccess = ApiAccess;

Actions.registerAll({
    'api-token-new': () => ApiTokensUi.create(),
    'api-token-revoke': el => ApiTokensUi.revoke(el),
    'api-admin-revoke': el => ApiAccess.revokeAny(el),
    'api-admin-page': el => ApiAccess.setPage(el),
    'sa-new': () => ApiAccess.newAccount(),
    'sa-token-new': el => ApiAccess.newToken(el),
    'sa-deactivate': el => ApiAccess.deactivate(el)
});
