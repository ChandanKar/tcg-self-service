/**
 * Waste › Fixes (E22-T04/T05, #/waste/fixes since U15-T07): the fixes waiting for approval (Approve for
 * admins other than the proposer) and recent ones with Rollback. U15-T08 rebuilds this page to its
 * mockup (WasteFix.dc.html).
 */
const WasteFixes = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const FIX_LABEL = { DELETE_VOLUME: 'Delete (snapshot first)', MIGRATE_GP3: 'Migrate to gp3', ARCHIVE_SNAPSHOT: 'Archive snapshot',
        RELEASE_IP: 'Release IP' };

    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        state = { actions: [], enabled: false };
        $('#content-area').html(`<div class="content-view waste-page" id="waste-fixes-view">
                ${PageHeader.markup({ title: 'Fixes', breadcrumb: [{ label: 'Waste', href: '#/waste' }, { label: 'Fixes' }],
                    summaryHtml: 'One administrator proposes a fix, a different one approves it, then the platform makes the change in AWS.' })}
                <div id="waste-actions"><p class="cost-note">Loading…</p></div>
            </div>`);
        refresh();
    }

    function refresh() {
        const token = ContentRouter.token();
        Promise.allSettled([call('GET', Config.API.waste.actionsConfig), call('GET', Config.API.waste.actions)]).then(([config, actions]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.enabled = config.status === 'fulfilled' && config.value && config.value.enabled;
            state.actions = actions.status === 'fulfilled' ? (actions.value || []) : [];
            if (actions.status !== 'fulfilled') {
                $('#waste-actions').html(`<div class="cost-panel-error" role="alert"><span>Couldn't load the fixes.</span>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="waste-fixes-refresh">Retry</button></div>`);
                return;
            }
            draw();
        });
    }

    function draw() {
        const actions = state.actions;
        const me = Auth.getUserId ? Auth.getUserId() : null;
        const pending = actions.filter(a => a.status === 'PROPOSED');
        const history = actions.filter(a => a.status !== 'PROPOSED').slice(0, 10);
        const pendingRows = pending.length ? pending.map(a => Utils.html`
            <li class="d-flex justify-content-between align-items-center flex-wrap gap-2 waste-action-row" data-action-id="${a.actionId}">
                <span><strong>${FIX_LABEL[a.actionType] || a.actionType}</strong> · ${a.resourceId} (${a.region})</span>
                <span class="d-flex gap-1">
                    ${Utils.raw(a.proposedBy === me ? '<span class="small text-muted">Waiting for another admin</span>'
                        : Utils.html`<button type="button" class="btn btn-sm btn-primary" data-action="waste-approve" data-id="${a.actionId}">Approve</button>`)}
                    <button type="button" class="btn btn-sm btn-ghost" data-action="waste-reject" data-id="${a.actionId}">Reject</button>
                </span></li>`).join('') : '<li class="text-muted small">Nothing waiting for approval.</li>';
        const historyRows = history.map(a => Utils.html`
            <li class="d-flex justify-content-between align-items-center flex-wrap gap-2 small">
                <span>${FIX_LABEL[a.actionType] || a.actionType} · ${a.resourceId} — <strong>${a.status.toLowerCase().replace('_', ' ')}</strong>${a.errorMessage ? ': ' + a.errorMessage : ''}</span>
                ${Utils.raw(a.status === 'EXECUTED' && a.rollbackSupported
                    ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="waste-rollback" data-id="${a.actionId}">Rollback</button>`
                    : a.status === 'EXECUTED' ? '<span class="text-muted">not reversible</span>' : '')}
            </li>`).join('');
        $('#waste-actions').html(`<section class="cost-card waste-actions-card" aria-labelledby="waste-actions-title">
            <h2 class="h6" id="waste-actions-title">Waiting for approval${state.enabled ? '' : ' <span class="badge text-bg-light border ms-1">fixes are turned off</span>'}</h2>
            <ul class="list-unstyled mb-2">${pendingRows}</ul>
            ${history.length ? `<details><summary class="small">Recent fixes</summary><ul class="list-unstyled mt-2 mb-0">${historyRows}</ul></details>` : ''}
        </section>`);
    }

    function approve(actionId) {
        const a = state.actions.find(x => x.actionId === actionId);
        if (!a) return;
        const send = () => call('POST', Config.API.waste.approve(actionId))
            .then(() => { Notifications.show('Approved; the fix runs in the background.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not approve the fix.')));
        if (a.actionType === 'RELEASE_IP' || a.actionType === 'DELETE_VOLUME') {
            let details = {};
            try { details = JSON.parse(a.findingDetails || '{}'); } catch (e) { details = {}; }
            const confirmText = a.actionType === 'RELEASE_IP' ? (details.publicIp || a.resourceId) : a.resourceId;
            DestructiveConfirm.show({
                title: a.actionType === 'RELEASE_IP' ? 'Release Elastic IP' : 'Delete unattached volume',
                message: a.actionType === 'RELEASE_IP'
                    ? `This releases ${confirmText}. AWS will not give the same address back: this cannot be undone.`
                    : `This snapshots ${a.resourceId}, waits for the snapshot, then deletes the volume. Rollback recreates it from the snapshot.`,
                impact: a.actionType === 'RELEASE_IP' ? ['The public IP is lost for good', 'Anything using it by address stops reaching it']
                    : ['The volume is deleted after its snapshot completes', 'The snapshot is kept (and billed) for rollback'],
                confirmText,
                actionText: 'Approve',
                onConfirm: send
            });
        } else {
            const note = a.actionType === 'MIGRATE_GP3' ? ' AWS allows one change per volume every 6 hours, so a rollback may have to wait.'
                : ' The archive tier bills at least 90 days.';
            Modals.confirm('Approve fix', `${FIX_LABEL[a.actionType]} for ${a.resourceId}?${note}`, send, { confirmText: 'Approve' });
        }
    }

    function reject(actionId) {
        call('POST', Config.API.waste.reject(actionId))
            .then(() => { Notifications.show('Fix rejected.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not reject the fix.')));
    }

    function rollback(actionId) {
        Modals.confirm('Roll back fix', 'Undo this fix in AWS?', () => {
            call('POST', Config.API.waste.rollback(actionId))
                .then(() => { Notifications.show('Rolled back.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not roll back the fix.')));
        }, { confirmText: 'Roll back' });
    }

    return { load, refresh, approve, reject, rollback };
})();

if (typeof window !== 'undefined') window.WasteFixes = WasteFixes;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'waste-fixes-refresh': () => WasteFixes.refresh(),
    'waste-approve': el => WasteFixes.approve(el.dataset.id),
    'waste-reject': el => WasteFixes.reject(el.dataset.id),
    'waste-rollback': el => WasteFixes.rollback(el.dataset.id)
});
