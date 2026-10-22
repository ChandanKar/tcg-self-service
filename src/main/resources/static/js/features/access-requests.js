/**
 * VM Self-Service Platform - Access Requests Module
 * The reviewer side of access requests (U10-T07, AccessRequests.dc.html): one card per waiting
 * request, oldest first, saying who wants what on which environment, why, which part, for how long
 * and what they have today. "Approve for" picks the length and Approve decides it in place; Deny…
 * asks for a reason. The requester's page is RequestAccess (request-access.js, U09-T02).
 */

const AccessRequests = (function() {
    'use strict';

    const NS = '.accessRequests';
    const WANTS = { VIEWER: 'view access', USER: 'operator access', ADMIN: 'admin access' };
    // "Approve for" choices besides the requested length (the server caps grants at
    // access.grant.max-duration-days).
    const APPROVE_DAYS = [7, 30, 90];
    const DAY_MS = 24 * 60 * 60 * 1000;

    // The waiting requests on the page (oldest first) and the environment filter ('' = all).
    let pendingList = [];
    let envFilter = '';
    // Three cards fit 1440 x 900 without a page scrollbar (U17-T07); the rest are a page away.
    const CARDS_PER_PAGE = 3;
    /** Cards a page on the page frame: as many as fit, at most CARDS_PER_PAGE (U18-T03). */
    let fitSize = null;
    let search = '';
    const cardsPerPage = () => fitSize || CARDS_PER_PAGE;
    let cardPage = 1;

    // ------------------------------------------------------------------ model

    function nameOf(req) {
        return req.requesterDisplayName || req.requesterEmail || 'Someone';
    }

    function initials(name) {
        const words = String(name || '').replace(/@.*/, '').split(/[\s._-]+/).filter(Boolean);
        if (!words.length) return '?';
        return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
    }

    function oldestFirst(list) {
        return (list || []).slice().sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    }

    /** "asked 2 h ago", "asked yesterday", "asked on 3 Oct". */
    function askedText(createdAt, now) {
        if (!createdAt) return '';
        const n = now ? new Date(now) : new Date();
        const t = new Date(createdAt);
        const min = Math.floor((n - t) / 60000);
        if (min < 1) return 'asked just now';
        if (min < 60) return `asked ${min} min ago`;
        if (min < 24 * 60) return `asked ${Math.floor(min / 60)} h ago`;
        const startOf = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
        const days = Math.round((startOf(n) - startOf(t)) / DAY_MS);
        if (days <= 1) return 'asked yesterday';
        if (days < 7) return `asked ${days} days ago`;
        const opts = { day: 'numeric', month: 'short' };
        if (t.getFullYear() !== n.getFullYear()) opts.year = 'numeric';
        return `asked on ${t.toLocaleDateString('en-GB', opts)}`;
    }

    function daysText(n, extension) {
        return `${n} ${extension ? 'more ' : ''}${n === 1 ? 'day' : 'days'}`;
    }

    /**
     * What a reviewer needs to know about a request: what is asked, which part, for how long and
     * what the person holds today.
     */
    function describeRequest(req, now) {
        const level = req.currentAccessLevel ? AccessLevels.label(req.currentAccessLevel) : null;
        const until = level && req.currentExpiresAt
            ? AccessLevels.endsText(req.currentExpiresAt, now).text.split(' · ')[0] : null;
        return {
            wants: req.extension ? 'an extension' : (WANTS[req.requestedAccessLevel] || 'access'),
            scope: req.scopeType === 'GROUP' ? `${req.scopeName || req.scopeId} group` : 'Whole environment',
            duration: req.durationDays ? daysText(req.durationDays, !!req.extension) : 'No end',
            current: !level ? 'None' : until ? `${level} until ${until}` : `${level}, no end date`,
            extension: !!req.extension
        };
    }

    /** "Approve for": the requested length first, then 7, 30, 90 days and No end. */
    function approveOptions(req) {
        const ext = !!req.extension;
        const asked = req.durationDays ? String(req.durationDays) : 'none';
        const all = APPROVE_DAYS.map(n => ({ value: String(n), label: daysText(n, ext) }))
            .concat([{ value: 'none', label: 'No end' }]);
        const first = all.find(o => o.value === asked) || { value: asked, label: daysText(req.durationDays, ext) };
        return [first].concat(all.filter(o => o.value !== asked));
    }

    /** The approve body (ReviewAccessRequestDTO) for an "Approve for" choice. */
    function approveBody(choice) {
        return choice === 'none' ? { clearExpiry: true } : { durationDays: parseInt(choice, 10) };
    }

    /** The environment filter: "All (<n> waiting)", then each environment with waiting requests. */
    function envOptions(list) {
        const names = new Map();
        (list || []).forEach(r => {
            if (!names.has(r.environmentId)) names.set(r.environmentId, r.environmentName || r.environmentId);
        });
        const envs = [...names].map(([value, label]) => ({ value, label }))
            .sort((a, b) => a.label.localeCompare(b.label));
        return [{ value: '', label: `All (${(list || []).length} waiting)` }].concat(envs);
    }

    // ------------------------------------------------------------------ page

    /** Router loader (see the page contract in core/router.js). */
    async function loadPendingRequestsPage() {
        const t = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied</div>');
            return;
        }

        showLoading();

        try {
            const requests = await fetchPendingRequests();
            if (!ContentRouter.isCurrent(t)) return;
            pendingList = oldestFirst(requests);
            envFilter = '';
            cardPage = 1;
            $('#content-area').html(pageHtml());
            render();
            bindPendingRequestsEvents();
            ContentRouter.onLeave(() => $(document).off(NS));
            loadApprovalsLine(t);
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load pending requests:', error);
            showError(error);
        }
    }

    /**
     * Fetch pending requests (admin)
     */
    function fetchPendingRequests() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.access.pendingRequests)
                .done(resolve)
                .fail(reject);
        });
    }

    /**
     * Show loading state
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading...</p>
            </div>
        `);
    }

    /** The page couldn't load (U17-T02). */
    function showError(xhr) {
        $('#content-area').html(UiStates.failedPage({ title: 'Access requests', what: 'The access requests', xhr }));
    }

    function pageHtml() {
        const filter = `<label class="ar-search"><span class="visually-hidden">Search requests</span>
                <input type="search" class="form-control" id="ar-search" placeholder="Person or environment" autocomplete="off"></label>
            <label class="ar-env-filter" for="ar-env-filter">Environment
                <select class="form-select" id="ar-env-filter"></select></label>`;
        return `
            <div class="content-view page page-frame ar-review-page" id="access-requests-view">
                ${PageHeader.markup({ title: 'Access requests',
                    summaryHtml: 'People asking to use environments you look after. Oldest first.',
                    actionsHtml: filter, helpPage: 'pending-requests', helpLabel: 'Access requests' })}
                <p class="ar-approvals" id="ar-approvals" hidden></p>
                <div class="ar-list list-panel page-fill" id="ar-list"></div>
            </div>`;
    }

    /** Requests to start or extend environments wait on Approvals; say so when it is in use. */
    function loadApprovalsLine(t) {
        if (!Config.API.approvals) return;
        ApiClient.get(Config.API.approvals.status, { suppressGlobalError: true })
            .done(function(status) {
                if (!ContentRouter.isCurrent(t) || !status || !status.visible) return;
                ApiClient.get(Config.API.approvals.list('to-review'), { suppressGlobalError: true })
                    .always(function(list) {
                        if (!ContentRouter.isCurrent(t)) return;
                        const waiting = Array.isArray(list)
                            ? list.filter(r => r.status === 'PENDING' && r.canDecide).length : 0;
                        $('#ar-approvals').html('Requests to start or extend environments are on '
                            + '<a href="#/approvals">Approvals</a>'
                            + (waiting ? ` · ${waiting} waiting.` : '.')).prop('hidden', false);
                    });
            });
    }

    function render() {
        const options = envOptions(pendingList);
        if (!options.some(o => o.value === envFilter)) envFilter = '';
        $('#ar-env-filter').html(options.map(o =>
            `<option value="${Utils.escapeHtml(o.value)}"${o.value === envFilter ? ' selected' : ''}>${Utils.escapeHtml(o.label)}</option>`
        ).join('')).prop('disabled', pendingList.length === 0);

        const q = search.trim().toLowerCase();
        const shown = pendingList.filter(r => (!envFilter || r.environmentId === envFilter)
            && (!q || [nameOf(r), r.requesterEmail, r.environmentName, r.environmentDisplayName, r.businessJustification]
                .some(v => String(v || '').toLowerCase().includes(q))));
        const pages = Math.max(1, Math.ceil(shown.length / cardsPerPage()));
        cardPage = Math.min(Math.max(1, cardPage), pages);
        // On the page frame (U18-T03): the cards scroll inside, the pager stays below them.
        $('#ar-list').html(shown.length
            ? `<div class="ar-cards list-panel-body" tabindex="0" role="region" aria-label="Access requests, scrolls">${
                Pagination.slice(shown, cardPage, cardsPerPage()).map(r => cardHtml(r)).join('')}</div>`
                + `<div class="list-panel-foot ar-list-foot">${shown.length > cardsPerPage() ? Pagination.buildPager({ page: cardPage, pageSize: cardsPerPage(),
                    totalItems: shown.length, action: 'ar-page', key: 'requests' }) : ''}</div>`
            : q && pendingList.length
                ? UiStates.filtered({ title: `No requests match "${search.trim()}".`, text: 'Check the spelling, or clear the search.' })
                : UiStates.empty({ title: 'No one is waiting.', text: 'New requests to use the environments you look after show here.' }));
        attachFit();
    }

    /** Cards a page follow the window: measured when the list first shows and when a resize settles. */
    function attachFit() {
        const panel = document.getElementById('ar-list');
        if (!panel || !panel.querySelector('.list-panel-body') || typeof ListPanel === 'undefined') return;
        const handle = ListPanel.attach(panel, { rowSelector: '.ar-card', rowHeight: 200, min: 1, max: CARDS_PER_PAGE, onFit: applyFit });
        if (fitSize === null) applyFit(handle.fitCount());
    }

    function applyFit(count) {
        if (!count || count === cardsPerPage()) return;
        const first = (cardPage - 1) * cardsPerPage();
        fitSize = count;
        cardPage = ListPanel.pageKeepingFirst(first, count);
        render();
    }

    function cardHtml(req, now) {
        const d = describeRequest(req, now);
        const name = nameOf(req);
        const id = Utils.escapeHtml(req.requestId);
        const options = approveOptions(req).map((o, i) =>
            `<option value="${Utils.escapeHtml(o.value)}"${i === 0 ? ' selected' : ''}>${Utils.escapeHtml(o.label)}</option>`).join('');
        const meta = [req.requesterEmail, askedText(req.createdAt, now)].filter(Boolean).join(' · ');
        const fact = (key, label, value) =>
            `<div data-fact="${key}"><dt>${label}</dt><dd>${Utils.escapeHtml(value)}</dd></div>`;
        return `
            <article class="ar-card" data-request-id="${id}" aria-labelledby="ar-title-${id}">
                <div class="ar-main">
                    <div class="ar-who">
                        <span class="ar-avatar" aria-hidden="true">${Utils.escapeHtml(initials(name))}</span>
                        <div class="ar-who-text">
                            <div class="ar-title" id="ar-title-${id}"><b>${Utils.escapeHtml(name)}</b> wants
                                <b>${Utils.escapeHtml(d.wants)}</b> on <b>${Utils.escapeHtml(req.environmentName || 'an environment')}</b></div>
                            <div class="ar-meta">${Utils.escapeHtml(meta)}</div>
                        </div>
                    </div>
                    ${req.businessJustification
                        ? `<p class="ar-reason">"${Utils.escapeHtml(req.businessJustification)}"</p>` : ''}
                    <dl class="ar-facts">
                        ${fact('scope', 'Part of it', d.scope)}
                        ${fact('for', 'For', d.duration)}
                        ${fact('current', 'Has today', d.current)}
                    </dl>
                </div>
                <div class="ar-actions">
                    <label class="ar-for" for="ar-for-${id}">Approve for</label>
                    <select class="form-select" id="ar-for-${id}" data-ar-for>${options}</select>
                    <button type="button" class="btn btn-primary ar-approve" data-action="approve" data-request-id="${id}">Approve</button>
                    <button type="button" class="btn ar-deny" data-action="deny" data-request-id="${id}">Deny…</button>
                </div>
            </article>`;
    }

    /** "Meera's", for the decline dialog's title. */
    function firstNameOf(req) {
        return req.requesterDisplayName ? req.requesterDisplayName.trim().split(/\s+/)[0] : nameOf(req);
    }

    /** The decline dialog's subtitle: 'Use CRM UAT for 14 days · "Regression run for 4.2"'. */
    function declineSubtitle(req) {
        const env = req.environmentName || 'the environment';
        const what = req.extension
            ? `Use ${env} for ${describeRequest(req).duration}`
            : `Use ${env}${req.durationDays ? ` for ${describeRequest(req).duration}` : ' with no end'}`;
        return req.businessJustification ? `${what} · "${req.businessJustification}"` : what;
    }

    /** Takes a decided request off the page and the sidebar badge. */
    function dropRequest(requestId) {
        pendingList = pendingList.filter(r => r.requestId !== requestId);
        render();
        updatePendingBadge();
    }

    function bindPendingRequestsEvents() {
        $(document).off(NS)
            .on('input' + NS, '#ar-search', function() {
                search = this.value;
                cardPage = 1;
                render();
            })
            .on('change' + NS, '#ar-env-filter', function() {
                envFilter = $(this).val() || '';
                cardPage = 1;
                render();
            })
            .on('click' + NS, '#access-requests-view [data-action="ar-page"]', function() {
                if (this.disabled) return;
                cardPage = Number(this.dataset.page) || 1;
                render();
                $('#ar-list').get(0).scrollIntoView({ block: 'nearest' });
            })
            .on('click' + NS, '#access-requests-view [data-action="approve"]', function() {
                const requestId = String($(this).data('request-id'));
                const choice = $(this).closest('.ar-card').find('[data-ar-for]').val();
                approve(requestId, choice, $(this));
            })
            .on('click' + NS, '#access-requests-view [data-action="deny"]', function() {
                const request = pendingList.find(r => r.requestId === String($(this).data('request-id')));
                if (request) showDeclineDialog(request, () => dropRequest(request.requestId));
            });
    }

    function approve(requestId, choice, $btn) {
        const $card = $btn.closest('.ar-card');
        $card.find('button, select').prop('disabled', true);
        $btn.text('Approving…');
        ApiClient.post(Config.API.access.approveRequest(requestId), approveBody(choice), { suppressGlobalError: true })
            .done(function() {
                Notifications.success('Access approved.');
                dropRequest(requestId);
            })
            .fail(function(xhr) {
                if (xhr.status === 409) {
                    // E04-T04: another reviewer (or the requester) got there first.
                    Notifications.warning('Someone else already reviewed this request');
                    dropRequest(requestId);
                    return;
                }
                $card.find('button, select').prop('disabled', false);
                $btn.text('Approve');
                Notifications.error(xhr.responseJSON?.message || 'Failed to approve request');
            });
    }

    /**
     * Decline dialog (U10-T08, AccessChange.dc.html): the reason is required and the requester
     * sees it with the decision; "Decline request" stays off until there is one.
     *
     * @param {object} request - the pending request
     * @param {function=} onDone - called once the request is declined (or found already decided)
     */
    function showDeclineDialog(request, onDone) {
        const first = firstNameOf(request);
        Modals.show({
            id: 'declineRequestModal',
            title: `Decline ${first}'s request`,
            body: Utils.html`
                <p class="acd-sub">${declineSubtitle(request)}</p>
                <form id="ar-decline-form" class="acd-form" novalidate>
                    <label class="acd-field" for="ar-decline-reason">Reason
                        <span class="acd-hint">${first} sees this with the decision</span></label>
                    <textarea class="form-control" id="ar-decline-reason" rows="3" maxlength="500" required></textarea>
                    <div class="acd-error" id="ar-decline-error" role="alert"></div>
                </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Decline request', class: 'btn-danger', id: 'ar-decline', disabled: true }
            ],
            onShow: function() {
                $('#ar-decline-reason').trigger('focus');
            }
        });
        const modal = document.getElementById('declineRequestModal');
        $('#ar-decline-reason').on('input', function() {
            $('#ar-decline').prop('disabled', !$(this).val().trim());
        });
        $('#ar-decline-form').on('submit', e => e.preventDefault());
        $('#ar-decline').on('click', function() {
            const reason = $('#ar-decline-reason').val().trim();
            if (!reason) return;
            $('#ar-decline-error').text('');
            $(this).prop('disabled', true).text('Declining…');
            ApiClient.post(Config.API.access.denyRequest(request.requestId), { notes: reason }, { suppressGlobalError: true })
                .done(() => {
                    Modals.hideWhenReady(modal);
                    Notifications.success('Request declined.');
                    if (onDone) onDone();
                })
                .fail(xhr => {
                    if (xhr.status === 409) {
                        Modals.hideWhenReady(modal);
                        Notifications.warning('Someone else already reviewed this request');
                        if (onDone) onDone();
                        return;
                    }
                    $('#ar-decline-error').text((xhr.responseJSON && xhr.responseJSON.message)
                        || "The request wasn't declined. Try again.");
                    $(this).prop('disabled', false).text('Decline request');
                });
        });
    }

    /**
     * Update pending requests badge in sidebar
     */
    function updatePendingBadge() {
        if (!Auth.isEnvAdmin()) return;

        ApiClient.get(Config.API.access.pendingRequests)
            .done(function(requests) {
                const count = requests ? requests.length : 0;
                if (count > 0) {
                    $('.pending-count').text(count).show();
                } else {
                    $('.pending-count').hide();
                }
            });
    }

    /**
     * Show environment access management modal
     */
    function showManageAccessModal(envId, envName) {
        Modals.show({
            id: 'manageAccessModal',
            title: `Manage Access: ${envName}`,
            size: 'lg',
            body: `
                <div class="manage-access-content">
                    ${UiStates.loading('rows', { count: 3 })}
                </div>
            `,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true },
                { text: 'Grant Access', class: 'btn-primary', id: 'grantAccessBtn' }
            ],
            onShow: function() {
                loadAccessList(envId);

                $('#grantAccessBtn').off('click').on('click', function() {
                    showGrantAccessModal(envId, envName);
                });
            }
        });
    }

    /**
     * Load access list for environment
     */
    function loadAccessList(envId) {
        ApiClient.get(Config.API.access.environmentAccess(envId))
            .done(function(accessList) {
                const html = buildAccessListHtml(accessList, envId);
                $('.manage-access-content').html(html);
                bindAccessListEvents(envId);
            })
            .fail(function() {
                $('.manage-access-content').html(`
                    <div class="alert alert-danger">Failed to load access list</div>
                `);
            });
    }

    /**
     * Build access list HTML
     */
    function buildAccessListHtml(accessList, envId) {
        if (!accessList || accessList.length === 0) {
            return `
                <div class="empty-state text-center py-4">
                    <i class="fas fa-users fa-2x text-muted mb-2"></i>
                    <p>No users have access to this environment</p>
                </div>
            `;
        }

        const rows = accessList.map(access => `
            <tr>
                <td>
                    <strong>${Utils.escapeHtml(access.userDisplayName || 'Unknown')}</strong>
                    <br><small class="text-muted">${Utils.escapeHtml(access.userEmail || '')}</small>
                </td>
                <td>
                    <span class="access-level-badge ${AccessLevels.chipClass(access.accessLevel)}">
                        ${Utils.escapeHtml(AccessLevels.label(access.accessLevel))}
                    </span>
                </td>
                <td>${access.grantedAt ? Utils.formatRelativeTime(access.grantedAt) : '-'}</td>
                <td>${access.expiresAt ? Utils.formatDate(access.expiresAt) : 'Never'}</td>
                <td class="td-actions">
                    <button class="btn btn-sm btn-outline-danger btn-ghost"
                            data-user-id="${access.userId}" data-action="revoke-access">
                        <i class="fas fa-times"></i> Revoke
                    </button>
                </td>
            </tr>
        `).join('');

        return `
            <table class="table table-sm mb-0 table-cards">
                <thead>
                    <tr>
                        <th>User</th>
                        <th>Access level</th>
                        <th>Granted</th>
                        <th>Expires</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Bind access list events
     */
    function bindAccessListEvents(envId) {
        $('[data-action="revoke-access"]').off('click').on('click', function() {
            const userId = $(this).data('user-id');
            revokeAccess(envId, userId);
        });
    }

    /**
     * Revoke user access
     */
    function revokeAccess(envId, userId) {
        Modals.confirm(
            'Revoke access',
            'Revoke this person\'s access to the environment? They can ask again later.',
            function() {
                ApiClient.delete(Config.API.access.revokeAccess(envId, userId))
                    .done(function() {
                        Notifications.success('Access revoked');
                        loadAccessList(envId);
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to revoke access');
                    });
            },
            { confirmText: 'Revoke', confirmClass: 'btn-danger' }
        );
    }

    /**
     * Show grant access modal
     */
    function showGrantAccessModal(envId, envName) {
        Modals.show({
            id: 'grantAccessModal',
            title: `Grant Access: ${envName}`,
            body: `
                <form id="grantAccessForm">
                    <div class="mb-3">
                        <label class="form-label">User Email <span class="text-danger">*</span></label>
                        <input type="email" class="form-control" id="grantUserEmail" required
                               placeholder="user@company.com">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Access level</label>
                        <select class="form-select" id="grantAccessLevel">
                            <option value="VIEWER">Viewer</option>
                            <option value="USER" selected>User</option>
                            <option value="ADMIN">Admin</option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Expiration</label>
                        <select class="form-select" id="grantExpiration">
                            <option value="">Never expires</option>
                            <option value="30">30 days</option>
                            <option value="90">90 days</option>
                            <option value="180">6 months</option>
                            <option value="365">1 year</option>
                        </select>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Grant Access', class: 'btn-primary', id: 'confirmGrant' }
            ],
            onShow: function() {
                $('#confirmGrant').off('click').on('click', function() {
                    const email = $('#grantUserEmail').val().trim();
                    const accessLevel = $('#grantAccessLevel').val();
                    const days = $('#grantExpiration').val();

                    if (!email) {
                        $('#grantUserEmail').addClass('is-invalid');
                        return;
                    }

                    let expiresAt = null;
                    if (days) {
                        const date = new Date();
                        date.setDate(date.getDate() + parseInt(days));
                        expiresAt = date.toISOString();
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Granting...');

                    ApiClient.post(Config.API.access.grantAccess(envId), {
                        userEmail: email,
                        accessLevel,
                        expiresAt
                    })
                    .done(function() {
                        Modals.hide('grantAccessModal');
                        Notifications.success('Access granted');
                        loadAccessList(envId);
                    })
                    .fail(function(xhr) {
                        $('#confirmGrant').prop('disabled', false).html('Grant Access');
                        Notifications.error(xhr.responseJSON?.message || 'Failed to grant access');
                    });
                });
            }
        });
    }

    // Public API
    return {
        loadPendingRequestsPage,
        showManageAccessModal,
        describeRequest,
        updatePendingBadge,
        model: { nameOf, initials, oldestFirst, askedText, describeRequest, approveOptions, approveBody, envOptions, cardHtml,
            firstNameOf, declineSubtitle }
    };
})();

window.AccessRequests = AccessRequests;

