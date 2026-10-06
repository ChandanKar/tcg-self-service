/**
 * VM Self-Service Platform - My Account panel
 * Slide-out opened from the user menu, with three tabs:
 *   Overview - who you are, how you sign in, at-a-glance counts, recent activity
 *   Access   - your grants (scope, level, expiry), pending requests, recently ended access
 *   Activity - your own VM operations and access history
 */

const MyAccount = (function() {
    'use strict';

    const LEVELS = {
        VIEWER: { label: 'View only', badge: 'level-viewer', help: 'See VMs, status and schedules' },
        USER: { label: 'Start & stop', badge: 'level-user', help: 'Everything above, plus start and stop VMs' },
        ADMIN: { label: 'Manage access', badge: 'level-admin', help: 'Everything above, plus grant access and approve requests' }
    };
    const TABS = [['overview', 'Overview'], ['access', 'Access'], ['activity', 'Activity']];
    const EXTENSION_DAYS = [7, 14, 30, 60, 90];
    const DEFAULT_EXTENSION_DAYS = 30;
    const DAY_MS = 24 * 60 * 60 * 1000;
    const ROOT = '#dynamicSlideoutPanel .my-account';

    let state = null;

    function init() {
        bindEvents();
    }

    /**
     * Open the panel.
     * @param {string=} tab - 'overview' (default), 'access' or 'activity'
     */
    function open(tab) {
        state = {
            tab: TABS.some(t => t[0] === tab) ? tab : 'overview',
            profile: null,
            grants: null,
            requests: null,
            history: null,
            activity: null,
            errors: {},
            filter: 'all',
            showEnded: false,
            extendingId: null,
            extendDraft: null,
            extendSending: false,
            cancellingId: null
        };
        Slideout.open('My Account', `
            <div class="my-account">
                <div class="ma-tabs" role="tablist" aria-label="My Account sections"></div>
                <div class="ma-body" role="tabpanel" tabindex="0"></div>
            </div>
        `, { variant: 'my-account' });
        render();
        loadAll();
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

    function loadAll() {
        load('profile', Config.API.users.myProfile);
        load('grants', Config.API.access.myEnvironments);
        loadRequests();
        load('history', Config.API.access.myAccessHistory(30));
        loadActivity();
    }

    function loadRequests() {
        return load('requests', Config.API.access.myRequests);
    }

    function loadActivity() {
        return load('activity', Config.API.users.myActivity(25));
    }

    function retryFailed() {
        const urls = {
            profile: Config.API.users.myProfile,
            grants: Config.API.access.myEnvironments,
            requests: Config.API.access.myRequests,
            history: Config.API.access.myAccessHistory(30),
            activity: Config.API.users.myActivity(25)
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

    function daysUntil(ts) {
        return Math.ceil((new Date(ts).getTime() - Date.now()) / DAY_MS);
    }

    function isExpiring(grant) {
        return !!grant.expiresAt && daysUntil(grant.expiresAt) <= windowDays();
    }

    function expiringGrants() {
        return (state.grants || []).filter(isExpiring);
    }

    function pendingRequests() {
        return (state.requests || []).filter(r => r.status === 'PENDING');
    }

    function pendingRequestFor(grant) {
        return pendingRequests().find(r => r.scopeType === grant.scopeType && r.scopeId === grant.scopeId);
    }

    /** Expiring soonest first, then permanent / far-off grants by environment name. */
    function sortGrants(grants) {
        return grants.slice().sort((a, b) => {
            const ea = isExpiring(a), eb = isExpiring(b);
            if (ea !== eb) return ea ? -1 : 1;
            if (ea && eb) return new Date(a.expiresAt) - new Date(b.expiresAt);
            return (a.environmentName || '').localeCompare(b.environmentName || '');
        });
    }

    function roleInfo(profile) {
        if (profile.admin) return { label: 'Administrator', cls: 'role-badge-admin' };
        if (profile.envAdmin) return { label: 'Environment Admin', cls: 'role-badge-env-admin' };
        return { label: 'User', cls: 'role-badge-user' };
    }

    function levelOf(level) {
        return LEVELS[level] || LEVELS.VIEWER;
    }

    function scopeText(item) {
        return item.scopeType === 'GROUP' ? `Group: ${esc(item.scopeName)}` : 'Entire environment';
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
        const focusedId = document.activeElement && $root[0].contains(document.activeElement)
            ? document.activeElement.id : null;

        $root.find('.ma-tabs').html(tabsHtml());
        $body.attr('aria-labelledby', `ma-tab-${state.tab}`).html(bodyHtml());
        $body.scrollTop(scrollTop);

        if (focusedId) {
            const el = document.getElementById(focusedId);
            if (el) el.focus();
        }
    }

    function tabsHtml() {
        const expiring = state.grants ? expiringGrants().length : 0;
        return TABS.map(([id, label]) => {
            const selected = state.tab === id;
            const count = id === 'access' && expiring > 0
                ? `<span class="ma-tab-count" title="${expiring} expiring soon">${expiring}</span>` : '';
            return `<button type="button" role="tab" id="ma-tab-${id}" class="ma-tab${selected ? ' active' : ''}"
                        aria-selected="${selected}" tabindex="${selected ? 0 : -1}" data-ma-tab="${id}">${label}${count}</button>`;
        }).join('');
    }

    function bodyHtml() {
        if (state.tab === 'access') return accessHtml();
        if (state.tab === 'activity') return activityHtml();
        return overviewHtml();
    }

    function loadingHtml() {
        return '<div class="ma-loading"><i class="fas fa-spinner fa-spin" aria-hidden="true"></i> Loading…</div>';
    }

    function errorHtml(what) {
        return `<div class="ma-error" role="alert">Couldn't load ${what}.
                    <button type="button" class="ma-link" data-ma-action="retry">Try again</button></div>`;
    }

    // ----- Overview -----

    function overviewHtml() {
        if (state.errors.profile) return errorHtml('your profile');
        const p = state.profile;
        if (!p) return loadingHtml();

        const role = roleInfo(p);
        const entra = p.authMethod === 'ENTRA_ID';
        const grants = state.grants || [];
        const envCount = new Set(grants.map(g => g.environmentId)).size;
        const expiring = expiringGrants().length;
        const pending = pendingRequests().length;

        return `
            <div class="ma-identity">
                <span class="ma-avatar" aria-hidden="true">${esc(initials(p.displayName))}</span>
                <div class="ma-identity-text">
                    <div class="ma-name">${esc(p.displayName)}</div>
                    <div class="ma-email">${esc(p.email)}</div>
                    <div class="ma-chips">
                        <span class="role-badge ${role.cls}">${role.label}</span>
                        <span class="ma-chip"><i class="fas ${entra ? 'fa-shield-alt' : 'fa-key'}" aria-hidden="true"></i>
                            ${entra ? 'Microsoft Entra ID' : 'Username &amp; password'}</span>
                    </div>
                </div>
            </div>

            <div class="ma-stats">
                ${statTileHtml(state.grants ? envCount : '–', envCount === 1 ? 'Environment' : 'Environments', '')}
                ${statTileHtml(state.grants ? expiring : '–', `Expiring in ${windowDays()} days`, expiring > 0 ? 'warn' : '')}
                ${statTileHtml(state.requests ? pending : '–', pending === 1 ? 'Request pending' : 'Requests pending', '')}
            </div>

            <h4 class="ma-section-title">Account details</h4>
            <dl class="ma-details">
                ${p.companyName ? detailRow('Company', esc(p.companyName)) : ''}
                ${detailRow('Sign-in method', entra ? 'Microsoft Entra ID (single sign-on)' : 'Username and password')}
                ${detailRow('Previous sign-in', p.previousLoginAt
                    ? formatDateTime(p.previousLoginAt) : '<span class="ma-muted">Not recorded yet</span>')}
                ${p.onboardedAt ? detailRow('Onboarded',
                    formatDate(p.onboardedAt) + (p.onboardedByName ? ` by ${esc(p.onboardedByName)}` : '')) : ''}
                ${detailRow('Member since', p.createdAt ? formatDate(p.createdAt) : '—')}
            </dl>
            ${entra ? `<p class="ma-note"><i class="fas fa-info-circle" aria-hidden="true"></i>
                Your name and email come from Microsoft Entra ID. To change them, contact your IT administrator.</p>` : ''}

            <div class="ma-section-head">
                <h4 class="ma-section-title">Recent activity</h4>
                <button type="button" class="ma-link" data-ma-tab="activity">View all</button>
            </div>
            ${recentActivityHtml()}
        `;
    }

    function statTileHtml(value, label, modifier) {
        return `<button type="button" class="ma-stat ${modifier}" data-ma-tab="access">
                    <span class="ma-stat-value">${value}</span>
                    <span class="ma-stat-label">${label}</span>
                </button>`;
    }

    function detailRow(label, valueHtml) {
        return `<dt>${label}</dt><dd>${valueHtml}</dd>`;
    }

    function recentActivityHtml() {
        if (state.errors.activity) return errorHtml('your activity');
        if (!state.activity) return loadingHtml();
        if (!state.activity.length) return '<p class="ma-muted">Nothing yet.</p>';
        return `<ul class="ma-recent">${state.activity.slice(0, 3).map(item => {
            const d = describe(item);
            return `<li>
                        <span class="ma-dot ${d.pill.cls}" aria-hidden="true"></span>
                        <span class="ma-recent-text">${esc(d.title)} <span class="ma-muted">· ${d.where}</span></span>
                        <time class="ma-muted" datetime="${esc(item.occurredAt)}" title="${formatDateTime(item.occurredAt)}">${Utils.formatRelativeTime(item.occurredAt)}</time>
                    </li>`;
        }).join('')}</ul>`;
    }

    // ----- Access -----

    function accessHtml() {
        if (state.errors.grants) return errorHtml('your access');
        if (!state.grants) return loadingHtml();
        const grants = sortGrants(state.grants);

        return `
            ${roleBannerHtml()}
            <div class="ma-access-head">
                <p class="ma-summary">${accessSummary(grants)}</p>
                <button type="button" class="btn btn-primary btn-sm" data-ma-action="request-access">
                    <i class="fas fa-plus" aria-hidden="true"></i> Request access</button>
            </div>
            ${grants.length ? grants.map(grantCardHtml).join('') : emptyAccessHtml()}
            ${pendingSectionHtml()}
            ${endedSectionHtml()}
            ${levelsLegendHtml()}
        `;
    }

    function roleBannerHtml() {
        const p = state.profile;
        if (!p) return '';
        let text = '';
        if (p.admin) {
            text = 'As an Administrator you can view and manage every environment. The list below shows only access assigned to you directly.';
        } else if (p.envAdmin && p.administeredEnvironments.length) {
            const names = p.administeredEnvironments.map(e => esc(e.name)).join(', ');
            text = `You are an Environment Admin for ${names}, so you can grant access and approve requests there.`;
        }
        return text ? `<div class="ma-banner"><i class="fas fa-shield-alt" aria-hidden="true"></i><span>${text}</span></div>` : '';
    }

    function accessSummary(grants) {
        if (!grants.length) return 'No active access';
        const envCount = new Set(grants.map(g => g.environmentId)).size;
        let text = `Access to ${envCount} environment${envCount === 1 ? '' : 's'}`;
        const expiring = expiringGrants().length;
        if (expiring) text += ` · <strong>${expiring} expiring soon</strong>`;
        return text;
    }

    function emptyAccessHtml() {
        return `<div class="ma-empty">
                    <i class="fas fa-lock" aria-hidden="true"></i>
                    <p class="ma-empty-title">You don't have access to any environments yet</p>
                    <p>Request access to an environment and its admins will review it. You'll get a notification when they decide.</p>
                </div>`;
    }

    function expiryText(grant) {
        if (!grant.expiresAt) return 'No expiry';
        const days = daysUntil(grant.expiresAt);
        const date = formatDate(grant.expiresAt);
        if (days <= 0) return `Expires today · ${date}`;
        if (days === 1) return `Expires tomorrow · ${date}`;
        if (isExpiring(grant)) return `Expires in ${days} days · ${date}`;
        return `Expires ${date} · in ${days} days`;
    }

    function grantCardHtml(g) {
        const level = levelOf(g.accessLevel);
        const expiring = isExpiring(g);
        const via = g.initiation === 'REQUEST' ? 'via request' : 'granted directly';
        const by = g.grantedByUserName ? `Granted by ${esc(g.grantedByUserName)} · ` : '';

        let action = '';
        if (expiring) {
            action = pendingRequestFor(g)
                ? '<span class="ma-pill info">Extension requested</span>'
                : `<button type="button" class="btn btn-sm ma-btn-warn" data-ma-action="extend" data-access-id="${esc(g.accessId)}"
                        aria-expanded="${state.extendingId === g.accessId}">Request extension</button>`;
        }

        return `
            <article class="ma-card${expiring ? ' expiring' : ''}">
                <div class="ma-card-top">
                    <button type="button" class="ma-env-link" data-ma-action="open-env"
                            data-env-id="${esc(g.environmentId)}" data-env-name="${esc(g.environmentName)}">
                        ${esc(g.environmentName)} <i class="fas fa-arrow-right" aria-hidden="true"></i></button>
                    <span class="access-level-badge ${level.badge}">${level.label}</span>
                </div>
                <div class="ma-card-meta">
                    <span><i class="fas fa-layer-group" aria-hidden="true"></i> ${scopeText(g)}</span>
                    <span class="ma-expiry${expiring ? ' warn' : ''}"><i class="fas fa-clock" aria-hidden="true"></i> ${expiryText(g)}</span>
                </div>
                ${g.notes ? `<p class="ma-card-notes">${esc(g.notes)}</p>` : ''}
                <div class="ma-card-foot">
                    <span class="ma-muted">${by}${via} · ${formatDate(g.grantedAt)}</span>
                    ${action}
                </div>
                ${state.extendingId === g.accessId ? extendFormHtml(g) : ''}
            </article>
        `;
    }

    function extendFormHtml(g) {
        const draft = state.extendDraft;
        const options = EXTENSION_DAYS.map(d =>
            `<option value="${d}"${d === draft.days ? ' selected' : ''}>${d} days</option>`).join('');
        return `
            <form class="ma-extend" data-access-id="${esc(g.accessId)}" novalidate>
                <div class="ma-extend-row">
                    <label for="ma-extend-days">Extend by</label>
                    <select id="ma-extend-days" class="form-select form-select-sm">${options}</select>
                </div>
                <label for="ma-extend-reason" class="form-label">Reason</label>
                <textarea id="ma-extend-reason" class="form-control form-control-sm" rows="2"
                          minlength="10" maxlength="1000" required>${esc(draft.reason)}</textarea>
                <div class="ma-extend-error" role="alert">${draft.error ? esc(draft.error) : ''}</div>
                <div class="ma-extend-actions">
                    <button type="button" class="btn btn-light btn-sm" data-ma-action="extend-cancel">Cancel</button>
                    <button type="submit" class="btn btn-primary btn-sm"${state.extendSending ? ' disabled' : ''}>
                        ${state.extendSending ? '<i class="fas fa-spinner fa-spin" aria-hidden="true"></i> Sending…' : 'Send request'}</button>
                </div>
            </form>
        `;
    }

    function pendingSectionHtml() {
        if (state.errors.requests) {
            return `<h4 class="ma-section-title">Pending requests</h4>${errorHtml('your requests')}`;
        }
        const pending = pendingRequests();
        if (!pending.length) return '';

        return `
            <h4 class="ma-section-title">Pending requests (${pending.length})</h4>
            ${pending.map(r => {
                const id = esc(r.requestId);
                const actions = state.cancellingId === r.requestId
                    ? `<div class="ma-confirm" role="group" aria-label="Confirm cancel">
                           <span>Cancel this request?</span>
                           <button type="button" id="ma-cancel-yes" class="btn btn-danger btn-sm" data-ma-action="cancel-confirm" data-request-id="${id}">Yes, cancel</button>
                           <button type="button" class="btn btn-light btn-sm" data-ma-action="cancel-keep">Keep</button>
                       </div>`
                    : `<button type="button" class="btn btn-outline-danger btn-sm" data-ma-action="cancel" data-request-id="${id}">Cancel request</button>`;
                return `
                    <div class="ma-pending">
                        <div class="ma-pending-text">
                            <div class="ma-row-title">${esc(r.environmentName)}
                                <span class="ma-muted">· ${levelOf(r.requestedAccessLevel).label} · ${scopeText(r)}</span></div>
                            <div class="ma-muted">Submitted ${formatDate(r.createdAt)}${r.durationDays ? ` · for ${r.durationDays} days` : ''} · Waiting for an environment admin</div>
                        </div>
                        ${actions}
                    </div>`;
            }).join('')}
        `;
    }

    function endedSectionHtml() {
        const ended = state.history || [];
        if (!ended.length) return '';

        const rows = state.showEnded ? ended.map(g => `
            <div class="ma-ended-row">
                <div>
                    <div class="ma-row-title">${esc(g.environmentName)}
                        <span class="ma-muted">· ${levelOf(g.accessLevel).label} · ${scopeText(g)}</span></div>
                    <div class="ma-muted">${g.status === 'REVOKED' && g.revokedAt
                        ? `Revoked ${formatDate(g.revokedAt)}` : `Expired ${formatDate(g.expiresAt)}`}</div>
                </div>
                <button type="button" class="btn btn-outline-primary btn-sm" data-ma-action="request-again"
                        data-env-id="${esc(g.environmentId)}" data-env-name="${esc(g.environmentName)}">Request again</button>
            </div>`).join('') : '';

        return `
            <div class="ma-ended">
                <button type="button" class="ma-disclosure" data-ma-action="toggle-ended" aria-expanded="${state.showEnded}">
                    <i class="fas fa-chevron-right${state.showEnded ? ' open' : ''}" aria-hidden="true"></i>
                    Recently expired or revoked (${ended.length})
                </button>
                ${rows}
            </div>
        `;
    }

    function levelsLegendHtml() {
        return `
            <div class="ma-legend">
                <div class="ma-legend-title">What the access levels mean</div>
                <dl>
                    ${Object.values(LEVELS).map(l =>
                        `<dt><span class="access-level-badge ${l.badge}">${l.label}</span></dt><dd>${l.help}</dd>`).join('')}
                </dl>
            </div>
        `;
    }

    // ----- Activity -----

    function activityHtml() {
        if (state.errors.activity) return errorHtml('your activity');
        if (!state.activity) return loadingHtml();

        const filters = [['all', 'All'], ['ops', 'VM operations'], ['access', 'Access changes']];
        const items = state.activity.filter(i =>
            state.filter === 'all' || (state.filter === 'ops' ? i.kind === 'OPERATION' : i.kind === 'ACCESS'));

        const list = items.length ? `<ul class="ma-activity">${items.map(activityItemHtml).join('')}</ul>`
            : '<p class="ma-muted ma-activity-empty">Nothing here yet.</p>';

        return `
            <div class="ma-filters" role="group" aria-label="Filter activity">
                ${filters.map(([id, label]) =>
                    `<button type="button" class="ma-filter${state.filter === id ? ' active' : ''}" aria-pressed="${state.filter === id}"
                             data-ma-action="filter" data-filter="${id}">${label}</button>`).join('')}
            </div>
            ${list}
            <button type="button" class="ma-link" data-ma-action="activity-logs">View full history in Activity Logs →</button>
        `;
    }

    function activityItemHtml(item) {
        const d = describe(item);
        return `
            <li class="ma-activity-item">
                <span class="ma-activity-icon ${d.pill.cls}" aria-hidden="true"><i class="fas ${d.icon}"></i></span>
                <div class="ma-activity-text">
                    <div class="ma-row-title">${esc(d.title)}</div>
                    <div class="ma-muted">${d.where}</div>
                    ${d.detail ? `<div class="ma-activity-detail">${esc(d.detail)}</div>` : ''}
                </div>
                <div class="ma-activity-side">
                    <span class="ma-pill ${d.pill.cls}">${d.pill.label}</span>
                    <time class="ma-muted" datetime="${esc(item.occurredAt)}" title="${formatDateTime(item.occurredAt)}">${Utils.formatRelativeTime(item.occurredAt)}</time>
                </div>
            </li>
        `;
    }

    /** Title, location, icon and status pill for one activity entry. Strings in `where` are already escaped. */
    function describe(item) {
        if (item.kind === 'OPERATION') {
            const verb = { START: 'Started', STOP: 'Stopped', RESTART: 'Restarted' }[item.event] || item.event;
            const n = item.totalTargets || 0;
            const status = {
                COMPLETED: ['Completed', 'ok'],
                PARTIAL_SUCCESS: ['Partial', 'warn'],
                FAILED: ['Failed', 'err'],
                CANCELLED: ['Cancelled', 'muted'],
                IN_PROGRESS: ['In progress', 'info'],
                PENDING: ['Queued', 'info']
            }[item.status] || [item.status, 'muted'];
            let detail = '';
            if (item.status === 'PARTIAL_SUCCESS') {
                detail = `${item.completedTargets || 0} of ${n} succeeded${item.note ? ': ' + item.note : ''}`;
            } else if (item.status === 'FAILED') {
                detail = item.note || '';
            }
            return {
                title: `${verb} ${n} VM${n === 1 ? '' : 's'}`,
                where: esc(item.environmentName),
                icon: { START: 'fa-play', STOP: 'fa-stop', RESTART: 'fa-redo' }[item.event] || 'fa-server',
                pill: { label: status[0], cls: status[1] },
                detail: detail
            };
        }

        const by = item.actorName ? ` by ${item.actorName}` : '';
        const access = {
            REQUESTED: ['Requested access', 'fa-paper-plane', 'Submitted', 'info'],
            APPROVED: [`Request approved${by}`, 'fa-check', 'Approved', 'ok'],
            DENIED: [`Request denied${by}`, 'fa-times', 'Denied', 'err'],
            CANCELLED: ['Request cancelled', 'fa-ban', 'Cancelled', 'muted'],
            GRANTED: [`Access granted${by}`, 'fa-key', 'Granted', 'ok'],
            REVOKED: ['Access revoked', 'fa-ban', 'Revoked', 'err'],
            EXPIRED: ['Access expired', 'fa-hourglass-end', 'Expired', 'muted']
        }[item.event] || [item.event, 'fa-key', item.event, 'muted'];
        const where = esc(item.environmentName)
            + (item.scopeName ? ` › ${esc(item.scopeName)}` : '')
            + (item.accessLevel ? ` · ${levelOf(item.accessLevel).label}` : '');
        return {
            title: access[0],
            where: where,
            icon: access[1],
            pill: { label: access[2], cls: access[3] },
            detail: item.note || ''
        };
    }

    // ============= Events =============

    function bindEvents() {
        $(document).on('click', `${ROOT} [data-ma-tab]`, function() {
            switchTab($(this).data('ma-tab'));
        });
        $(document).on('keydown', `${ROOT} [role="tab"]`, onTabKeydown);
        $(document).on('click', `${ROOT} [data-ma-action]`, onAction);
        $(document).on('submit', `${ROOT} .ma-extend`, onExtendSubmit);
        $(document).on('input change', `${ROOT} .ma-extend select, ${ROOT} .ma-extend textarea`, function() {
            if (!state || !state.extendDraft) return;
            state.extendDraft.days = parseInt($('#ma-extend-days').val(), 10);
            state.extendDraft.reason = $('#ma-extend-reason').val();
        });
    }

    function switchTab(tab, focusTab) {
        if (!state || state.tab === tab) return;
        state.tab = tab;
        render({ resetScroll: true });
        if (focusTab) $(`#ma-tab-${tab}`).trigger('focus');
    }

    function onTabKeydown(e) {
        const ids = TABS.map(t => t[0]);
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
            case 'request-access':
                leaveTo('request-access');
                break;
            case 'open-env':
                leaveTo('environment-detail', {
                    environmentId: $btn.data('env-id'),
                    environmentName: $btn.data('env-name')
                });
                break;
            case 'request-again':
                leaveTo('request-access');
                AccessRequests.showRequestAccessModal($btn.data('env-id'), $btn.data('env-name'));
                break;
            case 'activity-logs':
                leaveTo('activity-logs');
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
            case 'filter':
                state.filter = $btn.data('filter');
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
            days: DEFAULT_EXTENSION_DAYS,
            reason: `Extension: my access expires on ${formatDate(grant.expiresAt)}. `,
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
            draft.error = 'Please give a reason of at least 10 characters.';
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
                Notifications.success('Extension requested. You will be notified when it is reviewed.');
                state.extendingId = null;
                state.extendDraft = null;
                loadRequests();
                loadActivity();
            })
            .fail(xhr => {
                if (state !== owner) return;
                draft.error = xhr.responseJSON?.message || 'Could not send the request. Please try again.';
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
                Notifications.success('Request cancelled');
                state.cancellingId = null;
                loadRequests();
                loadActivity();
            })
            .fail(xhr => Notifications.error(xhr.responseJSON?.message || 'Failed to cancel request'));
    }

    // ============= Formatting =============

    function esc(text) {
        return Utils.escapeHtml(text == null ? '' : String(text));
    }

    function initials(name) {
        if (!name) return '?';
        const parts = name.trim().split(/\s+/);
        return (parts.length >= 2 ? parts[0][0] + parts[parts.length - 1][0] : name.substring(0, 2)).toUpperCase();
    }

    function formatDate(ts) {
        if (!ts) return '—';
        return new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    function formatDateTime(ts) {
        if (!ts) return '—';
        return new Date(ts).toLocaleString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
        });
    }

    return {
        init,
        open
    };
})();
