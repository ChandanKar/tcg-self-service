/**
 * VM Self-Service Platform - Request access (U09-T02, RequestAccess.dc.html)
 * Two tabs. "Request": the environments you can ask for on the left (search, 10 to a page, a tag
 * "Request waiting" or "You can view"), and on the right the form for the one you picked: Operator
 * or View only, the whole environment or one group, for 7, 30 or 90 days, and why. "Your requests":
 * one line per request ("Operator on Analytics QA, 30 days · sent 2 h ago to Sanjay Rao") with
 * Cancel request, Open <environment> or Request again. Other pages open it with
 * #/request-access?env=<id>&group=<id>&level=USER|VIEWER (and tab=mine). The words are pure
 * (RequestAccess.model), tested in src/test/js/request-access.test.mjs.
 */
const RequestAccess = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const DURATIONS = [7, 30, 90];
    const LEVEL = { USER: 'Operator', VIEWER: 'View only', ADMIN: 'Admin' };
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    function envName(env) {
        return env.displayName || env.name || env.environmentName || 'this environment';
    }

    /** "2 h", "5 min", "3 days". */
    function since(ts, now = new Date()) {
        const t = ts ? new Date(ts).getTime() : NaN;
        if (Number.isNaN(t)) return '';
        const m = Math.max(0, Math.round((now.getTime() - t) / 60000));
        if (m < 60) return `${Math.max(1, m)} min`;
        const h = Math.round(m / 60);
        return h < 48 ? `${h} h` : `${Math.round(h / 24)} days`;
    }

    function day(ts) {
        const d = ts ? new Date(ts) : null;
        return d && !Number.isNaN(d.getTime()) ? `${d.getDate()} ${MONTHS[d.getMonth()]}` : '';
    }

    /** "A", "A and B", "A, B and C". */
    function joinNames(names) {
        const n = (names || []).filter(Boolean);
        if (n.length <= 1) return n.join('');
        return `${n.slice(0, -1).join(', ')} and ${n[n.length - 1]}`;
    }

    /** "Data platform QA · 5 VMs" / "EKS · 3 node groups". */
    function envDescription(env) {
        const eks = String(env.serviceType || '').toUpperCase() === 'EKS';
        const count = Number(env.vmCount) || 0;
        const size = eks ? `${count} node group${count === 1 ? '' : 's'}` : `${count} VM${count === 1 ? '' : 's'}`;
        return [eks ? 'EKS' : env.description, size].filter(Boolean).join(' · ');
    }

    /** The tag on a list row. */
    function envTag(env, waitingEnvIds, viewEnvIds) {
        if (waitingEnvIds.has(env.environmentId)) return 'Request waiting';
        if (viewEnvIds.has(env.environmentId)) return 'You can view';
        return '';
    }

    /** The lengths offered, up to the longest a request may ask for. */
    function durations(maxDays) {
        const max = Number(maxDays) || 90;
        const list = DURATIONS.filter(d => d <= max);
        return list.length ? list : [max];
    }

    /** null when the reason is fine, else what to fix. */
    function reasonProblem(text) {
        const t = String(text || '').trim();
        if (t.length < 10) return 'Say a little more: at least 10 characters.';
        if (t.length > 1000) return 'Keep it under 1000 characters.';
        return null;
    }

    /**
     * One request as {chip, tone, html-free parts}: {chip, tone, level, env, extra, action:{kind,label}}.
     * "Operator on Analytics QA, 30 days · sent 2 h ago to Sanjay Rao".
     */
    function requestLine(req, now = new Date()) {
        const level = LEVEL[req.requestedAccessLevel] || 'Operator';
        const scope = req.scopeType === 'GROUP' && req.scopeName ? `, ${req.scopeName} group` : '';
        const days = req.durationDays ? `, ${req.durationDays} days` : '';
        const what = `${scope}${days}`;
        const env = req.environmentName || 'an environment';
        switch (req.status) {
            case 'PENDING': {
                const to = joinNames(req.reviewerNames);
                return { chip: 'Waiting', tone: 'wait', level, env, what,
                    extra: `sent ${since(req.createdAt, now)} ago${to ? ` to ${to}` : ''}`, action: { kind: 'cancel', label: 'Cancel request' } };
            }
            case 'APPROVED':
                return { chip: 'Approved', tone: 'ok', level, env, what,
                    extra: `approved${req.reviewedByUserName ? ` by ${req.reviewedByUserName}` : ''}${req.reviewedAt ? ` on ${day(req.reviewedAt)}` : ''}`,
                    action: { kind: 'open', label: `Open ${env}` } };
            case 'DENIED':
                return { chip: 'Denied', tone: 'no', level, env, what,
                    extra: req.reviewDecisionNotes ? `"${req.reviewDecisionNotes}"` : `denied${req.reviewedByUserName ? ` by ${req.reviewedByUserName}` : ''}`,
                    action: { kind: 'again', label: 'Request again' } };
            case 'CANCELLED':
                return { chip: 'Cancelled', tone: 'off', level, env, what, extra: `cancelled ${day(req.reviewedAt || req.createdAt)}`.trim(),
                    action: { kind: 'again', label: 'Request again' } };
            default:
                return { chip: 'Ended', tone: 'off', level, env, what, extra: '', action: { kind: 'again', label: 'Request again' } };
        }
    }

    // ---------------------------------------------------------------- the page

    let s = null;   // page state

    /** Router loader (see the page contract in core/router.js). */
    async function load() {
        const token = ContentRouter.token();
        ContentRouter.onLeave(() => $('#content-area').off('.reqAccess'));
        const q = ContentRouter.query ? ContentRouter.query() : {};
        s = {
            tab: q.tab === 'mine' ? 'mine' : 'new',
            envs: [], requests: [], viewEnvIds: new Set(), waitingEnvIds: new Set(),
            selected: q.env || q.environmentId || null,
            preset: { group: q.group || null, groupName: q.groupName || null, level: q.level || null, name: q.name || null },
            query: '', page: 0, minePage: 0, maxDays: 90, groups: {}, sending: false, errors: {}
        };
        $('#content-area').html('<div class="loading-state"><div class="spinner-border text-primary" role="status"></div><p>Loading…</p></div>');
        try {
            const [available, mine, grants, profile] = await Promise.all([
                get(Config.API.environments.available, []),
                get(Config.API.access.myRequests, []),
                get(Config.API.access.myEnvironments, []),
                get(Config.API.users.myProfile, null)
            ]);
            if (!ContentRouter.isCurrent(token)) return;
            s.maxDays = (profile && profile.requestMaxDurationDays) || 90;
            setData(available, mine, grants);
            render();
            bind();
        } catch (e) {
            if (!ContentRouter.isCurrent(token)) return;
            $('#content-area').html('<div class="error-state"><h4>Couldn\'t load Request access</h4><p>Try again in a moment.</p></div>');
        }
    }

    function get(url, fallback) {
        return new Promise(resolve => {
            ApiClient.get(url, { suppressGlobalError: true }).done(d => resolve(d || fallback)).fail(() => resolve(fallback));
        });
    }

    /** Available environments plus those you can only view (to ask for Operator). */
    function setData(available, mine, grants) {
        s.requests = (mine || []).slice().sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
        s.waitingEnvIds = new Set(s.requests.filter(r => r.status === 'PENDING').map(r => r.environmentId));
        const byId = new Map((available || []).map(e => [e.environmentId, e]));
        const levels = {};
        (grants || []).filter(g => g.status === 'ACTIVE').forEach(g => {
            const rank = { VIEWER: 1, USER: 2, ADMIN: 3 }[g.accessLevel] || 0;
            levels[g.environmentId] = Math.max(levels[g.environmentId] || 0, rank);
        });
        s.viewEnvIds = new Set(Object.keys(levels).filter(id => levels[id] === 1));
        (grants || []).filter(g => s.viewEnvIds.has(g.environmentId) && !byId.has(g.environmentId))
            .forEach(g => byId.set(g.environmentId, { environmentId: g.environmentId, displayName: g.environmentName, vmCount: null }));
        s.envs = Array.from(byId.values()).sort((a, b) => envName(a).localeCompare(envName(b)));
        if (s.selected && !byId.has(s.selected) && s.preset.name) {
            s.envs.unshift({ environmentId: s.selected, displayName: s.preset.name });
        }
        if (!s.selected || !s.envs.some(e => e.environmentId === s.selected)) s.selected = s.envs.length ? s.envs[0].environmentId : null;
        // Show the page of the selected environment.
        const index = s.envs.findIndex(e => e.environmentId === s.selected);
        s.page = index > 0 ? Math.floor(index / PAGE_SIZE) : 0;
    }

    function filtered() {
        const q = s.query.toLowerCase();
        return q ? s.envs.filter(e => [envName(e), e.name, e.description].some(v => String(v || '').toLowerCase().includes(q))) : s.envs;
    }

    function render() {
        const help = typeof HelpLink !== 'undefined' ? HelpLink.button('request-access', 'Request access') : '';
        const tabs = Tabs.markup({ id: 'ra-tabs', label: 'Request access', active: s.tab, tabs: [
            { key: 'new', label: 'Request' }, { key: 'mine', label: `Your requests · ${s.requests.length}` }] });
        $('#content-area').html(`
            <div class="ra-page">
                <div class="content-header">
                    <h1>Request access ${help}</h1>
                    <p class="text-muted">Pick an environment and say why. Its owners get your request and you hear back here and by email.</p>
                </div>
                ${tabs}
                ${Tabs.panel({ id: 'ra-tabs', key: 'new', active: s.tab }, newHtml())}
                ${Tabs.panel({ id: 'ra-tabs', key: 'mine', active: s.tab }, mineHtml())}
            </div>`);
        Tabs.bind(document.getElementById('ra-tabs'), key => { s.tab = key; renderPagers(); });
        renderPagers();
        loadGroups();
    }

    function newHtml() {
        return `<div class="ra-split">
                <section class="ra-card ra-list-card" aria-labelledby="ra-avail-title">
                    <div class="ra-card-head">
                        <h2 id="ra-avail-title">Environments</h2>
                        <label class="ra-search"><span class="visually-hidden">Search environments</span>
                            <input type="search" id="ra-search" placeholder="Search" value="${Utils.escapeHtml(s.query)}" autocomplete="off"></label>
                    </div>
                    <div id="ra-env-list">${listHtml()}</div>
                    <div class="pagination-bar-wrap" id="ra-env-pager"></div>
                </section>
                <section class="ra-card ra-form-card" aria-labelledby="ra-form-title" id="ra-form-card">${formHtml()}</section>
            </div>`;
    }

    function listHtml() {
        const list = filtered();
        const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
        if (s.page >= pages) s.page = pages - 1;
        if (!s.envs.length) return '<p class="ra-empty">You can use every environment.</p>';
        if (!list.length) {
            return Utils.html`<p class="ra-empty">No environments match "${s.query}". <button type="button" class="btn btn-link p-0 align-baseline" data-ra-clear>Clear search</button></p>`;
        }
        return list.slice(s.page * PAGE_SIZE, (s.page + 1) * PAGE_SIZE).map(env => {
            const tag = envTag(env, s.waitingEnvIds, s.viewEnvIds);
            return Utils.html`<button type="button" class="ra-env" data-ra-env="${env.environmentId}" aria-pressed="${String(env.environmentId === s.selected)}">
                <span class="ra-env-text"><span class="ra-env-name">${envName(env)}</span>
                    ${Utils.raw(env.vmCount !== null && env.vmCount !== undefined || env.description ? Utils.html`<span class="ra-env-desc">${envDescription(env)}</span>` : '')}</span>
                ${Utils.raw(tag ? Utils.html`<span class="ra-tag" data-tag="${tag === 'Request waiting' ? 'wait' : 'view'}">${tag}</span>` : '')}
            </button>`;
        }).join('');
    }

    function formHtml() {
        const env = s.envs.find(e => e.environmentId === s.selected);
        if (!env) return '<h2 id="ra-form-title">Request access</h2><p class="ra-empty">Pick an environment on the left.</p>';
        const name = envName(env);
        const waiting = s.requests.find(r => r.status === 'PENDING' && r.environmentId === env.environmentId);
        if (waiting) {
            const line = requestLine(waiting);
            return Utils.html`<h2 id="ra-form-title">Request access to ${name}</h2>
                <p class="ra-waiting">Your request for ${line.level}${line.what} is waiting, ${line.extra}.</p>
                <div><button type="button" class="btn btn-outline-danger btn-sm" data-ra-cancel="${waiting.requestId}">Cancel request</button></div>`;
        }
        const viewOnly = s.viewEnvIds.has(env.environmentId);
        const level = viewOnly ? 'USER' : (s.preset.level === 'VIEWER' ? 'VIEWER' : 'USER');
        const lengths = durations(s.maxDays);
        const chosenDays = lengths.includes(30) ? 30 : lengths[lengths.length - 1];
        const err = s.errors;
        return Utils.html`<h2 id="ra-form-title">Request access to ${name}</h2>
            <form id="ra-form" class="ra-form" novalidate>
                <fieldset class="ra-levels"><legend>What you need</legend>
                    <label class="ra-level"><input type="radio" name="raLevel" value="USER" ${level === 'USER' ? 'checked' : ''}>
                        <span><b>Operator</b><span>Start, stop and lock it</span></span></label>
                    <label class="ra-level"><input type="radio" name="raLevel" value="VIEWER" ${level === 'VIEWER' ? 'checked' : ''} ${viewOnly ? 'disabled' : ''}>
                        <span><b>View only</b><span>${viewOnly ? 'You have this already' : 'See its state, cost and history'}</span></span></label>
                </fieldset>
                <label class="ra-field" for="ra-group">Part of it
                    <select id="ra-group" class="form-select"><option value="">The whole environment</option></select>
                    <span class="ra-hint" id="ra-group-hint"></span></label>
                <label class="ra-field" for="ra-days">For
                    <select id="ra-days" class="form-select">${Utils.raw(lengths.map(d => `<option value="${d}" ${d === chosenDays ? 'selected' : ''}>${d} days</option>`).join(''))}</select></label>
                <label class="ra-field" for="ra-reason">Why you need it
                    <textarea id="ra-reason" class="form-control" rows="3" maxlength="1000" placeholder="For example: regression testing for release 4.2"
                        aria-invalid="${String(!!err.reason)}" aria-describedby="ra-reason-error"></textarea>
                    <span class="ra-error" id="ra-reason-error">${err.reason || ''}</span></label>
                <span class="ra-error" id="ra-form-error" role="alert">${err.form || ''}</span>
                <button type="submit" class="btn btn-primary ra-send" id="ra-send">Send request</button>
            </form>`;
    }

    /** The environment's groups for "Part of it"; without view access only the whole environment. */
    function loadGroups() {
        const envId = s.selected;
        if (!envId || !$('#ra-group').length) return;
        const fill = groups => {
            if (s.selected !== envId) return;
            const preset = s.preset.group;
            const options = (groups || []).map(g => ({ id: g.groupId, name: g.displayName || g.name }));
            if (preset && !options.some(o => o.id === preset)) options.push({ id: preset, name: s.preset.groupName || 'Your previous group' });
            $('#ra-group').html('<option value="">The whole environment</option>'
                + options.map(o => Utils.html`<option value="${o.id}">Only the ${o.name} group</option>`).join(''));
            if (preset) $('#ra-group').val(preset);
            $('#ra-group-hint').text(groups === null && !options.length ? 'Groups show once you can view this environment.' : '');
        };
        if (envId in s.groups) { fill(s.groups[envId]); return; }
        // Groups are readable only with view access: ask only then, so nothing is refused.
        if (!s.viewEnvIds.has(envId)) { s.groups[envId] = null; fill(null); return; }
        ApiClient.get(Config.API.groups.list(envId), { suppressGlobalError: true })
            .done(groups => { s.groups[envId] = groups || []; fill(s.groups[envId]); })
            .fail(() => { s.groups[envId] = null; fill(null); });
    }

    function mineHtml() {
        if (!s.requests.length) {
            return '<section class="ra-card" aria-labelledby="ra-mine-title"><h2 id="ra-mine-title" class="ra-card-title">Your requests</h2><p class="ra-empty">You have not asked for access yet.</p></section>';
        }
        const page = s.requests.slice(s.minePage * PAGE_SIZE, (s.minePage + 1) * PAGE_SIZE);
        return `<section class="ra-card" aria-labelledby="ra-mine-title"><h2 id="ra-mine-title" class="ra-card-title">Your requests</h2>
            <div id="ra-mine-list">${page.map(req => {
                const l = requestLine(req);
                const action = l.action.kind === 'cancel'
                    ? Utils.html`<button type="button" class="btn btn-link btn-sm p-0 ra-row-action" data-ra-cancel="${req.requestId}">${l.action.label}</button>`
                    : l.action.kind === 'open'
                        ? Utils.html`<a class="ra-row-action" href="#/environments/${req.environmentId}">${l.action.label}</a>`
                        : Utils.html`<button type="button" class="btn btn-link btn-sm p-0 ra-row-action" data-ra-again="${req.environmentId}"
                            data-ra-group="${req.scopeType === 'GROUP' ? req.scopeId || '' : ''}" data-ra-group-name="${req.scopeName || ''}"
                            data-ra-level="${req.requestedAccessLevel || 'USER'}" data-ra-name="${req.environmentName || ''}">${l.action.label}</button>`;
                return Utils.html`<div class="ra-request" data-request-id="${req.requestId}"><span class="ra-chip" data-tone="${l.tone}">${l.chip}</span>
                    <span class="ra-request-text">${l.level} on <b>${l.env}</b>${l.what}${Utils.raw(l.extra ? Utils.html` · ${l.extra}` : '')}</span>${Utils.raw(action)}</div>`;
            }).join('')}</div>
            <div class="pagination-bar-wrap" id="ra-mine-pager"></div></section>`;
    }

    function renderPagers() {
        if ($('#ra-env-pager').length) {
            const list = filtered();
            Pagination.renderNumbered('#ra-env-pager', {
                page: s.page, totalItems: list.length, pageSize: PAGE_SIZE, itemLabel: 'environments', zeroIndexed: true,
                bindAncestor: '#content-area',
                onPageChange: page => { s.page = page; $('#ra-env-list').html(listHtml()); renderPagers(); }
            });
        }
        if ($('#ra-mine-pager').length) {
            Pagination.renderNumbered('#ra-mine-pager', {
                page: s.minePage, totalItems: s.requests.length, pageSize: PAGE_SIZE, itemLabel: 'requests', zeroIndexed: true,
                bindAncestor: '#content-area',
                onPageChange: page => { s.minePage = page; rerenderMine(); }
            });
        }
    }

    function rerenderForm() {
        $('#ra-form-card').html(formHtml());
        loadGroups();
    }

    function rerenderMine() {
        const $panel = $('#ra-tabs-panel-mine');
        $panel.html(mineHtml());
        $('#ra-tabs-tab-mine').contents().first().replaceWith(`Your requests · ${s.requests.length}`);
        renderPagers();
    }

    async function refreshRequests() {
        const [mine] = await Promise.all([get(Config.API.access.myRequests, [])]);
        s.requests = (mine || []).slice().sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
        s.waitingEnvIds = new Set(s.requests.filter(r => r.status === 'PENDING').map(r => r.environmentId));
    }

    function send() {
        if (s.sending) return;
        const envId = s.selected;
        const reason = $('#ra-reason').val();
        const problem = reasonProblem(reason);
        s.errors = {};
        if (problem) {
            $('#ra-reason-error').text(problem);
            $('#ra-reason').attr('aria-invalid', 'true').trigger('focus');
            return;
        }
        const groupId = $('#ra-group').val() || null;
        const body = {
            accessLevel: $('input[name="raLevel"]:checked').val() || 'USER',
            businessJustification: reason.trim(),
            scopeType: groupId ? 'GROUP' : 'ENVIRONMENT',
            groupId,
            durationDays: Number($('#ra-days').val()) || null
        };
        s.sending = true;
        $('#ra-send').prop('disabled', true).text('Sending…');
        ApiClient.post(Config.API.access.requestAccess(envId), body, { suppressGlobalError: true })
            .done(async () => {
                await refreshRequests();
                const sent = s.requests.find(r => r.status === 'PENDING' && r.environmentId === envId);
                const to = joinNames(sent && sent.reviewerNames);
                Notifications.success(to ? `Request sent to ${to}.` : 'Request sent.');
                s.preset = {};
                s.tab = 'mine';
                s.minePage = 0;
                render();
            })
            .fail(xhr => {
                const r = xhr.responseJSON || {};
                const msg = r.message || (Array.isArray(r.errors) ? r.errors.map(e => e.message).filter(Boolean).join('; ') : '') || 'Couldn\'t send the request. Try again.';
                $('#ra-form-error').text(msg);
            })
            .always(() => {
                s.sending = false;
                $('#ra-send').prop('disabled', false).text('Send request');
            });
    }

    function cancel(requestId) {
        Modals.confirm('Cancel request', 'Cancel this access request? You can ask again later.', () => {
            ApiClient.delete(Config.API.access.cancelRequest(requestId), { suppressGlobalError: true })
                .done(async () => {
                    Notifications.success('Request cancelled');
                    await refreshRequests();
                    render();
                })
                .fail(xhr => Notifications.error((xhr.responseJSON && xhr.responseJSON.message) || 'Couldn\'t cancel the request'));
        }, { confirmText: 'Cancel request', cancelText: 'Keep it', confirmClass: 'btn-danger' });
    }

    function bind() {
        const $area = $('#content-area');
        $area.off('.reqAccess')
            .on('input.reqAccess', '#ra-search', Utils.debounce(function() {
                s.query = this.value.trim();
                s.page = 0;
                $('#ra-env-list').html(listHtml());
                renderPagers();
            }, 250))
            .on('click.reqAccess', '[data-ra-clear]', () => {
                s.query = '';
                $('#ra-search').val('').trigger('focus');
                $('#ra-env-list').html(listHtml());
                renderPagers();
            })
            .on('click.reqAccess', '[data-ra-env]', function() {
                s.selected = this.getAttribute('data-ra-env');
                s.preset = {};
                s.errors = {};
                $('#ra-env-list [data-ra-env]').attr('aria-pressed', 'false');
                this.setAttribute('aria-pressed', 'true');
                rerenderForm();
            })
            .on('submit.reqAccess', '#ra-form', e => { e.preventDefault(); send(); })
            .on('input.reqAccess', '#ra-reason', function() {
                $('#ra-reason-error').text('');
                this.setAttribute('aria-invalid', 'false');
            })
            .on('click.reqAccess', '[data-ra-cancel]', function() { cancel(this.getAttribute('data-ra-cancel')); })
            .on('click.reqAccess', '[data-ra-again]', function() {
                s.selected = this.getAttribute('data-ra-again');
                s.preset = { group: this.getAttribute('data-ra-group') || null, groupName: this.getAttribute('data-ra-group-name') || null,
                    level: this.getAttribute('data-ra-level') || null, name: this.getAttribute('data-ra-name') || null };
                if (!s.envs.some(e => e.environmentId === s.selected)) s.envs.unshift({ environmentId: s.selected, displayName: s.preset.name || 'This environment' });
                s.tab = 'new';
                render();
            });
    }

    return { load, model: { requestLine, envTag, envDescription, durations, reasonProblem, joinNames } };
})();

if (typeof window !== 'undefined') window.RequestAccess = RequestAccess;
