/**
 * API tokens in the UI (E34-T05).
 *   ApiTokensUi - the "API tokens" tab of My Account: the user's own tokens, create (name, scopes the
 *                 role allows, lifetime), a one-time reveal with Copy, revoke. Hidden while tokens are off.
 *   ApiAccess   - #/api-access, admins only (U14-T04, ApiAccess.dc.html / ApiTabs.dc.html): tabs
 *                 Tokens (every active token: name and prefix, owner, what it can do in words, last
 *                 used, expiry, Revoke), Service accounts (owner, active tokens, New token, Switch off /
 *                 Switch on) and Webhooks; a switched-off card while tokens are off, with no requests.
 * A token is shown once: in My Account's reveal dialog, on the admin page in a card above the list;
 * closing either removes the token from the page.
 */
const ApiTokenScopes = [
    ['environments:read', 'Read environments, VMs and operations'],
    ['operations:write', 'Start, stop and lock environments'],
    ['schedules:read', 'Read schedules'],
    ['schedules:write', 'Change schedules'],
    ['cost:read', 'Read cost and savings'],
    ['audit:read', 'Read the audit log'],
    ['assistant:read', 'Read-only AI assistant (MCP) access'],
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
            el.innerHTML = UiStates.loading('rows', { count: 2 });
            reload();
            return;
        }
        draw();
    }

    function reload() {
        return call('GET', Config.API.apiTokens.mine).then(list => { tokens = list || []; draw(); })
            .catch(e => { if (mountEl) mountEl.innerHTML = UiStates.failed({ what: 'Your tokens', xhr: e, onRetry: reload }); });
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
     * @param {function(object)} [onCreated] - shows the created token itself instead of the reveal dialog
     */
    function openCreate(title, role, send, onDone, onCreated) {
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
                if (onCreated) onCreated(created);
                else reveal(created.token);
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
    const TABS = [['tokens', 'Tokens'], ['accounts', 'Service accounts'], ['webhooks', 'Webhooks']];
    const SCOPE_WORDS = {
        'environments:read': 'read environments', 'operations:write': 'start and stop', 'schedules:read': 'read schedules',
        'schedules:write': 'change schedules', 'cost:read': 'read cost', 'audit:read': 'read the audit log',
        'assistant:read': 'ask the assistant', 'access:write': 'grant access', 'admin': 'everything'
    };
    let s = null; // {token, tab, page, tokenPage, accounts, revealed}

    // ---- pure model (node tests: src/test/js/api-access.test.mjs) ----

    /** "Read environments, start and stop" from ['environments:read', 'operations:write']. */
    function scopesText(scopes) {
        const text = (scopes || []).map(x => SCOPE_WORDS[x] || x).join(', ');
        return text ? text.charAt(0).toUpperCase() + text.slice(1) : '—';
    }

    /** "Today 06:00", "Yesterday 18:30", "Mon 09:00" within a week, else "18 Oct"; "Never". */
    function used(ts, now = new Date()) {
        if (!ts) return 'Never';
        const d = new Date(ts);
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const days = Math.round((new Date(now.getFullYear(), now.getMonth(), now.getDate())
            - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
        if (days === 0) return `Today ${time}`;
        if (days === 1) return `Yesterday ${time}`;
        if (days > 1 && days < 7) return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${time}`;
        return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
    }

    /** "18 Jan 2027". */
    function day(ts) {
        return ts ? new Date(ts).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';
    }

    /** "2 active tokens", "1 active token", "no tokens". */
    function tokensText(n) {
        const count = Number(n) || 0;
        return count ? `${count} active token${count === 1 ? '' : 's'}` : 'no tokens';
    }

    // ---- page ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    function on() {
        return typeof Capabilities === 'undefined' || Capabilities.has('apiTokens');
    }

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        s = { token: ContentRouter.token(), tab: TABS.some(([k]) => k === q.tab) ? q.tab : 'tokens', page: 1,
            tokenPage: null, accounts: null, loaded: {} };
        const enabled = on();
        $('#content-area').html(`
            <div class="content-view api-access-page" id="api-access-view">
                ${PageHeader.markup({ title: 'API access',
                    summaryHtml: 'Tokens for scripts and tools, accounts for services, and webhooks that tell other systems what happened.',
                    actionsHtml: enabled ? '<button type="button" class="btn btn-primary" id="api-new-token" data-action="api-admin-new-token">New token</button>' : '',
                    helpPage: typeof HelpLink !== 'undefined' ? 'api-access' : null, helpLabel: 'API access' })}
                <div id="api-access-body"></div>
            </div>`);
        // While API tokens are off, say so without asking for the lists (U02-T03).
        if (!enabled) {
            $('#api-access-body').html(`<section class="api-off" id="api-access-off" aria-labelledby="api-off-title">
                <h2 id="api-off-title">API tokens are switched off</h2>
                <p>Nobody can create or use a token until they are on. They are switched on in the server settings
                    (<code>API_TOKENS_ENABLED</code>), after the token secret (<code>API_TOKEN_PEPPER</code>) is set; the setup guide explains both steps.</p>
                <a class="btn btn-secondary" href="#/help/api-access">Setup guide</a>
            </section>`);
            return;
        }
        $('#api-access-body').html(`
            <div class="api-tabs" role="tablist" aria-label="API access">${TABS.map(([key, label]) => `<button type="button" role="tab"
                class="api-tab" id="api-tab-${key}" data-api-tab="${key}" aria-controls="api-panel-${key}" aria-selected="${key === s.tab}">${label}</button>`).join('')}
                <a class="api-ref" href="#/help/api-access">API reference</a></div>
            ${TABS.map(([key]) => `<section class="api-panel" id="api-panel-${key}" role="tabpanel" aria-labelledby="api-tab-${key}"${key === s.tab ? '' : ' hidden'}></section>`).join('')}`);
        $('#api-access-view').off('.api')
            .on('click.api', '[data-api-tab]', function() { switchTab(this.dataset.apiTab); })
            .on('click.api', '[data-action="api-admin-page"]', function() { s.page = Number(this.dataset.page) || 1; loadTokens(); })
            .on('click.api', '#api-reveal-copy', copyReveal)
            .on('click.api', '#api-reveal-close', () => $('#api-reveal').remove());
        ContentRouter.onLeave(() => $('#api-reveal').remove());
        show(s.tab);
    }

    function switchTab(tab) {
        if (!s || tab === s.tab) return;
        s.tab = tab;
        $('.api-tab').each(function() { this.setAttribute('aria-selected', String(this.dataset.apiTab === tab)); });
        TABS.forEach(([key]) => $(`#api-panel-${key}`).prop('hidden', key !== tab));
        $('#api-reveal').remove();
        try {
            history.replaceState(null, '', tab === 'tokens' ? '#/api-access' : `#/api-access?tab=${tab}`);
        } catch (e) { /* the address keeps the old tab */ }
        show(tab);
    }

    function show(tab) {
        $('#api-new-token').prop('hidden', tab !== 'tokens');
        if (s.loaded[tab]) return;
        s.loaded[tab] = true;
        if (tab === 'tokens') loadTokens();
        else if (tab === 'accounts') loadAccounts();
        else drawWebhooks();
    }

    // ---- Tokens

    function loadTokens() {
        const token = s.token;
        if (!$('#api-admin-tokens').length) $('#api-panel-tokens').html('<p class="api-note api-pad">Loading tokens…</p>');
        call('GET', Config.API.apiTokens.all(s.page - 1)).then(data => {
            if (!ContentRouter.isCurrent(token)) return;
            s.tokenPage = data;
            drawTokens();
        }).catch(e => {
            if (ContentRouter.isCurrent(token)) {
                $('#api-panel-tokens').html(UiStates.failed({ what: 'The tokens', xhr: e, onRetry: loadTokens }));
            }
        });
    }

    function drawTokens() {
        const items = (s.tokenPage && s.tokenPage.content) || [];
        const meta = (s.tokenPage && s.tokenPage.page) || {};
        const now = new Date();
        const rows = items.map(v => Utils.html`<tr data-token="${v.token.id}">
                <td data-label="Name"><div class="api-name">${v.token.name}</div><div class="api-mono api-sub">vmc_${v.token.prefix}…</div></td>
                <td data-label="Owner">${v.ownerName || v.ownerEmail || v.ownerUserId}${Utils.raw(v.ownerServiceAccount ? ' <span class="api-tag">Service</span>' : '')}</td>
                <td data-label="Can">${scopesText(v.token.scopes)}</td>
                <td data-label="Last used">${used(v.token.lastUsedAt, now)}</td>
                <td data-label="Expires">${day(v.token.expiresAt)}</td>
                <td class="api-actions"><button type="button" class="api-link api-danger" data-action="api-admin-revoke" data-id="${v.token.id}"
                    aria-label="Revoke ${v.token.name}">Revoke</button></td>
            </tr>`).join('');
        const total = Number(meta.totalElements) || items.length;
        const size = Number(meta.size) || 50;
        $('#api-panel-tokens').html(items.length ? `<div class="api-card"><div class="api-scroll"><table class="api-table" id="api-admin-tokens">
                <thead><tr><th scope="col">Name</th><th scope="col">Owner</th><th scope="col">Can</th><th scope="col">Last used</th><th scope="col">Expires</th>
                <th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div>
            ${total > size ? `<div class="api-foot">${Pagination.buildPager({ page: s.page, pageSize: size, totalItems: total, action: 'api-admin-page', key: 'tokens' })}</div>` : ''}</div>`
            : '<p class="api-note api-pad">No active tokens. New token makes one for you; service accounts get theirs on their tab.</p>');
    }

    function newToken() {
        ApiTokensUi.openCreate('New API token', { admin: true, envAdmin: true },
            body => call('POST', Config.API.apiTokens.mine, body), null, created => {
                revealInline('tokens', 'Token created for you', created);
                loadTokens();
            });
    }

    function revokeAny(el) {
        const v = ((s.tokenPage && s.tokenPage.content) || []).find(x => x.token.id === el.dataset.id);
        if (!v) return;
        Modals.confirm('Revoke token', `Revoke '${v.token.name}' of ${v.ownerName || v.ownerUserId}? It stops working at once.`, () => {
            call('DELETE', Config.API.apiTokens.item(v.token.id)).then(() => {
                Notifications.success('Token revoked.');
                loadTokens();
                if (s.loaded.accounts) loadAccounts();
            }).catch(e => Notifications.showError(message(e, 'Could not revoke the token.')));
        }, { confirmText: 'Revoke', confirmClass: 'btn-danger' });
    }

    /** A new token shown once inside the page; it leaves with the card, the tab or the page. */
    function revealInline(tab, title, created) {
        $('#api-reveal').remove();
        const days = created.expiresAt ? Math.max(1, Math.round((new Date(created.expiresAt).getTime() - Date.now()) / 86400000)) : null;
        // Above the panel, so reloading the list below does not take it away.
        $(`#api-panel-${tab}`).before(Utils.html`<div class="api-reveal" id="api-reveal" role="status">
                <div class="api-reveal-title">${title}</div>
                <div class="api-reveal-row"><code class="api-mono" id="api-reveal-value"></code>
                    <button type="button" class="api-link" id="api-reveal-copy">Copy</button></div>
                <div class="api-sub">Copy it now; it isn't shown again. Can: ${scopesText(created.scopes)}${days ? ` · ends in ${days} days` : ''}</div>
                <button type="button" class="api-link api-reveal-close" id="api-reveal-close">Done</button>
            </div>`);
        $('#api-reveal-value').text(created.token);
    }

    function copyReveal() {
        const text = $('#api-reveal-value').text();
        (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject())
            .then(() => Notifications.success('Token copied.'))
            .catch(() => {
                const range = document.createRange();
                range.selectNodeContents(document.getElementById('api-reveal-value'));
                const sel = window.getSelection();
                sel.removeAllRanges();
                sel.addRange(range);
            });
    }

    // ---- Service accounts

    function loadAccounts() {
        const token = s.token;
        call('GET', Config.API.serviceAccounts.list).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            s.accounts = list || [];
            drawAccounts();
        }).catch(e => {
            if (ContentRouter.isCurrent(token)) {
                $('#api-panel-accounts').html(UiStates.failed({ what: 'The service accounts', xhr: e, onRetry: loadAccounts }));
            }
        });
    }

    function drawAccounts() {
        const rows = s.accounts.map(a => Utils.html`<div class="api-account" data-account="${a.userId}">
                <div class="api-account-line"><span><b class="api-mono">${a.name}</b> <span class="api-sub">· ${a.role === 'ENV_ADMIN' ? 'Environment admin' : 'User'}</span></span>
                    <span class="api-state api-state-${a.active ? 'on' : 'off'}">${a.active ? 'Active' : 'Switched off'}</span></div>
                <div class="api-account-line"><span class="api-sub">Owner ${a.ownerName || '—'} · ${tokensText(a.activeTokens)}</span>
                    <span class="api-account-actions">${Utils.raw(a.active
                        ? Utils.html`<button type="button" class="api-link" data-action="sa-token-new" data-id="${a.userId}" aria-label="New token for ${a.name}">New token</button>
                            <button type="button" class="api-link api-danger" data-action="sa-deactivate" data-id="${a.userId}" aria-label="Switch off ${a.name}">Switch off</button>`
                        : Utils.html`<button type="button" class="api-link" data-action="sa-reactivate" data-id="${a.userId}" aria-label="Switch on ${a.name}">Switch on</button>`)}</span></div>
            </div>`).join('');
        $('#api-panel-accounts').html(`<section class="api-card" aria-labelledby="api-sa-title" id="api-admin-accounts">
                <div class="api-card-head"><div><h2 id="api-sa-title">Service accounts</h2>
                    <div class="api-sub">For pipelines and tools. Each has a person who owns it. Give one environments in Access management like a person.</div></div>
                    <button type="button" class="btn btn-secondary btn-sm" data-action="sa-new">New service account</button></div>
                ${rows || '<p class="api-note api-pad">No service accounts yet.</p>'}
            </section>`);
    }

    function newAccount() {
        call('GET', Config.API.users.list).then(users => {
            const people = (users || []).filter(u => u.active !== false && u.isActive !== false && !u.serviceAccount);
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
                    Notifications.success('Service account created. Give it environments in Access management.');
                    loadAccounts();
                }).catch(e => {
                    $('#sa-create').prop('disabled', false);
                    $('#sa-error').text(message(e, 'The service account could not be created.'));
                });
            });
        }).catch(e => Notifications.showError(message(e, "Couldn't load the users.")));
    }

    function account(el) {
        return (s.accounts || []).find(x => x.userId === el.dataset.id);
    }

    function newAccountToken(el) {
        const a = account(el);
        if (!a) return;
        ApiTokensUi.openCreate('New token for ' + a.name, { admin: false, envAdmin: a.role === 'ENV_ADMIN' },
            body => call('POST', Config.API.serviceAccounts.tokens(a.userId), body), null, created => {
                revealInline('accounts', `Token created for ${a.name}`, created);
                loadAccounts();
                if (s.loaded.tokens) loadTokens();
            });
    }

    function deactivate(el) {
        const a = account(el);
        if (!a) return;
        Modals.confirm('Switch off service account', `Switch off '${a.name}'? Its ${tokensText(a.activeTokens)} stop working at once and stay revoked if you switch it on again.`, () => {
            call('DELETE', Config.API.serviceAccounts.item(a.userId)).then(() => {
                loadAccounts();
                if (s.loaded.tokens) loadTokens();
            }).catch(e => Notifications.showError(message(e, 'Could not switch it off.')));
        }, { confirmText: 'Switch off', confirmClass: 'btn-danger' });
    }

    function reactivate(el) {
        const a = account(el);
        if (!a) return;
        call('POST', Config.API.serviceAccounts.reactivate(a.userId)).then(() => {
            Notifications.success(`${a.name} is on. Its earlier tokens stay revoked: make a new one.`);
            loadAccounts();
        }).catch(e => Notifications.showError(message(e, 'Could not switch it on.')));
    }

    // ---- Webhooks (U14-T05): every webhook, its events, Send test and recent deliveries

    function drawWebhooks() {
        if (typeof WebhooksPanel !== 'undefined') WebhooksPanel.mountAll('#api-panel-webhooks');
    }

    return { load, newToken, revokeAny, newAccount, newAccountToken, deactivate, reactivate,
        model: { scopesText, used, day, tokensText } };
})();

if (typeof window !== 'undefined') {
    window.ApiTokensUi = ApiTokensUi;
    window.ApiAccess = ApiAccess;
}

if (typeof Actions !== 'undefined') Actions.registerAll({
    'api-token-new': () => ApiTokensUi.create(),
    'api-token-revoke': el => ApiTokensUi.revoke(el),
    'api-admin-new-token': () => ApiAccess.newToken(),
    'api-admin-revoke': el => ApiAccess.revokeAny(el),
    'sa-new': () => ApiAccess.newAccount(),
    'sa-token-new': el => ApiAccess.newAccountToken(el),
    'sa-deactivate': el => ApiAccess.deactivate(el),
    'sa-reactivate': el => ApiAccess.reactivate(el)
});
