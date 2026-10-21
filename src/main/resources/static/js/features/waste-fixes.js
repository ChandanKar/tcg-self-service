/**
 * Waste › Fixes (E22-T04/T05, U15-T08 WasteFix.dc.html): how a fix moves (proposed by one admin,
 * approved by a different one, applied in AWS, rolled back if needed), the fixes with who proposed
 * them, what they save and their status, with Approve / Reject / Roll back / Details; and the
 * "Propose a fix" dialog the Waste rows open, with the fix and a reason.
 */
const WasteFixes = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

    /** Each fix's name in the list. */
    const FIX_NAME = { DELETE_VOLUME: 'Delete volume, snapshot first', MIGRATE_GP3: 'Change to gp3', ARCHIVE_SNAPSHOT: 'Archive snapshot',
        RELEASE_IP: 'Release IP address' };

    /** The fix a finding type can get, as the propose dialog offers it. */
    const FIX_CHOICE = {
        UNATTACHED_VOLUME: { type: 'DELETE_VOLUME', title: 'Delete the volume', note: 'A snapshot is taken first, so it can be restored' },
        GP2_VOLUME: { type: 'MIGRATE_GP3', title: 'Change to gp3', note: 'Same size, about 20% cheaper' },
        OLD_SNAPSHOT: { type: 'ARCHIVE_SNAPSHOT', title: 'Move to the archive tier',
            note: 'Much cheaper to keep; restoring takes hours, and it is billed for at least 90 days' },
        UNASSOCIATED_IP: { type: 'RELEASE_IP', title: 'Release the IP', note: "AWS won't give the same address back" }
    };

    /** Status tag text and tone. */
    const STATUS = {
        PROPOSED: ['Waiting for approval', 'wait'], APPROVED: ['Being applied', 'busy'], EXECUTING: ['Being applied', 'busy'],
        EXECUTED: ['Done', 'done'], FAILED: ['Failed', 'failed'], ROLLED_BACK: ['Rolled back', 'plain'], REJECTED: ['Rejected', 'plain']
    };

    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function money0(v) {
        return v === null || v === undefined ? '—' : '$' + Math.round(Number(v)).toLocaleString('en-US');
    }

    // ---------------------------------------------------------------- model (node-tested)

    /** "today 09:20", "Fri" within the week, else "9 Oct". */
    function whenText(ts, now) {
        if (!ts) return '';
        const d = new Date(ts);
        const today = now || new Date();
        const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
        if (d.getTime() >= startOfToday) {
            return `today ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        }
        if (startOfToday - d.getTime() < 6 * 86_400_000) return DAYS[d.getDay()];
        return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
    }

    /** What a row offers: buttons (approve, reject, rollback, details) or a note. */
    function actionsFor(a, me, enabled) {
        if (a.status === 'PROPOSED') {
            if (!enabled) return { buttons: [], note: 'Fixes are switched off' };
            return a.proposedBy === me ? { buttons: [], note: 'Another admin approves' } : { buttons: ['approve', 'reject'], note: '' };
        }
        if (a.status === 'EXECUTED') return a.rollbackSupported && enabled ? { buttons: ['rollback'], note: '' }
            : { buttons: [], note: a.rollbackSupported ? '' : "Can't be undone" };
        if (a.status === 'FAILED') return { buttons: ['details'], note: '' };
        if (a.status === 'ROLLED_BACK') return { buttons: [], note: a.rolledBackAt ? `on ${whenText(a.rolledBackAt)}` : '' };
        return { buttons: [], note: '' };
    }

    // ---------------------------------------------------------------- page

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        state = { actions: [], enabled: false };
        const steps = [['Proposed', 'by one admin', 'wait'], ['Approved', 'by a different admin', 'busy'], ['Applied', 'in AWS', 'done'],
            ['Roll back', 'if needed; releasing an IP is final', 'plain']];
        $('#content-area').html(`<div class="content-view waste-page" id="waste-fixes-view">
                ${PageHeader.markup({ title: 'Fixes', breadcrumb: [{ label: 'Waste', href: '#/waste' }, { label: 'Fixes' }],
                    summaryHtml: 'One administrator proposes a fix, a different one approves it, then the platform makes the change in AWS.' })}
                <ol class="fix-steps" aria-label="How a fix moves">${steps.map(([name, desc, tone]) =>
                    `<li><span class="fix-dot fix-${tone}"></span><b>${name}</b><span>${desc}</span></li>`).join('')}</ol>
                <div id="waste-fixes-off"></div>
                <div id="waste-actions"><p class="cost-note">Loading…</p></div>
            </div>`);
        refresh();
    }

    function refresh() {
        const token = ContentRouter.token();
        Promise.allSettled([call('GET', Config.API.waste.actionsConfig), call('GET', Config.API.waste.actions)]).then(([config, actions]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.enabled = config.status === 'fulfilled' && config.value && config.value.enabled;
            if (actions.status !== 'fulfilled') {
                $('#waste-actions').html(UiStates.failed({ what: 'the fixes', xhr: actions.reason, retryAction: 'waste-fixes-refresh', className: 'cost-panel-error' }));
                return;
            }
            state.actions = actions.value || [];
            draw();
        });
    }

    function whatText(a) {
        const kind = typeof Waste !== 'undefined' && Waste.model
            ? Waste.model.kindText({ findingType: a.findingType, details: a.findingDetails }) : '';
        return [a.resourceId, kind, a.region].filter(Boolean).join(' · ');
    }

    function draw() {
        $('#waste-fixes-off').html(state.enabled ? '' : `<p class="cost-note waste-off" role="status">Fixes are switched off
            (waste.actions.enabled), so nothing changes in AWS. Earlier fixes stay listed below.</p>`);
        const me = Auth.getUserId ? Auth.getUserId() : null;
        const button = (kind, a) => ({
            approve: Utils.html`<button type="button" class="btn btn-primary btn-sm" data-action="waste-approve" data-id="${a.actionId}">Approve</button>`,
            reject: Utils.html`<button type="button" class="btn btn-link btn-sm fix-quiet" data-action="waste-reject" data-id="${a.actionId}">Reject</button>`,
            rollback: Utils.html`<button type="button" class="btn btn-outline-danger btn-sm" data-action="waste-rollback" data-id="${a.actionId}">Roll back</button>`,
            details: Utils.html`<button type="button" class="btn btn-link btn-sm" data-action="waste-fix-details" data-id="${a.actionId}">Details</button>`
        })[kind];
        const rows = state.actions.length ? state.actions.map(a => {
            const [label, tone] = STATUS[a.status] || [a.status, 'plain'];
            const offer = actionsFor(a, me, state.enabled);
            return Utils.html`<tr data-action-id="${a.actionId}">
                <td><b class="fix-name">${FIX_NAME[a.actionType] || a.actionType}</b><span class="rs-sub">${whatText(a)}</span>
                    ${Utils.raw(a.reason ? Utils.html`<span class="rs-sub fix-reason">“${a.reason}”</span>` : '')}</td>
                <td>${a.proposedBy === me ? 'You' : (a.proposedByName || a.proposedBy)}<span class="rs-sub">${whenText(a.proposedAt)}</span></td>
                <td class="cost-num cost-mono rs-saves">${money0(a.monthlySaving)}</td>
                <td><span class="fix-tag fix-${tone}">${label}</span></td>
                <td class="fix-actions">${Utils.raw(offer.buttons.map(b => button(b, a)).join(''))}${Utils.raw(offer.note
                    ? Utils.html`<span class="fix-note">${offer.note}</span>` : '')}</td>
            </tr>`;
        }).join('') : '<tr><td colspan="5" class="text-muted text-center py-4">No fixes yet. Propose one from a row on the Waste page.</td></tr>';
        $('#waste-actions').html(`<section class="cost-card rs-table-card waste-actions-card"><div class="cost-scroll">
            <table class="cost-table fix-table" id="waste-fixes-table">
                <thead><tr><th scope="col">Fix</th><th scope="col">Proposed by</th><th scope="col" class="cost-num">Saves a month</th>
                    <th scope="col">Status</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div></section>`);
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
            Modals.confirm('Approve fix', `${FIX_NAME[a.actionType]}: ${a.resourceId}?${note}`, send, { confirmText: 'Approve' });
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

    function details(actionId) {
        const a = state.actions.find(x => x.actionId === actionId);
        if (!a) return;
        Modals.show({ id: 'wasteFixDetailsModal', title: `${FIX_NAME[a.actionType] || a.actionType} failed`,
            body: Utils.html`<p class="mb-1">${whatText(a)}</p><p class="mb-0 fix-error">${a.errorMessage || 'AWS gave no reason.'}</p>` });
    }

    // ---------------------------------------------------------------- propose (from a Waste row)

    /**
     * "Propose a fix": the finding in one line, the fix it can get, a reason, and "Another admin approves
     * it". onDone runs after the fix is proposed.
     */
    function openPropose(f, summaryLine, onDone) {
        const choice = FIX_CHOICE[f.findingType];
        if (!choice) return;
        const body = Utils.html`
            <p class="cost-note mb-2" id="propose-summary">${summaryLine}</p>
            <fieldset class="propose-fixes"><legend class="visually-hidden">Fix</legend>
                <label class="propose-fix"><input type="radio" class="form-check-input" name="propose-fix" value="${choice.type}" checked>
                    <span><b>${choice.title}</b><span class="rs-sub">${choice.note}</span></span></label>
            </fieldset>
            <label class="propose-reason" for="propose-reason">Reason
                <input type="text" class="form-control" id="propose-reason" maxlength="500" placeholder="e.g. Left over from the 3.9 load test"></label>
            <p class="cost-note mt-2 mb-0">Another admin approves it before anything changes in AWS.</p>
            <p class="propose-error mt-2 mb-0" id="propose-error" role="alert" hidden></p>`;
        Modals.show({ id: 'wasteProposeModal', title: 'Propose a fix', body,
            buttons: [{ text: 'Cancel', class: 'btn-ghost', dismiss: true }, { text: 'Propose fix', class: 'btn-primary', id: 'propose-submit' }] });
        $('#propose-submit').off('click').on('click', () => {
            const reason = String($('#propose-reason').val() || '').trim();
            $('#propose-submit').prop('disabled', true);
            call('POST', Config.API.waste.propose(f.findingId), { type: choice.type, reason: reason || null })
                .then(() => {
                    Modals.hide('wasteProposeModal');
                    Notifications.show('Fix proposed; another admin approves it.', 'success');
                    if (onDone) onDone();
                })
                .catch(e => {
                    $('#propose-submit').prop('disabled', false);
                    $('#propose-error').text(serverMessage(e, 'Could not propose the fix.')).prop('hidden', false);
                });
        });
    }

    return { load, refresh, approve, reject, rollback, details, openPropose, model: { whenText, actionsFor } };
})();

if (typeof window !== 'undefined') window.WasteFixes = WasteFixes;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'waste-fixes-refresh': () => WasteFixes.refresh(),
    'waste-approve': el => WasteFixes.approve(el.dataset.id),
    'waste-reject': el => WasteFixes.reject(el.dataset.id),
    'waste-rollback': el => WasteFixes.rollback(el.dataset.id),
    'waste-fix-details': el => WasteFixes.details(el.dataset.id)
});
