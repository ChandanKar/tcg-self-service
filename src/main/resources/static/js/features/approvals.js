/**
 * Operation approvals (E25-T06). #/approvals: what waits for the signed-in user (approve, or
 * deny with a reason) and the status of their own requests (cancel while pending). On the
 * environment page a banner shows a pending start or extension of the user's own, who it waits
 * for, and updates itself until it is decided.
 */
const Approvals = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const STATUS = {
        PENDING: ['Waiting', 'text-bg-warning'], APPROVED: ['Approved', 'text-bg-success'], EXECUTED: ['Running', 'text-bg-success'],
        DENIED: ['Denied', 'text-bg-danger'], EXPIRED: ['Expired', 'text-bg-secondary'], CANCELLED: ['Cancelled', 'text-bg-secondary'],
        FAILED: ['Failed', 'text-bg-danger']
    };
    let pageToken = null;
    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function money(v) {
        return v === null || v === undefined ? '—' : Utils.formatCurrency(Number(v));
    }

    function badge(status) {
        const [label, cls] = STATUS[status] || [status, 'text-bg-light'];
        return Utils.html`<span class="badge ${cls}">${label}</span>`;
    }

    function what(r) {
        return r.requestType === 'EXTEND' ? `Keep ${r.environmentName} running longer` : `Start ${r.environmentName}`;
    }

    // ---------------------------------------------------------------- page

    function load() {
        pageToken = ContentRouter.token();
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = { tab: q.tab === 'mine' ? 'mine' : 'review', review: null, mine: null };
        $('#content-area').html(`
            <div class="content-view approvals-page" id="approvals-view">
                <div class="content-header">
                    <h1><i class="fas fa-stamp me-2" aria-hidden="true"></i>Approvals ${typeof HelpLink !== 'undefined' ? HelpLink.button('approvals', 'Approvals') : ''}</h1>
                    <p class="text-muted mb-0">Starts and extensions that need approval because of their size, length or cost</p>
                </div>
                <ul class="nav nav-tabs mb-3" role="tablist">
                    <li class="nav-item" role="presentation"><button type="button" class="nav-link" role="tab" id="approvals-tab-review"
                        aria-controls="approvals-body" data-action="approvals-tab" data-tab="review">To review</button></li>
                    <li class="nav-item" role="presentation"><button type="button" class="nav-link" role="tab" id="approvals-tab-mine"
                        aria-controls="approvals-body" data-action="approvals-tab" data-tab="mine">My requests</button></li>
                </ul>
                <div id="approvals-body" role="tabpanel"></div>
            </div>`);
        showTab(state.tab);
    }

    function showTab(tab) {
        state.tab = tab;
        ['review', 'mine'].forEach(t => $(`#approvals-tab-${t}`).toggleClass('active', t === tab).attr('aria-selected', String(t === tab)));
        $('#approvals-body').attr('aria-labelledby', `approvals-tab-${tab}`)
            .html('<div class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading…</div>');
        const token = pageToken;
        call('GET', Config.API.approvals.list(tab === 'review' ? 'to-review' : 'mine'))
            .then(list => {
                if (!ContentRouter.isCurrent(token) || state.tab !== tab) return;
                state[tab] = list || [];
                draw();
            })
            .catch(() => {
                if (!ContentRouter.isCurrent(token)) return;
                $('#approvals-body').html(`<div class="cost-panel-error" role="alert"><span>Couldn't load approvals.</span>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="approvals-tab" data-tab="${tab}">Retry</button></div>`);
            });
    }

    function card(r, review) {
        const approvers = r.approvers.map(a => Utils.html`<li>${a.name}${Utils.raw(a.decision
            ? ` <span class="badge ${a.decision === 'APPROVED' ? 'text-bg-success' : 'text-bg-danger'}">${a.decision === 'APPROVED' ? 'approved' : 'denied'}</span>`
            : ' <span class="text-muted small">waiting</span>')}${Utils.raw(a.reason ? Utils.html` <span class="small text-muted">— ${a.reason}</span>` : '')}</li>`).join('');
        const actions = review && r.canDecide ? Utils.html`<div class="d-flex gap-2 flex-wrap">
                <button type="button" class="btn btn-sm btn-success" data-action="approval-approve" data-id="${r.requestId}">Approve</button>
                <button type="button" class="btn btn-sm btn-ghost" data-action="approval-deny" data-id="${r.requestId}">Deny</button></div>`
            : !review && r.canCancel ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="approval-cancel" data-id="${r.requestId}">Cancel request</button>`
            : '';
        return Utils.html`<section class="card mb-2 approval-card" data-request="${r.requestId}"><div class="card-body">
            <div class="d-flex justify-content-between align-items-start gap-2 flex-wrap">
                <div>
                    <h2 class="h6 mb-1">${what(r)} ${Utils.raw(badge(r.status))}</h2>
                    <div class="small text-muted">${review ? `Asked by ${r.requesterName} · ` : ''}${r.scopeSummary || ''} · about ${money(r.estimatedCost)}
                        · ${r.approverMode === 'ALL' ? 'every approver must agree' : 'one approval is enough'}</div>
                </div>
                ${Utils.raw(actions)}
            </div>
            ${Utils.raw(r.reasons.length ? Utils.html`<div class="small mt-2"><strong>Why approval is needed:</strong> ${r.reasons.join('; ')}</div>` : '')}
            ${Utils.raw(r.statusReason ? Utils.html`<div class="small mt-1">${r.statusReason}</div>` : '')}
            <ul class="small mb-0 mt-2 approval-approvers">${Utils.raw(approvers)}</ul>
        </div></section>`;
    }

    function draw() {
        const review = state.tab === 'review';
        const list = state[state.tab] || [];
        const shown = review ? list.filter(r => r.status === 'PENDING').concat(list.filter(r => r.status !== 'PENDING').slice(0, 20)) : list;
        $('#approvals-body').html(shown.length ? shown.map(r => card(r, review)).join('')
            : `<p class="text-muted">${review ? 'Nothing waits for your approval.' : 'You have no approval requests.'}</p>`);
    }

    function approve(id) {
        const r = (state.review || []).find(x => x.requestId === id);
        if (!r) return;
        Modals.confirm('Approve', `${what(r)} (${r.scopeSummary || ''}, about ${money(r.estimatedCost)})? It runs as ${r.requesterName}.`, () => {
            call('POST', Config.API.approvals.approve(id), {})
                .then(updated => {
                    Notifications.success(updated.status === 'PENDING' ? 'Approved; waiting for the other approvers.' : 'Approved.');
                    showTab('review');
                })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not approve.')));
        }, { confirmText: 'Approve', confirmClass: 'btn-success' });
    }

    function deny(id) {
        Modals.prompt('Deny request', 'Why? The requester sees this.', reason => {
            call('POST', Config.API.approvals.deny(id), { reason })
                .then(() => { Notifications.success('Denied.'); showTab('review'); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not deny.')));
        }, { required: true, submitText: 'Deny' });
    }

    function cancel(id, after) {
        Modals.confirm('Cancel request', 'Withdraw this request? Nothing will start.', () => {
            call('POST', Config.API.approvals.cancel(id), {})
                .then(() => {
                    Notifications.success('Request cancelled.');
                    if (after) after(); else showTab('mine');
                })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not cancel.')));
        }, { confirmText: 'Cancel request', confirmClass: 'btn-danger' });
    }

    // ---------------------------------------------------------------- environment banner

    let bannerEnv = null;
    let watched = new Set();

    /** Shows the user's pending request for this environment, and follows it until it is decided. */
    function renderEnvBanner(env) {
        const $slot = $('#env-approval-banner');
        if (!$slot.length || !env) return;
        bannerEnv = env;
        const nav = ContentRouter.token();
        call('GET', Config.API.approvals.list('mine')).then(list => {
            if (!ContentRouter.isCurrent(nav) || !$('#env-approval-banner').length) return;
            const mine = (list || []).filter(r => r.environmentId === env.environmentId);
            const pending = mine.find(r => r.status === 'PENDING');
            const justDecided = mine.find(r => watched.has(r.requestId) && r.status !== 'PENDING');
            if (justDecided) {
                watched.delete(justDecided.requestId);
                const ok = justDecided.status === 'EXECUTED' || justDecided.status === 'APPROVED';
                Notifications.show(ok ? (justDecided.requestType === 'EXTEND' ? 'Extension approved.' : 'Approved: starting now.')
                    : `Your request was ${(STATUS[justDecided.status] || [justDecided.status])[0].toLowerCase()}.`
                        + (justDecided.statusReason ? ` ${justDecided.statusReason}` : ''), ok ? 'success' : 'warning', 6000);
            }
            if (!pending) {
                $slot.html('');
                if (typeof RealTime !== 'undefined' && RealTime.stopPolling) RealTime.stopPolling('approvalBanner');
                if (justDecided && typeof Environments !== 'undefined' && Environments.loadDetail) {
                    Environments.loadDetail({ environmentId: env.environmentId });
                }
                return;
            }
            watched.add(pending.requestId);
            const waitingFor = pending.approvers.filter(a => !a.decision).map(a => a.name);
            $slot.html(Utils.html`<div class="alert alert-info d-flex justify-content-between align-items-center gap-2 flex-wrap" role="status" id="approval-waiting">
                <span><i class="fas fa-hourglass-half me-1" aria-hidden="true"></i>${what(pending)} is waiting for approval by
                    ${waitingFor.join(', ') || 'the approvers'}.</span>
                <span class="d-flex gap-2"><a class="btn btn-sm btn-ghost" href="#/approvals?tab=mine">Details</a>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="approval-cancel" data-id="${pending.requestId}"
                        data-banner="1">Cancel</button></span></div>`);
            if (typeof RealTime !== 'undefined' && RealTime.startPolling) {
                RealTime.startPolling('approvalBanner', () => renderEnvBanner(bannerEnv), 15000, { pageScoped: true, immediate: false });
            }
        }).catch(() => $slot.html(''));
    }

    return { load, showTab, approve, deny, cancel, renderEnvBanner, refreshBanner: () => bannerEnv && renderEnvBanner(bannerEnv) };
})();

window.Approvals = Approvals;

Actions.registerAll({
    'approvals-tab': el => Approvals.showTab(el.dataset.tab),
    'approval-approve': el => Approvals.approve(el.dataset.id),
    'approval-deny': el => Approvals.deny(el.dataset.id),
    'approval-cancel': el => Approvals.cancel(el.dataset.id, el.dataset.banner ? Approvals.refreshBanner : null)
});
