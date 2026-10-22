/**
 * Approval policies page (E25-T06, U12-T06 ApprovalPolicies.dc.html), #/approval-policies: each
 * policy as a card in words ("Needs approval when a run or extension is longer than 8 hours",
 * "Decided by any one of Sanjay Rao or Meera Iyer") with Edit / Delete, and "Check a request"
 * beside them: which policy a start would meet, before anyone asks. Admins see every policy;
 * environment admins those of the environments they administer. Approvers are picked by name
 * (users/search, open to environment admins), a team or the environment admins.
 */
const ApprovalPolicies = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;
    let state = null;
    let searchTimer = null;

    // ------------------------------------------------------------------ model

    /** "A", "A or B", "A, B or C" (word = 'or' / 'and'). */
    function joinWords(items, word) {
        const list = (items || []).filter(Boolean);
        if (list.length <= 1) return list.join('');
        return `${list.slice(0, -1).join(', ')} ${word} ${list[list.length - 1]}`;
    }

    function money(n) {
        const v = Number(n);
        return Number.isInteger(v) ? `$${v}` : `$${v.toFixed(2)}`;
    }

    /** The conditions in words, joined by "or" (any one of them asks for approval). */
    function whenText(p) {
        const parts = [];
        if (p.maxRuntimeHours) parts.push(`a run or extension is longer than ${p.maxRuntimeHours} hour${p.maxRuntimeHours === 1 ? '' : 's'}`);
        if (p.instanceTypePatterns && p.instanceTypePatterns.length) parts.push(`any VM matches ${joinWords(p.instanceTypePatterns, 'or')}`);
        if (p.minVcpu) parts.push(`any VM has ${p.minVcpu} vCPU or more`);
        if (p.maxEstimatedCost !== null && p.maxEstimatedCost !== undefined) parts.push(`the estimated cost is over ${money(p.maxEstimatedCost)}`);
        return parts.length ? joinWords(parts, 'or') : 'anything starts or is extended';
    }

    /** "any one of A or B", "both A and B", "all of A, B and C", or "A". */
    function whoText(p) {
        const names = (p.approvers || []).map(a => a.label).filter(Boolean);
        if (!names.length) return 'administrators';
        if (names.length === 1) return names[0];
        if (p.approverMode === 'ALL') return names.length === 2 ? `both ${names[0]} and ${names[1]}` : `all of ${joinWords(names, 'and')}`;
        return `any one of ${joinWords(names, 'or')}`;
    }

    function scopeText(p) {
        if (p.environmentName || p.environmentId) return p.environmentName || p.environmentId;
        if (p.teamName || p.teamId) return `${p.teamName || p.teamId} team`;
        return 'All environments you look after';
    }

    /** The answer of POST /approval-policies/evaluate in words, one line per policy met. */
    function checkLines(decision, hours) {
        if (!decision || !decision.requiresApproval) {
            return decision && decision.blocked ? (decision.warnings || ['This start is not allowed.']) : ['No approval needed.'];
        }
        return (decision.matches || []).map(m => {
            const names = m.approverNames || [];
            const who = !names.length ? '' : names.length === 1 ? ` ${names[0]} decides.`
                : m.approverMode === 'ALL' ? ` All of ${joinWords(names, 'and')}.` : ` Any one of ${joinWords(names, 'or')}.`;
            const why = (m.reasons || []).join('; ');
            return `Needs approval: ${m.name}${why ? ` · ${why}` : ''}.${who}`.replace('..', '.');
        });
    }

    // ------------------------------------------------------------------ page

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function current() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }
        state = { environments: [], teams: [], policies: null, page: 1, showName: null };
        const off = typeof Capabilities !== 'undefined' && !Capabilities.has('approvals');
        $('#content-area').html(`
            <div class="content-view ap-page" id="approval-policies-view">
                ${PageHeader.markup({ title: 'Approval policies',
                    summaryHtml: "When a start or an extension needs someone's yes before it runs.",
                    actionsHtml: `<button type="button" class="btn btn-primary" data-action="policy-new" id="ap-new">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New policy</button>` })}
                ${off ? '<p class="ap-off" id="ap-off" role="status">Approvals are switched off for the platform. Policies are kept but not applied.</p>' : ''}
                <div class="ap-layout">
                    <div class="ap-list" id="policy-list"><div class="ar-empty" role="status">Loading policies…</div></div>
                    <section class="ap-check" aria-labelledby="ap-check-title">
                        <div>
                            <h2 id="ap-check-title">Check a request</h2>
                            <p>See which policy a start would meet, before anyone asks.</p>
                        </div>
                        <label class="ap-field">Environment<select id="dry-env" class="form-select"></select></label>
                        <label class="ap-field">Run for (hours)<input id="dry-hours" type="number" min="1" max="720" value="24" class="form-control ap-mono"></label>
                        <button type="button" class="btn btn-outline-primary" data-action="policy-dry-run">Check</button>
                        <div id="dry-result" aria-live="polite"></div>
                    </section>
                </div>
            </div>`);
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.environments.list), call('GET', Config.API.teams.list(false))]).then(([envs, teams]) => {
            if (!ContentRouter.isCurrent(token)) return;
            const all = envs.status === 'fulfilled' ? (envs.value || []) : [];
            state.environments = all.filter(e => e.viewerCanAdminister !== false && e.isActive !== false);
            state.teams = teams.status === 'fulfilled' ? (teams.value || []) : [];
            $('#dry-env').html(state.environments.map(e => Utils.html`<option value="${e.environmentId}">${e.displayName || e.name}</option>`).join(''));
            refresh();
        });
    }

    /** Admins read every policy; environment admins each administered environment's own. */
    async function fetchPolicies() {
        if (Auth.isAdmin()) return await call('GET', Config.API.approvalPolicies.list('')) || [];
        const lists = await Promise.all(state.environments.map(e =>
            call('GET', Config.API.approvalPolicies.list(e.environmentId)).catch(() => [])));
        const seen = new Set();
        return [].concat(...lists).filter(p => !seen.has(p.policyId) && seen.add(p.policyId))
            .sort((a, b) => a.name.localeCompare(b.name));
    }

    function refresh() {
        const token = pageToken;
        fetchPolicies().then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            state.policies = list;
            draw();
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#policy-list').html(UiStates.failed({ what: 'The policies', xhr: e, onRetry: refresh }));
        });
    }

    // Three policies fit 1440 x 900 without a page scrollbar (U17-T07); after a save, its page opens.
    const POLICIES_PER_PAGE = 3;

    function draw() {
        const all = state.policies;
        if (state.showName) {
            const at = all.findIndex(p => p.name === state.showName);
            if (at >= 0) state.page = Math.floor(at / POLICIES_PER_PAGE) + 1;
            state.showName = null;
        }
        const pages = Math.max(1, Math.ceil(all.length / POLICIES_PER_PAGE));
        state.page = Math.min(Math.max(1, state.page || 1), pages);
        const pager = all.length > POLICIES_PER_PAGE ? Pagination.buildPager({ page: state.page, pageSize: POLICIES_PER_PAGE,
            totalItems: all.length, action: 'policy-page', key: 'policies' }) : '';
        $('#policy-list').html(all.length ? Pagination.slice(all, state.page, POLICIES_PER_PAGE).map(p => Utils.html`
            <article class="ap-card${Utils.raw(p.active ? '' : ' ap-card-off')}" data-policy-id="${p.policyId}">
                <div class="ap-card-head">
                    <h2>${p.name}${Utils.raw(p.active ? '' : ' <span class="ap-chip-off">Off</span>')}</h2>
                    <span class="ap-scope">${scopeText(p)}</span>
                </div>
                <div class="ap-line ap-when">Needs approval when ${whenText(p)}</div>
                <div class="ap-line ap-who">Decided by ${whoText(p)}</div>
                <div class="ap-actions">
                    <button type="button" class="ap-link" data-action="policy-edit" data-id="${p.policyId}" aria-label="Edit ${p.name}">Edit</button>
                    <button type="button" class="ap-link ap-danger" data-action="policy-delete" data-id="${p.policyId}" aria-label="Delete ${p.name}">Delete</button>
                </div>
            </article>`).join('') + pager
            : '<div class="ar-empty">No policies yet: starts and extensions run without anyone\'s yes. Add one with New policy.</div>');
    }

    // ---- form

    function chipHtml(a) {
        return Utils.html`<span class="pol-chip" data-type="${a.type}" data-id="${a.approverId || ''}">${a.label}
            <button type="button" class="pol-chip-x" aria-label="Remove ${a.label}">×</button></span>`;
    }

    function readApprovers() {
        return $('#pol-approvers .pol-chip').map(function() {
            return { type: this.dataset.type, approverId: this.dataset.id || null };
        }).get();
    }

    function addApprover(a) {
        const key = `${a.type}:${a.approverId || ''}`;
        if (readApprovers().some(x => `${x.type}:${x.approverId || ''}` === key)) return;
        $('#pol-approvers').append(chipHtml(a));
        $('#pol-approvers-empty').prop('hidden', true);
    }

    function openForm(policyId) {
        const p = policyId ? state.policies.find(x => x.policyId === policyId) : null;
        const scopeOptions = (Auth.isAdmin() ? '<option value="">All environments</option>' : '') + state.environments.map(e =>
            Utils.html`<option value="ENV:${e.environmentId}">${e.displayName || e.name}</option>`).join('')
            + (Auth.isAdmin() ? state.teams.map(t => Utils.html`<option value="TEAM:${t.teamId}">${t.displayName} team</option>`).join('') : '');
        const currentScope = p ? (p.environmentId ? 'ENV:' + p.environmentId : p.teamId ? 'TEAM:' + p.teamId : '')
            : (Auth.isAdmin() ? '' : (state.environments[0] ? 'ENV:' + state.environments[0].environmentId : ''));
        const approvers = p ? p.approvers : [{ type: 'ENV_ADMINS', approverId: null, label: 'Environment admins' }];
        const groups = '<option value="">Add a group…</option><option value="ENV_ADMINS:">Environment admins</option>'
            + state.teams.map(t => Utils.html`<option value="TEAM:${t.teamId}">${t.displayName} team</option>`).join('');
        Modals.show({
            id: 'policyModal',
            title: p ? `Edit ${p.name}` : 'New policy',
            size: 'lg',
            body: Utils.html`
                <div class="pol-form">
                    <div class="pol-grid">
                        <label class="ap-field">Name<input class="form-control" id="pol-name" maxlength="100" value="${p ? p.name : ''}" required></label>
                        <label class="ap-field">Applies to<select class="form-select" id="pol-scope" ${p ? 'disabled' : ''}>${Utils.raw(scopeOptions)}</select></label>
                    </div>
                    <fieldset class="pol-fieldset"><legend>Needs approval when any of these is true</legend>
                        <div class="pol-grid">
                            <label class="ap-field">A run or extension is longer than (hours)
                                <input class="form-control" id="pol-hours" type="number" min="1" value="${p && p.maxRuntimeHours ? p.maxRuntimeHours : ''}"></label>
                            <label class="ap-field">The estimated cost is over ($)
                                <input class="form-control" id="pol-cost" type="number" min="0" step="0.01" value="${p && p.maxEstimatedCost !== null ? p.maxEstimatedCost : ''}"></label>
                            <label class="ap-field">Any VM's type matches
                                <input class="form-control" id="pol-patterns" placeholder="*.8xlarge, p*" value="${p ? p.instanceTypePatterns.join(', ') : ''}">
                                <span class="pol-hint">Separate with commas; * matches anything.</span></label>
                            <label class="ap-field">Any VM has this many vCPU or more
                                <input class="form-control" id="pol-vcpu" type="number" min="1" value="${p && p.minVcpu ? p.minVcpu : ''}"></label>
                        </div>
                    </fieldset>
                    <fieldset class="pol-fieldset"><legend>Decided by</legend>
                        <div class="pol-chips" id="pol-approvers" aria-live="polite">${Utils.raw(approvers.map(chipHtml).join(''))}</div>
                        <p class="pol-hint" id="pol-approvers-empty"${Utils.raw(approvers.length ? ' hidden' : '')}>No one picked: administrators decide.</p>
                        <div class="pol-grid">
                            <div class="ap-field pol-search">
                                <label for="pol-approver-search">Add a person</label>
                                <input class="form-control" id="pol-approver-search" type="search" autocomplete="off" placeholder="Type a name or email"
                                       role="combobox" aria-expanded="false" aria-controls="pol-approver-results" aria-autocomplete="list">
                                <ul class="pol-results" id="pol-approver-results" role="listbox" aria-label="People" hidden></ul>
                            </div>
                            <label class="ap-field">Add a group<select class="form-select" id="pol-approver-group">${Utils.raw(groups)}</select></label>
                        </div>
                        <label class="ap-field pol-mode">When there is more than one
                            <select class="form-select" id="pol-mode"><option value="ANY">Any one of them decides</option>
                                <option value="ALL" ${p && p.approverMode === 'ALL' ? 'selected' : ''}>All of them must agree</option></select></label>
                        <p class="pol-hint">The person asking never approves their own request.</p>
                    </fieldset>
                    <label class="re-check"><input type="checkbox" id="pol-active" ${!p || p.active ? 'checked' : ''}> On</label>
                    <div class="re-error" id="pol-error" role="alert" hidden></div>
                </div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: p ? 'Save policy' : 'Create policy', class: 'btn-primary', id: 'pol-save' }],
            onShow: () => $('#pol-name').trigger('focus')
        });
        $('#pol-scope').val(currentScope);
        bindForm(p);
    }

    function showResults(users) {
        const $list = $('#pol-approver-results');
        const list = (users || []).filter(u => u.isActive !== false).slice(0, 8);
        $list.html(list.length ? list.map((u, i) => Utils.html`<li role="option" id="pol-opt-${i}" class="pol-option" tabindex="-1"
                data-id="${u.userId}" data-label="${u.displayName || u.email}">${u.displayName || u.email}${Utils.raw(u.displayName && u.email
                ? Utils.html` <span>${u.email}</span>` : '')}</li>`).join('')
            : '<li class="pol-option-none" role="option" aria-disabled="true">No one matches</li>').prop('hidden', false);
        $('#pol-approver-search').attr('aria-expanded', 'true');
    }

    function hideResults() {
        $('#pol-approver-results').prop('hidden', true).empty();
        $('#pol-approver-search').attr('aria-expanded', 'false');
    }

    function pick($option) {
        addApprover({ type: 'USER', approverId: $option.data('id'), label: String($option.data('label')) });
        $('#pol-approver-search').val('').trigger('focus');
        hideResults();
    }

    function bindForm(p) {
        const $modal = $('#policyModal');
        $modal.on('click', '.pol-chip-x', function() {
            $(this).closest('.pol-chip').remove();
            $('#pol-approvers-empty').prop('hidden', readApprovers().length > 0);
            $('#pol-approver-search').trigger('focus');
        });
        $modal.on('change', '#pol-approver-group', function() {
            const v = this.value;
            if (!v) return;
            const i = v.indexOf(':');
            addApprover({ type: v.slice(0, i), approverId: v.slice(i + 1) || null, label: $(this).find('option:selected').text() });
            this.value = '';
        });
        $modal.on('input', '#pol-approver-search', function() {
            const q = this.value.trim();
            clearTimeout(searchTimer);
            if (q.length < 2) { hideResults(); return; }
            searchTimer = setTimeout(() => {
                call('GET', Config.API.users.search(q)).then(users => {
                    if ($('#pol-approver-search').val().trim() === q) showResults(users);
                }).catch(e => {
                    $('#pol-approver-results').html(Utils.html`<li class="pol-option-none" role="option" aria-disabled="true">${serverMessage(e, 'Search failed.')}</li>`).prop('hidden', false);
                });
            }, 250);
        });
        $modal.on('keydown', '#pol-approver-search, .pol-option', function(e) {
            const $options = $('#pol-approver-results .pol-option');
            if (e.key === 'Escape' && !$('#pol-approver-results').prop('hidden')) { e.preventDefault(); e.stopPropagation(); hideResults(); return; }
            if (!$options.length) return;
            const i = $options.index(this);
            if (e.key === 'ArrowDown') { e.preventDefault(); $options.eq(Math.min(i + 1, $options.length - 1)).trigger('focus'); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); if (i <= 0) $('#pol-approver-search').trigger('focus'); else $options.eq(i - 1).trigger('focus'); }
            else if (e.key === 'Enter' && i >= 0) { e.preventDefault(); pick($(this)); }
        });
        $modal.on('click', '.pol-option', function() { pick($(this)); });
        $('#pol-save').on('click', function() {
            const scope = $('#pol-scope').val() || '';
            const num = sel => { const v = $(sel).val(); return v === '' ? null : Number(v); };
            const body = {
                name: $('#pol-name').val().trim(),
                environmentId: scope.startsWith('ENV:') ? scope.slice(4) : null,
                teamId: scope.startsWith('TEAM:') ? scope.slice(5) : null,
                instanceTypePatterns: $('#pol-patterns').val().split(',').map(s => s.trim()).filter(Boolean),
                minVcpu: num('#pol-vcpu'), maxRuntimeHours: num('#pol-hours'), maxEstimatedCost: num('#pol-cost'),
                approverMode: $('#pol-mode').val(),
                approvers: readApprovers(),
                active: $('#pol-active').is(':checked')
            };
            if (!body.name) {
                $('#pol-error').text('Give the policy a name.').prop('hidden', false);
                $('#pol-name').trigger('focus');
                return;
            }
            const $btn = $(this).prop('disabled', true);
            (p ? call('PUT', Config.API.approvalPolicies.item(p.policyId), body) : call('POST', Config.API.approvalPolicies.create, body))
                .then(() => {
                    bootstrap.Modal.getInstance(document.getElementById('policyModal')).hide();
                    Notifications.success(p ? 'Policy saved.' : 'Policy created.');
                    state.showName = body.name;
                    if (current()) refresh();
                })
                .catch(e => { $('#pol-error').text(serverMessage(e, 'Could not save the policy.')).prop('hidden', false); $btn.prop('disabled', false); });
        });
    }

    function remove(policyId) {
        const p = state.policies.find(x => x.policyId === policyId);
        if (!p) return;
        Modals.confirm('Delete policy', `Delete "${p.name}"? Starts it matched no longer need approval.`, () => {
            call('DELETE', Config.API.approvalPolicies.item(policyId))
                .then(() => { Notifications.success('Policy deleted.'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not delete the policy.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    function dryRun() {
        const environmentId = $('#dry-env').val();
        if (!environmentId) return;
        const hours = Number($('#dry-hours').val()) || null;
        call('POST', Config.API.approvalPolicies.evaluate, { environmentId, hours })
            .then(d => {
                const needs = d && d.requiresApproval;
                const off = !needs && typeof Capabilities !== 'undefined' && !Capabilities.has('approvals')
                    ? ['Approvals are switched off for the platform, so no policy applies.'] : [];
                $('#dry-result').html(`<div class="ap-outcome${needs ? ' ap-outcome-needs' : ''}" id="dry-outcome">${checkLines(d, hours).concat(off)
                    .map(line => Utils.html`<p>${line}</p>`).join('')}</div>`);
            })
            .catch(e => $('#dry-result').html(Utils.html`<div class="ap-outcome" id="dry-outcome">${serverMessage(e, 'Could not check.')}</div>`));
    }

    function goToPage(n) {
        if (!state || !state.policies) return;
        state.page = Number(n) || 1;
        draw();
    }

    return { load, openForm, remove, dryRun, goToPage, model: { joinWords, whenText, whoText, scopeText, checkLines } };
})();

window.ApprovalPolicies = ApprovalPolicies;

if (typeof Actions !== 'undefined') {
    Actions.registerAll({
        'policy-new': () => ApprovalPolicies.openForm(null),
        'policy-edit': el => ApprovalPolicies.openForm(el.dataset.id),
        'policy-delete': el => ApprovalPolicies.remove(el.dataset.id),
        'policy-dry-run': () => ApprovalPolicies.dryRun(),
        'policy-page': el => { if (!el.disabled) ApprovalPolicies.goToPage(el.dataset.page); }
    });
}
