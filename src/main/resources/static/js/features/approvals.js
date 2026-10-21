/**
 * Operation approvals (E25-T06). #/approvals (U09-T03, Approvals.dc.html): "To review · 2" shows
 * each waiting start or extension as a decision card (what, who asked and when, when it expires,
 * the requester's own note, why it needs approval, the cost and who decides) with Deny… and
 * Approve, then the decided ones lighter, 10 to a page; "My requests" lists your own as status
 * lines with Cancel request. On the environment page a banner shows a pending start or extension
 * of the user's own, who it waits for, and updates itself until it is decided. The words are pure
 * (Approvals.model), tested in src/test/js/approvals.test.mjs.
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
    /** Why each list failed to load (the jqXHR), for the couldn't-load panel. */
    const failures = {};

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

    const PAGE_SIZE = 10;

    /** "Start Payments Dev for 24 hours", "Keep CRM UAT running 12 hours longer". */
    function titleText(r) {
        const h = Number(r.requestedHours) || 0;
        const hours = h ? ` ${h} hour${h === 1 ? '' : 's'}` : '';
        return r.requestType === 'EXTEND'
            ? `Keep ${r.environmentName} running${hours ? hours + ' longer' : ' longer'}`
            : `Start ${r.environmentName}${hours ? ' for' + hours : ''}`;
    }

    function what(r) {
        return titleText(r);
    }

    /** "A", "A and B", "A, B and C" (or "A or B" with word 'or'). */
    function joinNames(names, word = 'and') {
        if (names.length <= 1) return names.join('');
        return `${names.slice(0, -1).join(', ')} ${word} ${names[names.length - 1]}`;
    }

    /**
     * Who decides: "Any one of you or Meera Iyer", "You and Meera Iyer (both)"; others' names
     * without "you" when the viewer is not an approver.
     */
    function decidedByText(r, viewerId) {
        const approvers = r.approvers || [];
        const me = approvers.some(a => a.userId === viewerId);
        const others = approvers.filter(a => a.userId !== viewerId).map(a => a.name);
        const all = r.approverMode === 'ALL';
        if (all) {
            const names = me ? ['You'].concat(others) : others;
            return `${joinNames(names)}${names.length === 2 ? ' (both)' : names.length > 2 ? ' (all)' : ''}`;
        }
        if (me) return others.length ? `Any one of you or ${joinNames(others, 'or')}` : 'You';
        return others.length > 1 ? `Any one of ${joinNames(others, 'or')}` : (others[0] || '—');
    }

    /** "Expires in 3 h 20 min" (urgent under an hour); null once decided or without a date. */
    function expiresText(r, now = new Date()) {
        if (r.status !== 'PENDING' || !r.expiresAt) return null;
        const minutes = Math.round((new Date(r.expiresAt).getTime() - now.getTime()) / 60000);
        if (minutes <= 0) return { text: 'Expires now', urgent: true };
        const h = Math.floor(minutes / 60), m = minutes % 60;
        return { text: `Expires in ${h ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`}`, urgent: minutes < 60 };
    }

    function ago(ts, now = new Date()) {
        const minutes = Math.max(1, Math.round((now.getTime() - new Date(ts).getTime()) / 60000));
        if (minutes < 60) return `${minutes} min`;
        const hours = Math.round(minutes / 60);
        return hours < 48 ? `${hours} h` : `${Math.round(hours / 24)} days`;
    }

    function clockDay(ts, now = new Date()) {
        const d = new Date(ts);
        if (Number.isNaN(d.getTime())) return '';
        const pad = n => String(n).padStart(2, '0');
        const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
        return now.getTime() - d.getTime() < 6 * 86400000 ? `${days[d.getDay()]} ${pad(d.getHours())}:${pad(d.getMinutes())}`
            : `${d.getDate()} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()]}`;
    }

    /** My request as a status line: {chip, tone, text, cancel}. */
    function mineLine(r, now = new Date()) {
        const approvedBy = (r.approvers || []).filter(a => a.decision === 'APPROVED').map(a => a.name);
        const deniedBy = (r.approvers || []).find(a => a.decision === 'DENIED');
        switch (r.status) {
            case 'PENDING': {
                const waiting = (r.approvers || []).filter(a => !a.decision).map(a => a.name);
                return { chip: 'Waiting', tone: 'wait', text: `waiting for ${joinNames(waiting) || 'the approvers'}`, cancel: !!r.canCancel };
            }
            case 'EXECUTED':
            case 'APPROVED':
                return { chip: r.requestType === 'EXTEND' ? 'Extended' : 'Started', tone: 'ok',
                    text: `approved by ${joinNames(approvedBy) || 'the approvers'}${r.decidedAt ? `, ${r.requestType === 'EXTEND' ? 'extended' : 'started'} ${clockDay(r.decidedAt, now)}` : ''}` };
            case 'DENIED':
                return { chip: 'Denied', tone: 'no',
                    text: `denied${deniedBy ? ` by ${deniedBy.name}` : ''}${deniedBy && deniedBy.reason ? `: "${deniedBy.reason}"` : ''}` };
            case 'EXPIRED':
                return { chip: 'Expired', tone: 'off', text: 'nobody decided in time' };
            case 'CANCELLED':
                return { chip: 'Cancelled', tone: 'off', text: 'you cancelled it' };
            default:
                return { chip: 'Failed', tone: 'no', text: r.statusReason || 'it could not start' };
        }
    }

    /** Shows the sidebar item only while Approvals concerns the user (on, or requests to see). */
    function probeNav() {
        call('GET', Config.API.approvals.status)
            .then(r => {
                $('#approvals-link').toggle(!!(r && r.visible));
                // U16-T01: the requests waiting for this user's decision, as the menu's badge.
                const waiting = Number((r && r.waiting) || 0);
                $('.approvals-count').text(waiting).toggle(waiting > 0);
            })
            .catch(() => $('#approvals-link').hide());
    }

    // ---------------------------------------------------------------- page

    function load() {
        pageToken = ContentRouter.token();
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = { tab: q.tab === 'mine' ? 'mine' : 'review', review: null, mine: null, page: { review: 0, mine: 0 } };
        $('#content-area').html(`
            <div class="content-view approvals-page" id="approvals-view">
                <div class="content-header">
                    <h1>Approvals ${typeof HelpLink !== 'undefined' ? HelpLink.button('approvals', 'Approvals') : ''}</h1>
                    <p class="text-muted mb-0">Starts and extensions that need a yes because of how long they run or what they cost.</p>
                </div>
                <div id="approvals-tabs-wrap"></div>
                <div id="approvals-body"></div>
            </div>`);
        const token = pageToken;
        Promise.all([call('GET', Config.API.approvals.list('to-review')).catch(x => { failures.review = x; return null; }),
            call('GET', Config.API.approvals.list('mine')).catch(x => { failures.mine = x; return null; })]).then(([review, mine]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.review = review;
            state.mine = mine;
            draw();
        });
    }

    /** Reload both lists (after a decision) and keep the tab. */
    function showTab(tab) {
        if (!state) return;
        state.tab = tab === 'mine' ? 'mine' : 'review';
        const token = pageToken;
        Promise.all([call('GET', Config.API.approvals.list('to-review')).catch(x => { failures.review = x; return null; }),
            call('GET', Config.API.approvals.list('mine')).catch(x => { failures.mine = x; return null; })]).then(([review, mine]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.review = review;
            state.mine = mine;
            draw();
        });
    }

    function viewerId() {
        return typeof Auth !== 'undefined' && Auth.getUserId ? Auth.getUserId() : null;
    }

    function card(r) {
        const pending = r.status === 'PENDING';
        const expiry = expiresText(r);
        const chip = expiry ? Utils.html`<span class="approval-chip" data-tone="${expiry.urgent ? 'urgent' : 'wait'}">${expiry.text}</span>`
            : Utils.html`<span class="approval-chip" data-tone="${['EXECUTED', 'APPROVED'].includes(r.status) ? 'ok' : 'off'}">${(STATUS[r.status] || [r.status])[0]}</span>`;
        const actions = pending && r.canDecide ? Utils.html`<div class="approval-actions">
                <button type="button" class="btn btn-outline-secondary" data-action="approval-deny" data-id="${r.requestId}">Deny…</button>
                <button type="button" class="btn btn-primary" data-action="approval-approve" data-id="${r.requestId}">Approve</button></div>` : '';
        const decided = !pending ? Utils.html`<p class="approval-outcome">${r.statusReason || mineLine(r).text}</p>` : '';
        return Utils.html`<article class="approval-card${pending ? '' : ' is-decided'}" data-request="${r.requestId}" aria-labelledby="approval-title-${r.requestId}">
            <div class="approval-head">
                <div><h2 id="approval-title-${r.requestId}">${titleText(r)}</h2>
                    <p class="approval-who">${r.requesterName} · asked ${ago(r.createdAt)} ago</p></div>
                ${Utils.raw(chip)}
            </div>
            ${Utils.raw(r.requesterNote ? Utils.html`<p class="approval-note">"${r.requesterNote}"</p>` : '')}
            <div class="approval-facts">
                <div><div class="approval-label">Why it needs approval</div><div>${(r.reasons || []).join('; ') || '—'}</div></div>
                <div><div class="approval-label">Estimated cost</div><div class="mono">${money(r.estimatedCost)}</div></div>
                <div><div class="approval-label">Decided by</div><div>${decidedByText(r, viewerId())}</div></div>
            </div>
            ${Utils.raw(decided)}
            ${Utils.raw(actions)}
        </article>`;
    }

    function mineRow(r) {
        const l = mineLine(r);
        const verb = r.requestType === 'EXTEND' ? Utils.html`Keep <b>${r.environmentName}</b> running${r.requestedHours ? ` ${r.requestedHours} hours longer` : ' longer'}`
            : Utils.html`Start <b>${r.environmentName}</b>${r.requestedHours ? ` for ${r.requestedHours} hours` : ''}`;
        return Utils.html`<div class="approval-row" data-request="${r.requestId}"><span class="approval-chip" data-tone="${l.tone}">${l.chip}</span>
            <span class="approval-row-text">${Utils.raw(verb)} · ${l.text}</span>
            ${Utils.raw(l.cancel ? Utils.html`<button type="button" class="btn btn-link btn-sm p-0" data-action="approval-cancel" data-id="${r.requestId}">Cancel request</button>` : '')}</div>`;
    }

    function draw() {
        const review = state.review || [];
        const mine = state.mine || [];
        const waiting = review.filter(r => r.status === 'PENDING' && r.canDecide).length;
        const mineWaiting = mine.filter(r => r.status === 'PENDING').length;
        $('#approvals-tabs-wrap').html(Tabs.markup({ id: 'approvals-tabs', label: 'Approvals', active: state.tab, tabs: [
            { key: 'review', label: waiting ? `To review · ${waiting}` : 'To review' },
            { key: 'mine', label: mineWaiting ? `My requests · ${mineWaiting}` : 'My requests' }] }));
        Tabs.bind(document.getElementById('approvals-tabs'), key => { state.tab = key; drawBody(); });
        drawBody();
    }

    function drawBody() {
        const tab = state.tab;
        const list = tab === 'review'
            ? (state.review || []).filter(r => r.status === 'PENDING').concat((state.review || []).filter(r => r.status !== 'PENDING'))
            : (state.mine || []);
        const failed = tab === 'review' ? state.review === null : state.mine === null;
        const page = Math.min(state.page[tab], Math.max(0, Math.ceil(list.length / PAGE_SIZE) - 1));
        const shown = list.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
        let body;
        if (failed) {
            body = UiStates.failed({ what: tab === 'review' ? 'Approvals to review' : 'Your approval requests', xhr: failures[tab],
                retryAction: 'approvals-tab', retryData: { tab } });
        } else if (!list.length) {
            body = tab === 'review'
                ? UiStates.empty({ title: 'Nothing waits for your approval.', text: 'Starts and extensions that need your yes show up here.' })
                : UiStates.empty({ title: 'You have not asked for any approvals.', text: 'A start or an extension that needs a yes shows up here with its answer.' });
        } else if (tab === 'review') {
            body = `<div class="approval-cards">${shown.map(card).join('')}</div>`;
        } else {
            body = `<div class="approval-rows">${shown.map(mineRow).join('')}</div>`;
        }
        $('#approvals-body').attr({ role: 'tabpanel', 'aria-labelledby': `approvals-tabs-tab-${tab}` })
            .html(body + '<div class="pagination-bar-wrap" id="approvals-pager"></div>');
        if (list.length > PAGE_SIZE) {
            Pagination.renderNumbered('#approvals-pager', { page, totalItems: list.length, pageSize: PAGE_SIZE,
                itemLabel: tab === 'review' ? 'requests' : 'requests', zeroIndexed: true, bindAncestor: '#content-area',
                onPageChange: p => { state.page[tab] = p; drawBody(); } });
        }
    }

    /** Approve at once; the toast says what happens next. */
    function approve(id) {
        const r = (state && state.review || []).find(x => x.requestId === id);
        if (!r) return;
        call('POST', Config.API.approvals.approve(id), {})
            .then(updated => {
                if (updated && updated.status === 'PENDING') {
                    const others = (updated.approvers || []).filter(a => !a.decision && a.userId !== viewerId()).map(a => a.name);
                    Notifications.success(`Approved. Waiting for ${joinNames(others) || 'the other approvers'}.`);
                } else {
                    Notifications.success(r.requestType === 'EXTEND' ? `Approved. ${r.environmentName} keeps running longer.`
                        : `Approved. ${r.environmentName} is starting.`);
                }
                showTab('review');
            })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not approve.')));
    }

    /** Deny… asks why first: the requester sees the reason. */
    function deny(id) {
        Modals.prompt('Deny this request?', 'Why? The requester sees this.', reason => {
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
    /**
     * My request waiting for approval on this environment, as the navy Approval alert (U07-T07):
     * "Your start is waiting for Sanjay Rao. Asked 10 min ago." with Cancel request. Polled every
     * 15 s while one waits; a decision shows a toast and reloads the page.
     */
    function renderEnvBanner(env) {
        if (!$('#env-alerts').length || !env || typeof EnvAlerts === 'undefined') return;
        bannerEnv = env;
        const nav = ContentRouter.token();
        call('GET', Config.API.approvals.list('mine')).then(list => {
            if (!ContentRouter.isCurrent(nav) || !$('#env-alerts').length) return;
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
                EnvAlerts.set('approval', null, env.environmentId);
                if (typeof RealTime !== 'undefined' && RealTime.stopPolling) RealTime.stopPolling('approvalBanner');
                if (justDecided && typeof Environments !== 'undefined' && Environments.loadDetail) {
                    Environments.loadDetail({ environmentId: env.environmentId });
                }
                return;
            }
            watched.add(pending.requestId);
            const waitingFor = pending.approvers.filter(a => !a.decision).map(a => a.name);
            const asked = pending.createdAt && Utils.formatRelativeTime ? ` Asked ${Utils.formatRelativeTime(pending.createdAt)}.` : '';
            EnvAlerts.set('approval', {
                tone: 'navy', id: 'approval-waiting',
                title: pending.requestType === 'EXTEND' ? 'Your extension is waiting' : 'Your start is waiting',
                detail: `for ${waitingFor.join(', ') || 'the approvers'}.${asked}`,
                action: { label: 'Cancel request', dataAction: 'approval-cancel', data: { id: pending.requestId, banner: '1' } },
            }, env.environmentId);
            if (typeof RealTime !== 'undefined' && RealTime.startPolling) {
                RealTime.startPolling('approvalBanner', () => renderEnvBanner(bannerEnv), 15000, { pageScoped: true, immediate: false });
            }
        }).catch(() => EnvAlerts.set('approval', null, env.environmentId));
    }

    return { load, probeNav, showTab, approve, deny, cancel, renderEnvBanner, refreshBanner: () => bannerEnv && renderEnvBanner(bannerEnv),
        model: { titleText, decidedByText, expiresText, mineLine, joinNames } };
})();

if (typeof window !== 'undefined') window.Approvals = Approvals;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'approvals-tab': el => Approvals.showTab(el.dataset.tab),
    'approval-approve': el => Approvals.approve(el.dataset.id),
    'approval-deny': el => Approvals.deny(el.dataset.id),
    'approval-cancel': el => Approvals.cancel(el.dataset.id, el.dataset.banner ? Approvals.refreshBanner : null)
});
