/**
 * VM Self-Service Platform - My account panel (U09-T05, MyAccount.dc.html)
 * Slide-out opened from the user menu. The header names you (initials, name, "email · role");
 * four tabs:
 *   Access        - what you can use: one card per grant (level, until when, who granted it,
 *                   Request an extension when it ends soon), requests waiting for a decision,
 *                   environments kept running for you, and "Show N ended grants"
 *   Cost          - month to date across your environments, one line each, your teams' quotas
 *   Notifications - which events reach you in the app and by email (E29-T06)
 *   Profile       - how you signed in, role, teams, keyboard shortcuts, Sign out
 *   API tokens    - your personal API tokens (E34-T05), shown only while tokens are on
 * open('overview') opens Access; open('activity') goes to the My activity page.
 */

const MyAccount = (function() {
    'use strict';

    const TABS = [['access', 'Access'], ['cost', 'Cost'], ['notifications', 'Notifications'], ['profile', 'Profile'],
        ['api-tokens', 'API tokens']];
    const EXTENSION_DAYS = [7, 14, 30, 60, 90];
    const DEFAULT_EXTENSION_DAYS = 30;
    const DAY_MS = 24 * 60 * 60 * 1000;
    const ROOT = '#dynamicSlideoutPanel .my-account';
    const PANEL = '#dynamicSlideoutPanel';

    let state = null;

    // ============= Pure model (node tests: src/test/js/my-account.test.mjs) =============

    function initials(name) {
        if (!name || !String(name).trim()) return '?';
        const parts = String(name).trim().split(/\s+/);
        return (parts.length >= 2 ? parts[0][0] + parts[parts.length - 1][0] : parts[0].substring(0, 2)).toUpperCase();
    }

    /** "17 Nov", with the year when it is not this year. */
    function shortDate(ts, now) {
        const d = new Date(ts);
        const n = now || new Date();
        const opts = { day: 'numeric', month: 'short' };
        if (d.getFullYear() !== n.getFullYear()) opts.year = 'numeric';
        return d.toLocaleDateString('en-GB', opts);
    }

    /**
     * Calendar days from today to the day of `ts` (0 = today), as AccessLevels.endsText counts them.
     * Midnight to midnight with rounding, so a clock change (an extra or a missing hour) in between and
     * the time of day do not move the count: a grant ending later today is "today", not "tomorrow".
     */
    function daysBetween(ts, now) {
        const startOfDay = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
        return Math.round((startOfDay(new Date(ts)) - startOfDay(now || new Date())) / DAY_MS);
    }

    /** The shared level names (AccessLevels): "Operator", "Admin", "Operator, Application group only". */
    function levelText(item) {
        const label = AccessLevels.label((item && (item.accessLevel || item.requestedAccessLevel)) || 'VIEWER');
        return item && item.scopeType === 'GROUP' && item.scopeName ? `${label}, ${item.scopeName} group only` : label;
    }

    /**
     * When a grant ends: "No end date", "Until 17 Nov (29 days)", or within the extension window
     * {soon: true} "Ends 22 Oct — in 3 days" / "Ends 21 Oct — tomorrow" / "Ends today".
     */
    function untilText(grant, windowDays, now) {
        if (!grant || !grant.expiresAt) return { text: 'No end date', soon: false };
        const days = daysBetween(grant.expiresAt, now);
        const date = shortDate(grant.expiresAt, now);
        if (days <= 0) return { text: 'Ends today', soon: true };
        if (days <= windowDays) return { text: `Ends ${date} — ${days === 1 ? 'tomorrow' : `in ${days} days`}`, soon: true };
        return { text: `Until ${date} (${days} days)`, soon: false };
    }

    function roleText(profile) {
        if (!profile) return '';
        if (profile.admin) return 'Administrator';
        if (profile.envAdmin) return 'Environment admin';
        return 'User';
    }

    /** The Role row: "Environment admin for Payments Dev and CRM UAT". */
    function roleDetail(profile) {
        const role = roleText(profile);
        const envs = (profile && profile.administeredEnvironments || []).map(e => e.name).filter(Boolean);
        if (role === 'Environment admin' && envs.length) return `${role} for ${joinNames(envs)}`;
        return role;
    }

    function joinNames(names) {
        if (names.length <= 1) return names.join('');
        return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
    }

    function signInText(profile) {
        return profile && profile.authMethod === 'ENTRA_ID' ? 'Microsoft (Entra ID)' : 'Username and password';
    }

    /**
     * Month to date over the environments whose cost is shown (group-only grants have none); the
     * server's total when it sends one (U23-T01): it covers every environment, not only the 50 listed,
     * and for administrators it is the fleet's figure Home and Cost management show.
     */
    function costTotal(environments, serverTotal) {
        if (serverTotal !== null && serverTotal !== undefined && Number.isFinite(Number(serverTotal))) return Number(serverTotal);
        return (environments || []).filter(e => e.scope !== 'GROUPS')
            .reduce((sum, e) => sum + Number(e.monthToDateEstimated || 0), 0);
    }

    function endedText(grant, now) {
        if (grant.status === 'REVOKED' && grant.revokedAt) return `Removed ${shortDate(grant.revokedAt, now)}`;
        return grant.expiresAt ? `Ended ${shortDate(grant.expiresAt, now)}` : 'Ended';
    }

    // ============= Panel =============

    function visibleTabs() {
        return TABS.filter(([id]) => id !== 'api-tokens' || (state && state.tokensEnabled));
    }

    function init() {
        bindEvents();
        // Closing the panel ends this view: in-flight requests are owner-guarded on `state`,
        // so resetting it makes their late responses no-ops.
        $(document).off('slideout:closed.myAccount').on('slideout:closed.myAccount', function() {
            state = null;
        });
        // Another panel in the same slot must not keep this one's header.
        $(document).off('slideout:opened.myAccount').on('slideout:opened.myAccount', (e, panel) => {
            if (panel && panel.getAttribute('data-variant') !== 'my-account') $(panel).find('.ma-head-extra').remove();
        });
    }

    /**
     * Open the panel on a tab ('access' by default). opts.extend = an access id: once the grants
     * have loaded, start the extension request for that grant (Home's "Ask for more time", U06-T02).
     */
    function open(tab, opts = {}) {
        if (tab === 'activity') {
            if (typeof Slideout !== 'undefined') Slideout.close();
            ContentRouter.navigate('activity-logs');
            return;
        }
        state = {
            tab: TABS.some(t => t[0] === tab) ? tab : 'access',
            profile: null,
            grants: null,
            requests: null,
            history: null,
            cost: null,
            costRequested: false,
            leases: [],
            errors: {},
            showEnded: false,
            extendingId: null,
            extendDraft: null,
            extendSending: false,
            cancellingId: null,
            prefs: null,
            prefsRequested: false,
            prefDraft: {},
            prefSaving: false,
            prefMessage: null,
            tokensEnabled: false
        };
        Slideout.open('My account', `
            <div class="my-account">
                <div class="ma-tabs" role="tablist" aria-label="My account"></div>
                <div class="ma-body" role="tabpanel" tabindex="0"></div>
            </div>
        `, { variant: 'my-account', width: 520 });
        render();
        loadAll(opts.extend);
        if (state.tab === 'cost') loadCost();
        if (state.tab === 'notifications') loadPrefs();
        if (window.ApiTokensUi) {
            const owner = state;
            ApiTokensUi.probe().then(on => {
                if (state !== owner || !on) return;
                state.tokensEnabled = true;
                render();
            });
        }
    }

    // ============= Notifications (E29-T06) =============

    function loadPrefs() {
        if (!state || state.prefsRequested) return;
        state.prefsRequested = true;
        load('prefs', Config.API.users.notificationPreferences);
    }

    function prefValue(p, channel) {
        const draft = state.prefDraft[p.type];
        return draft && draft[channel] !== undefined ? draft[channel] : p[channel];
    }

    function prefChanged() {
        return Object.keys(state.prefDraft).length > 0;
    }

    function notificationsHtml() {
        if (state.errors.prefs) return errorHtml('your notification settings');
        if (!state.prefs) return loadingHtml();
        const groups = [];
        state.prefs.forEach(p => {
            let g = groups.find(x => x.name === p.category);
            if (!g) groups.push(g = { name: p.category, items: [] });
            g.items.push(p);
        });
        const rows = groups.map(g => Utils.html`
            <tr class="ma-pref-group"><th colspan="3" scope="colgroup">${g.name}</th></tr>
            ${Utils.raw(g.items.map(p => Utils.html`<tr data-pref-row="${p.type}">
                    <th scope="row" class="ma-pref-label">${p.label}</th>
                    <td class="ma-pref-cell">
                        <input class="form-check-input" type="checkbox" id="ma-pref-${p.type}-inApp" data-ma-pref="inApp"
                               data-type="${p.type}" aria-label="${p.label}, in app" ${prefValue(p, 'inApp') ? 'checked' : ''}
                               ${p.inAppLocked ? 'disabled' : ''}>
                        ${Utils.raw(p.inAppLocked ? '<span class="ma-pref-note" title="You always get this one in the app">Always</span>' : '')}
                    </td>
                    <td class="ma-pref-cell" ${p.emailAvailable ? '' : Utils.raw('title="Email is off for this event for everyone"')}>
                        <input class="form-check-input" type="checkbox" id="ma-pref-${p.type}-email" data-ma-pref="email"
                               data-type="${p.type}" aria-label="${p.label}, email" ${prefValue(p, 'email') ? 'checked' : ''}
                               ${p.emailAvailable ? '' : 'disabled'}>
                        ${Utils.raw(p.emailAvailable ? '' : '<span class="ma-pref-note">Not emailed</span>')}
                    </td>
                </tr>`).join(''))}`).join('');
        const message = state.prefMessage
            ? Utils.html`<span class="ma-pref-message ${state.prefMessage.ok ? 'ok' : 'bad'}" role="status">${state.prefMessage.text}</span>` : '';
        return Utils.html`
            <div class="ma-prefs">
                <table class="ma-pref-table">
                    <thead><tr><th scope="col">Tell me when</th><th scope="col">In app</th><th scope="col">Email</th></tr></thead>
                    <tbody>${Utils.raw(rows)}</tbody>
                </table>
                <p class="ma-hint">Some messages always reach you in the app. Email goes out only for the events your organisation emails.
                    <button type="button" class="ma-link" data-ma-action="help" data-help-topic="notifications">Help</button></p>
                <div class="ma-pref-actions">
                    <button type="button" class="btn btn-primary btn-sm" data-ma-action="prefs-save" id="ma-prefs-save"
                            ${prefChanged() && !state.prefSaving ? '' : 'disabled'}>${state.prefSaving ? 'Saving…' : 'Save'}</button>
                    ${Utils.raw(message)}
                </div>
            </div>`;
    }

    function onPrefChange() {
        if (!state || !state.prefs) return;
        const type = this.dataset.type;
        const channel = this.dataset.maPref;
        const original = state.prefs.find(p => p.type === type);
        const draft = Object.assign({}, state.prefDraft[type]);
        if (original && original[channel] === this.checked) delete draft[channel];
        else draft[channel] = this.checked;
        if (Object.keys(draft).length) state.prefDraft[type] = draft;
        else delete state.prefDraft[type];
        state.prefMessage = null;
        render();
    }

    function savePrefs() {
        if (!state || !prefChanged()) return;
        const owner = state;
        const changes = Object.keys(state.prefDraft).map(type => Object.assign({ type }, state.prefDraft[type]));
        state.prefSaving = true;
        render();
        ApiClient.request('PUT', Config.API.users.notificationPreferences, changes, { suppressGlobalError: true })
            .done(list => {
                if (state !== owner) return;
                state.prefs = list || state.prefs;
                state.prefDraft = {};
                state.prefMessage = { ok: true, text: 'Saved.' };
            })
            .fail(() => {
                if (state === owner) state.prefMessage = { ok: false, text: 'Could not save. Try again.' };
            })
            .always(() => {
                if (state !== owner) return;
                state.prefSaving = false;
                render();
            });
    }

    // ============= Cost (E18-T04) =============

    /** The Cost tab is loaded the first time it is shown. */
    function loadCost() {
        if (!state || state.costRequested) return;
        state.costRequested = true;
        load('cost', Config.API.users.myCost);
        load('quotas', Config.API.teams.myQuotaUsage); // E25-T08
    }

    function money(amount) {
        return Utils.formatCurrency(Number(amount || 0));
    }

    /** A 14-point inline sparkline (no chart library in the slide-out). */
    function sparklineSvg(points, name) {
        const values = (points || []).map(Number);
        if (values.length < 2) return '';
        const w = 72;
        const h = 20;
        const max = Math.max(...values, 0.01);
        const step = w / (values.length - 1);
        const coords = values.map((v, i) => `${(i * step).toFixed(1)},${(h - 2 - (v / max) * (h - 4)).toFixed(1)}`).join(' ');
        const first = values[0];
        const last = values[values.length - 1];
        const trend = last > first ? 'rising' : last < first ? 'falling' : 'flat';
        return `<svg class="ma-spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img"
                    aria-label="${Utils.escapeHtml(`${name}: daily cost over 14 days, ${trend}, last day ${money(last)}`)}">
                    <polyline fill="none" stroke="currentColor" stroke-width="1.5" points="${coords}"/></svg>`;
    }

    function costRowHtml(env) {
        const name = env.displayName || env.name;
        const value = env.scope === 'GROUPS'
            ? Utils.html`<span class="ma-cost-hint">${env.hint || 'Shown for whole environments only'}</span>`
            : Utils.html`${Utils.raw(sparklineSvg(env.sparkline, name))}<span class="ma-cost-mtd">${money(env.monthToDateEstimated)}</span>`;
        return Utils.html`
            <div class="ma-cost-row" data-env-id="${env.environmentId}">
                <button type="button" class="ma-env-link" data-ma-action="open-env" data-env-id="${env.environmentId}">${name}</button>
                <span class="ma-cost-value">${Utils.raw(value)}</span>
            </div>`;
    }

    /** My teams' monthly quotas (E25-T08); nothing when none of my teams has one. */
    function quotaCardsHtml() {
        const teams = Array.isArray(state.quotas) ? state.quotas : [];
        if (!teams.length || typeof CostPreview === 'undefined') return '';
        return teams.map(t => Utils.html`<article class="ma-quota-card" data-team="${t.teamId}">
                <div class="ma-quota-head"><strong>${t.teamName}</strong><span>team quota this month</span></div>
                ${Utils.raw(t.quotas.map(q => CostPreview.quotaBar(q)).join(''))}</article>`).join('');
    }

    function costHtml() {
        if (state.errors.cost) return errorHtml("your environments' cost");
        if (!state.cost) return loadingHtml();
        const list = state.cost.environments || [];
        if (list.length === 0) {
            return `<div class="ma-empty"><p>You can't use any environment yet, so there is no cost to show.</p>
                        <button type="button" class="btn btn-primary btn-sm" data-ma-action="request-access">Request access</button></div>`;
        }
        const capped = state.cost.capped
            ? Utils.html`<p class="ma-hint">The ${state.cost.limit} highest of ${state.cost.totalEnvironments} environments.</p>`
            : '';
        return `<div class="ma-cost">
                <div class="ma-cost-label">Month to date across the environments you can use</div>
                <div class="ma-cost-total" id="ma-cost-total">${Utils.escapeHtml(money(costTotal(list, state.cost.monthToDateTotal)))}</div>
                <div class="ma-cost-list">${list.map(costRowHtml).join('')}</div>
                ${capped}
                <p class="ma-hint">Estimated from daily snapshots.</p>
                ${quotaCardsHtml()}
            </div>`;
    }

    // ============= Data =============

    function load(key, url) {
        const owner = state;
        return ApiClient.get(url, { suppressGlobalError: true })
            .done(data => {
                if (state !== owner) return;
                state[key] = data || (key === 'profile' ? null : []);
                delete state.errors[key];
            })
            .fail(() => {
                if (state === owner) state.errors[key] = true;
            })
            .always(() => {
                if (state === owner) render();
            });
    }

    function loadAll(extendAccessId) {
        // Leases are fetched only when the profile says they are on, so nothing fails while off.
        load('profile', Config.API.users.myProfile).done(() => {
            if (state && state.profile && state.profile.leasesEnabled) loadLeases();
        });
        const owner = state;
        load('grants', Config.API.access.myEnvironments).done(() => {
            if (extendAccessId && state === owner && findGrant(extendAccessId)) startExtension(extendAccessId);
        });
        loadRequests();
        load('history', Config.API.access.myAccessHistory(30));
    }

    /** Running environments the user keeps alive (E15-T07); an error just hides the section. */
    function loadLeases() {
        const owner = state;
        ApiClient.get(Config.API.users.myLeases, { suppressGlobalError: true })
            .done(data => {
                if (state !== owner) return;
                state.leases = Array.isArray(data) ? data : [];
                render();
            });
    }

    function loadRequests() {
        return load('requests', Config.API.access.myRequests);
    }

    function retryFailed() {
        const urls = {
            profile: Config.API.users.myProfile,
            grants: Config.API.access.myEnvironments,
            requests: Config.API.access.myRequests,
            history: Config.API.access.myAccessHistory(30),
            cost: Config.API.users.myCost,
            quotas: Config.API.teams.myQuotaUsage,
            prefs: Config.API.users.notificationPreferences
        };
        Object.keys(state.errors).forEach(key => {
            delete state.errors[key];
            load(key, urls[key]);
        });
        render();
    }

    // ============= Derived values =============

    function windowDays() {
        return state.profile ? state.profile.extensionWindowDays : 7;
    }

    /** The server's access.request.max-duration-days (an extension is a request). */
    function maxRequestDays() {
        return state.profile && state.profile.requestMaxDurationDays ? state.profile.requestMaxDurationDays : 180;
    }

    function extensionDayOptions() {
        const allowed = EXTENSION_DAYS.filter(d => d <= maxRequestDays());
        return allowed.length ? allowed : [maxRequestDays()];
    }

    function defaultExtensionDays() {
        const options = extensionDayOptions();
        return options.includes(DEFAULT_EXTENSION_DAYS) ? DEFAULT_EXTENSION_DAYS : options[options.length - 1];
    }

    function isExpiring(grant) {
        return !!grant.expiresAt && daysBetween(grant.expiresAt) <= windowDays();
    }

    function pendingRequests() {
        return (state.requests || []).filter(r => r.status === 'PENDING');
    }

    function pendingRequestFor(grant) {
        return pendingRequests().find(r => r.scopeType === grant.scopeType && r.scopeId === grant.scopeId);
    }

    /** Ending soonest first, then the rest by environment name. */
    function sortGrants(grants) {
        return grants.slice().sort((a, b) => {
            const ea = isExpiring(a), eb = isExpiring(b);
            if (ea !== eb) return ea ? -1 : 1;
            if (ea && eb) return new Date(a.expiresAt) - new Date(b.expiresAt);
            return (a.environmentName || '').localeCompare(b.environmentName || '');
        });
    }

    function findGrant(accessId) {
        return (state.grants || []).find(g => g.accessId === accessId);
    }

    // ============= Rendering =============

    function render(options = {}) {
        const $root = $(ROOT);
        if (!$root.length || !state) return;

        const $body = $root.find('.ma-body');
        const scrollTop = options.resetScroll ? 0 : $body.scrollTop();
        const focused = document.activeElement && $root[0].contains(document.activeElement)
            ? document.activeElement : null;
        const focusedId = focused ? focused.id : null;
        // Keep the caret where the user was typing (E13-T10)
        let selection = null;
        try {
            if (focused && typeof focused.selectionStart === 'number') {
                selection = [focused.selectionStart, focused.selectionEnd];
            }
        } catch (e) { selection = null; }

        renderHeader();
        $root.find('.ma-tabs').html(tabsHtml());
        $body.attr('aria-labelledby', `ma-tab-${state.tab}`).html(bodyHtml());
        if (state.tab === 'api-tokens' && window.ApiTokensUi) ApiTokensUi.mount(document.getElementById('ma-api-tokens'));
        $body.scrollTop(scrollTop);

        if (focusedId) {
            const el = document.getElementById(focusedId);
            if (el) {
                el.focus();
                if (selection && typeof el.setSelectionRange === 'function') {
                    try { el.setSelectionRange(selection[0], selection[1]); } catch (e) { /* not a text field */ }
                }
            }
        }
    }

    /** Initials, the name as the dialog's title, and "email · role" under it. */
    function renderHeader() {
        const $header = $(`${PANEL} .slideout-panel-header`);
        const p = state.profile;
        $header.find('.ma-head-extra').remove();
        $header.find('#dynamicSlideoutTitle').text(p && p.displayName ? p.displayName : 'My account');
        if (!p) return;
        $header.prepend(Utils.html`<span class="ma-avatar ma-head-extra" aria-hidden="true">${initials(p.displayName)}</span>`);
        $header.find('#dynamicSlideoutTitle').after(Utils.html`<div class="ma-head-sub ma-head-extra">${[p.email, roleText(p)].filter(Boolean).join(' · ')}</div>`);
    }

    /** Reload the open panel's data in place (keeps the tab, drafts and focus). */
    function refresh() {
        if (state && $(ROOT).length) loadAll();
    }

    function tabsHtml() {
        return visibleTabs().map(([id, label]) => {
            const selected = state.tab === id;
            return `<button type="button" role="tab" id="ma-tab-${id}" class="ma-tab${selected ? ' active' : ''}"
                        aria-selected="${selected}" tabindex="${selected ? 0 : -1}" data-ma-tab="${id}">${label}</button>`;
        }).join('');
    }

    function bodyHtml() {
        if (state.tab === 'cost') return costHtml();
        if (state.tab === 'notifications') return notificationsHtml();
        if (state.tab === 'profile') return profileHtml();
        if (state.tab === 'api-tokens') return '<div id="ma-api-tokens" class="ma-api-tokens"></div>';
        return accessHtml();
    }

    function loadingHtml() {
        return '<div class="ma-loading" role="status">Loading…</div>';
    }

    function errorHtml(what) {
        return `<div class="ma-error" role="alert">Couldn't load ${what}.
                    <button type="button" class="ma-link" data-ma-action="retry">Try again</button></div>`;
    }

    // ----- Access -----

    function accessHtml() {
        if (state.errors.grants) return errorHtml('your access');
        if (!state.grants) return loadingHtml();
        const grants = sortGrants(state.grants);
        return `<div class="ma-access">
                ${leasesSectionHtml()}
                ${pendingSectionHtml()}
                ${grants.length ? `<div class="ma-grants">${grants.map(grantCardHtml).join('')}</div>` : emptyAccessHtml()}
                ${endedSectionHtml()}
                ${grants.length ? '<button type="button" class="ma-link ma-more" data-ma-action="request-access">Ask for another environment</button>' : ''}
            </div>`;
    }

    function leasesSectionHtml() {
        const leases = state.leases || [];
        if (!leases.length) return '';
        const rows = leases.map(l => {
            const endsAt = new Date(l.endsAt);
            const mins = Math.max(0, Math.round((endsAt.getTime() - Date.now()) / 60000));
            const left = mins >= 60 ? `${Math.floor(mins / 60)} h${mins % 60 ? ' ' + (mins % 60) + ' min' : ''}` : `${mins} min`;
            const extend = l.permissions && l.permissions.canExtend
                ? Utils.html`<button type="button" class="btn btn-sm btn-outline-secondary" data-ma-action="lease-extend"
                        data-env-id="${l.environmentId}">Extend</button>`
                : '';
            return Utils.html`<li class="ma-lease">
                    <button type="button" class="ma-env-link" data-ma-action="open-env" data-env-id="${l.environmentId}">
                        ${l.environmentName || l.environmentId}</button>
                    <span class="ma-lease-end">stops at ${endsAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} (in ${left})</span>
                    ${Utils.raw(extend)}
                </li>`;
        }).join('');
        return `<section class="ma-leases" aria-labelledby="ma-leases-title">
                <h3 class="ma-section-title" id="ma-leases-title">Running environments</h3>
                <ul class="ma-lease-list">${rows}</ul>
            </section>`;
    }

    function extendLease(envId) {
        const lease = (state.leases || []).find(l => l.environmentId === envId);
        if (!lease || typeof Leases === 'undefined') return;
        const owner = state;
        ApiClient.get(Config.API.leases.policy(envId), { suppressGlobalError: true })
            .always((policy) => {
                if (state !== owner) return;
                Leases.showExtendDialog(envId, lease, policy && policy.effectiveMaxMinutes ? policy : null,
                    () => { if (state === owner) loadLeases(); });
            });
    }

    function emptyAccessHtml() {
        // U22-T06: an administrator holds no grants but manages every environment; staging told them
        // "You can't use any environment yet".
        if (typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin()) {
            return `<div class="ma-empty">
                    <p class="ma-empty-title">You manage every environment as an administrator</p>
                    <p>Access given to you on a single environment would show here.</p>
                    <a class="btn btn-outline-secondary btn-sm" href="#/my-environments">Go to Environments</a>
                </div>`;
        }
        return `<div class="ma-empty">
                    <p class="ma-empty-title">You can't use any environment yet</p>
                    <p>Ask for one and its admins decide. You get a message when they do.</p>
                    <button type="button" class="btn btn-primary btn-sm" data-ma-action="request-access">Request access</button>
                </div>`;
    }

    function grantCardHtml(g) {
        const until = untilText(g, windowDays());
        const by = g.grantedByUserName ? `Granted by ${g.grantedByUserName}` : `Granted ${shortDate(g.grantedAt)}`;
        const updated = g.lastModifiedAt
            ? Utils.html`<span class="ma-updated"> · Updated by ${g.lastModifiedByUserName || 'an admin'} on ${shortDate(g.lastModifiedAt)}</span>`
            : '';
        let action = '';
        if (isExpiring(g)) {
            action = pendingRequestFor(g)
                ? '<span class="ma-pill">Extension requested</span>'
                : Utils.html`<button type="button" class="ma-link ma-extend-link" data-ma-action="extend" data-access-id="${g.accessId}"
                        aria-expanded="${state.extendingId === g.accessId ? 'true' : 'false'}">Request an extension</button>`;
        }
        return Utils.html`
            <article class="ma-card ma-grant${until.soon ? ' expiring' : ''}" data-access-id="${g.accessId}">
                <div class="ma-grant-top">
                    <button type="button" class="ma-env-link" data-ma-action="open-env" data-env-id="${g.environmentId}">${g.environmentName}</button>
                    <span class="ma-grant-level">${levelText(g)}</span>
                </div>
                <div class="ma-grant-until${until.soon ? ' soon' : ''}">${until.text}</div>
                <div class="ma-grant-by">${by}${Utils.raw(updated)}</div>
                ${g.notes ? Utils.raw(Utils.html`<p class="ma-grant-notes">${g.notes}</p>`) : ''}
                ${Utils.raw(action)}
                ${Utils.raw(state.extendingId === g.accessId ? extendFormHtml(g) : '')}
            </article>`;
    }

    /**
     * An approved extension adds the days to the current end, or to the approval time if the
     * grant has already lapsed (server: EnvironmentAccessService.applyExpiry).
     */
    function newExpiryText(g, days) {
        const base = Math.max(Date.now(), g.expiresAt ? new Date(g.expiresAt).getTime() : 0);
        return `If approved now it ends ${shortDate(base + days * DAY_MS)}.`;
    }

    function extendFormHtml(g) {
        const draft = state.extendDraft;
        const options = extensionDayOptions().map(d =>
            `<option value="${d}"${d === draft.days ? ' selected' : ''}>${d} days</option>`).join('');
        return `
            <form class="ma-extend" data-access-id="${Utils.escapeHtml(g.accessId)}" novalidate>
                <div class="ma-extend-row">
                    <label for="ma-extend-days">Extend by</label>
                    <select id="ma-extend-days" class="form-select form-select-sm">${options}</select>
                </div>
                <p class="ma-hint ma-extend-new-expiry" id="ma-extend-new-expiry">${Utils.escapeHtml(newExpiryText(g, draft.days))}</p>
                <label for="ma-extend-reason" class="form-label">Why you need it longer</label>
                <textarea id="ma-extend-reason" class="form-control form-control-sm" rows="2"
                          minlength="10" maxlength="1000" required>${Utils.escapeHtml(draft.reason)}</textarea>
                <div class="ma-extend-error" role="alert">${draft.error ? Utils.escapeHtml(draft.error) : ''}</div>
                <div class="ma-extend-actions">
                    <button type="button" class="btn btn-light btn-sm" data-ma-action="extend-cancel">Cancel</button>
                    <button type="submit" class="btn btn-primary btn-sm"${state.extendSending ? ' disabled' : ''}>
                        ${state.extendSending ? 'Sending…' : 'Send request'}</button>
                </div>
            </form>
        `;
    }

    function pendingSectionHtml() {
        if (state.errors.requests) {
            return `<section class="ma-pendings"><h3 class="ma-section-title">Waiting for a decision</h3>${errorHtml('your requests')}</section>`;
        }
        const pending = pendingRequests();
        if (!pending.length) return '';
        const rows = pending.map(r => {
            const actions = state.cancellingId === r.requestId
                ? Utils.html`<div class="ma-confirm" role="group" aria-label="Confirm cancel">
                       <span>Cancel this request?</span>
                       <button type="button" id="ma-cancel-yes" class="btn btn-danger btn-sm" data-ma-action="cancel-confirm" data-request-id="${r.requestId}">Yes, cancel</button>
                       <button type="button" class="btn btn-light btn-sm" data-ma-action="cancel-keep">Keep</button>
                   </div>`
                : Utils.html`<button type="button" class="ma-link" data-ma-action="cancel" data-request-id="${r.requestId}">Cancel request</button>`;
            return Utils.html`
                <div class="ma-pending">
                    <div class="ma-pending-text">
                        <div><b>${r.environmentName}</b> · ${levelText(r)}${r.durationDays ? `, ${r.durationDays} days` : ''}</div>
                        <div class="ma-hint">Sent ${shortDate(r.createdAt)}</div>
                    </div>
                    ${Utils.raw(actions)}
                </div>`;
        }).join('');
        return `<section class="ma-pendings"><h3 class="ma-section-title">Waiting for a decision</h3>${rows}</section>`;
    }

    function endedSectionHtml() {
        const ended = state.history || [];
        if (!ended.length) return '';
        const rows = state.showEnded ? ended.map(g => Utils.html`
            <div class="ma-ended-row">
                <div>
                    <div><b>${g.environmentName}</b> · ${levelText(g)}</div>
                    <div class="ma-hint">${endedText(g)}</div>
                </div>
                <button type="button" class="btn btn-outline-secondary btn-sm" data-ma-action="request-again"
                        data-env-id="${g.environmentId}" data-env-name="${g.environmentName}"
                        data-scope-type="${g.scopeType || 'ENVIRONMENT'}"
                        data-group-id="${g.scopeType === 'GROUP' ? g.scopeId : ''}"
                        data-group-name="${g.scopeType === 'GROUP' ? (g.scopeName || '') : ''}"
                        data-level="${g.accessLevel || ''}">Request again</button>
            </div>`).join('') : '';
        const label = state.showEnded ? 'Hide ended grants' : `Show ${ended.length} ended grant${ended.length === 1 ? '' : 's'}`;
        return `<div class="ma-ended">
                <button type="button" class="ma-link" data-ma-action="toggle-ended" aria-expanded="${state.showEnded}">${label}</button>
                ${rows}
            </div>`;
    }

    // ----- Profile -----

    function profileHtml() {
        if (state.errors.profile) return errorHtml('your profile');
        const p = state.profile;
        if (!p) return loadingHtml();
        const teams = p.teamNames && p.teamNames.length ? p.teamNames.join(', ') : 'None';
        const row = (label, value) => Utils.html`<div class="ma-profile-row"><span class="ma-profile-label">${label}</span><span>${value}</span></div>`;
        return `<div class="ma-profile">
                ${row('Signed in with', signInText(p))}
                ${row('Role', roleDetail(p))}
                ${row(p.teamNames && p.teamNames.length > 1 ? 'Teams' : 'Team', teams)}
                ${row('Previous sign-in', p.previousLoginAt ? new Date(p.previousLoginAt).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : 'Not recorded yet')}
                <div class="ma-profile-row"><span class="ma-profile-label">Keyboard shortcuts</span><span><kbd>?</kbd> to see them</span></div>
                <button type="button" class="ma-link ma-sign-out" data-ma-action="sign-out">Sign out</button>
            </div>`;
    }

    // ============= Events =============

    function bindEvents() {
        $(document).on('click', `${ROOT} [data-ma-tab]`, function() {
            switchTab($(this).data('ma-tab'));
        });
        $(document).on('keydown', `${ROOT} [role="tab"]`, onTabKeydown);
        $(document).on('click', `${ROOT} [data-ma-action]`, onAction);
        $(document).on('submit', `${ROOT} .ma-extend`, onExtendSubmit);
        $(document).on('change', `${ROOT} [data-ma-pref]`, onPrefChange);
        $(document).on('input change', `${ROOT} .ma-extend select, ${ROOT} .ma-extend textarea`, function() {
            if (!state || !state.extendDraft) return;
            state.extendDraft.days = parseInt($('#ma-extend-days').val(), 10);
            state.extendDraft.reason = $('#ma-extend-reason').val();
            const grant = findGrant(state.extendingId);
            if (grant) $('#ma-extend-new-expiry').text(newExpiryText(grant, state.extendDraft.days));
        });
    }

    function switchTab(tab, focusTab) {
        if (!state || state.tab === tab) return;
        // Unsaved notification changes are not dropped silently (E29-T06).
        if (state.tab === 'notifications' && prefChanged()
            && !window.confirm('Discard your unsaved notification changes?')) return;
        if (state.tab === 'notifications') state.prefDraft = {};
        state.tab = tab;
        if (tab === 'cost') loadCost();
        if (tab === 'notifications') loadPrefs();
        render({ resetScroll: true });
        if (focusTab) $(`#ma-tab-${tab}`).trigger('focus');
    }

    function onTabKeydown(e) {
        const ids = visibleTabs().map(t => t[0]);
        const i = ids.indexOf(state.tab);
        let next = null;
        if (e.key === 'ArrowRight') next = ids[(i + 1) % ids.length];
        else if (e.key === 'ArrowLeft') next = ids[(i - 1 + ids.length) % ids.length];
        else if (e.key === 'Home') next = ids[0];
        else if (e.key === 'End') next = ids[ids.length - 1];
        if (next) {
            e.preventDefault();
            switchTab(next, true);
        }
    }

    function onAction() {
        const $btn = $(this);
        switch ($btn.data('ma-action')) {
            case 'retry':
                retryFailed();
                break;
            case 'prefs-save':
                savePrefs();
                break;
            case 'help':
                leaveTo('help', { topic: String($btn.data('help-topic')) });
                break;
            case 'lease-extend':
                extendLease(String($btn.data('env-id')));
                break;
            case 'request-access':
                leaveTo('request-access');
                break;
            case 'open-env':
                leaveTo('environment-detail', { environmentId: String($btn.data('env-id')) });
                break;
            case 'request-again':
                // Same scope and level as the grant that ended (LOW-ACC-4), on the one request form (U09-T02).
                Slideout.close();
                window.location.hash = '#/request-access?' + new URLSearchParams(Object.entries({
                    env: String($btn.data('env-id')),
                    name: String($btn.data('env-name') || ''),
                    group: $btn.attr('data-scope-type') === 'GROUP' ? ($btn.attr('data-group-id') || '') : '',
                    groupName: $btn.attr('data-group-name') || '',
                    level: $btn.attr('data-level') || ''
                }).filter(([, v]) => v)).toString();
                break;
            case 'sign-out':
                Slideout.close();
                Auth.logout();
                break;
            case 'extend':
                startExtension($btn.data('access-id'));
                break;
            case 'extend-cancel':
                state.extendingId = null;
                state.extendDraft = null;
                render();
                break;
            case 'cancel':
                state.cancellingId = $btn.data('request-id');
                render();
                $('#ma-cancel-yes').trigger('focus');
                break;
            case 'cancel-keep':
                state.cancellingId = null;
                render();
                break;
            case 'cancel-confirm':
                cancelRequest($btn.data('request-id'));
                break;
            case 'toggle-ended':
                state.showEnded = !state.showEnded;
                render();
                break;
        }
    }

    function leaveTo(contentType, params) {
        Slideout.close();
        ContentRouter.navigate(contentType, params);
    }

    function startExtension(accessId) {
        const grant = findGrant(accessId);
        if (!grant) return;
        state.extendingId = accessId;
        state.extendDraft = {
            days: defaultExtensionDays(),
            reason: `Extension: my access ends on ${shortDate(grant.expiresAt)}. `,
            error: null
        };
        render();
        const textarea = document.getElementById('ma-extend-reason');
        if (textarea) {
            textarea.focus();
            textarea.setSelectionRange(textarea.value.length, textarea.value.length);
        }
    }

    function onExtendSubmit(e) {
        e.preventDefault();
        if (state.extendSending) return;
        const grant = findGrant($(this).data('access-id'));
        const draft = state.extendDraft;
        if (!grant || !draft) return;

        const reason = (draft.reason || '').trim();
        if (reason.length < 10) {
            draft.error = 'Say why in at least 10 characters.';
            render();
            return;
        }

        draft.error = null;
        state.extendSending = true;
        render();

        const owner = state;
        ApiClient.post(Config.API.access.requestAccess(grant.environmentId), {
            accessLevel: grant.accessLevel,
            businessJustification: reason,
            durationDays: draft.days,
            scopeType: grant.scopeType,
            groupId: grant.scopeType === 'GROUP' ? grant.scopeId : null
        }, { suppressGlobalError: true })
            .done(() => {
                if (state !== owner) return;
                Notifications.success('Extension requested. You get a message when it is decided.');
                state.extendingId = null;
                state.extendDraft = null;
                loadRequests();
            })
            .fail(xhr => {
                if (state !== owner) return;
                draft.error = (xhr.responseJSON && xhr.responseJSON.message) || 'Could not send the request. Try again.';
            })
            .always(() => {
                if (state !== owner) return;
                state.extendSending = false;
                render();
            });
    }

    function cancelRequest(requestId) {
        const owner = state;
        ApiClient.delete(Config.API.access.cancelRequest(requestId), { suppressGlobalError: true })
            .done(() => {
                if (state !== owner) return;
                Notifications.success('Request cancelled.');
                state.cancellingId = null;
                loadRequests();
            })
            .fail(xhr => Notifications.error((xhr.responseJSON && xhr.responseJSON.message) || 'Could not cancel the request.'));
    }

    return {
        init,
        open,
        refresh,
        model: { initials, levelText, untilText, roleText, roleDetail, signInText, costTotal, endedText, shortDate, emptyAccessHtml }
    };
})();
