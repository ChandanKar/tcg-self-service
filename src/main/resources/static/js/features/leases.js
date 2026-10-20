/**
 * Environment leases on the environment page (E15-T06): when the environment stops, a live
 * countdown, Extend (with owner approval beyond the maximum), pending requests for owners and the
 * owner's lease settings. Buttons follow the permissions the server sends; nothing renders while
 * the feature is off (the environment's leaseEnabled is null). Since U07-T05 it is the "Lease"
 * fact in "About this environment" (#env-fact-lease) and the extend dialog uses chips.
 */
const Leases = (function() {
    'use strict';

    const EXTEND_OPTIONS = [30, 60, 120, 480, 720];
    const POLICY_OPTIONS = [15, 30, 60, 120, 240, 480, 720, 1440, 2880, 4320];
    const ENDING_SOON_MS = 15 * 60 * 1000;

    let current = null;      // { env, lease, policy, requests }
    let renderToken = 0;
    const policies = {};     // envId -> last policy seen (used by the start dialog)

    function getJson(url) {
        return new Promise(resolve => {
            ApiClient.get(url, { suppressGlobalError: true, quietServerErrors: true })
                .done((data, _status, xhr) => resolve(xhr && xhr.status === 204 ? null : data))
                .fail(() => resolve(undefined));
        });
    }

    function minutesLabel(minutes) {
        if (minutes < 60) return `${minutes} min`;
        const h = Math.floor(minutes / 60);
        const m = minutes % 60;
        return m ? `${h} h ${m} min` : `${h} h`;
    }

    function untilLabel(endsAtMs) {
        const left = endsAtMs - Date.now();
        if (left <= 0) return 'stopping now';
        return 'in ' + minutesLabel(Math.max(1, Math.round(left / 60000)));
    }

    /** "20:00": 24-hour like the rest of the app, whatever the browser's locale. */
    function clock(ts) {
        const d = new Date(ts);
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    }

    // ---------------------------------------------------------------- rendering

    /** Load the lease and policy and render them into the Lease fact (#env-fact-lease). */
    function render(env) {
        const $section = $('#env-fact-lease');
        if (!$section.length || !env || env.leaseEnabled === null || env.leaseEnabled === undefined) {
            $section.empty();
            return;
        }
        const token = ++renderToken;
        const envId = env.environmentId;
        Promise.all([getJson(Config.API.leases.get(envId)), getJson(Config.API.leases.policy(envId))])
            .then(([lease, policy]) => {
                if (token !== renderToken || lease === undefined && policy === undefined) return;
                if (policy) policies[envId] = policy;
                const wantRequests = lease && lease.permissions && lease.permissions.canManagePolicy && lease.pendingExtension;
                return (wantRequests ? getJson(Config.API.leases.extensionRequests(envId)) : Promise.resolve([]))
                    .then(requests => {
                        if (token !== renderToken) return;
                        current = { env, lease: lease || null, policy: policy || null, requests: requests || [] };
                        $section.html(buildFact(current));
                        endingAlert(current);
                        startTimers(envId);
                        openExtendFromRoute();
                    });
            });
    }

    /** "14:30", or "Mon 14:30" when not today. */
    function when(ms) {
        const time = clock(ms);
        return new Date(ms).toDateString() === new Date().toDateString() ? time
            : `${new Date(ms).toLocaleDateString([], { weekday: 'short' })} ${time}`;
    }

    /**
     * The "Lease" fact (EnvDetail.dc.html): "Stops at 20:00 · in 2 h 15 min" with Extend and
     * "Up to 6 h more without approval"; without a lease, how long a start runs; the owner's
     * settings and pending requests to approve.
     */
    function buildFact({ env, lease, policy, requests }) {
        const canEditPolicy = policy && policy.canEdit;
        const settings = canEditPolicy
            ? '<button type="button" class="btn btn-link env-fact-action" data-action="lease-settings">Settings</button>'
            : '';

        if (!lease) {
            if (!policy || (!policy.enabled && !canEditPolicy)) return '';
            const value = policy.enabled
                ? `Runs ${minutesLabel(policy.effectiveDefaultMinutes)} when started`
                : 'Off for this environment';
            const note = policy.enabled ? `Up to ${minutesLabel(policy.effectiveMaxMinutes)} without approval` : '';
            return Utils.html`<div class="env-fact lease-fact lease-idle">
                    <div class="env-fact-head"><span>Lease</span><span class="env-fact-actions">${Utils.raw(settings)}</span></div>
                    <div class="env-fact-value">${value}</div>
                    ${note ? Utils.raw(Utils.html`<div class="env-fact-note">${note}</div>`) : ''}
                </div>`;
        }

        const endsAt = new Date(lease.endsAt).getTime();
        const startedAt = new Date(lease.startedAt).getTime();
        const soon = endsAt - Date.now() < ENDING_SOON_MS;
        const who = (lease.participants || []).map(p => p.displayName || p.userId).join(', ');
        const pending = lease.pendingExtension;
        const extend = lease.permissions && lease.permissions.canExtend
            ? '<button type="button" class="btn btn-link env-fact-action" data-action="lease-extend">Extend</button>'
            : '';
        // How much longer without the owner's OK: the maximum minus the lease's length so far.
        let allowance = '';
        if (policy && policy.effectiveMaxMinutes && !Number.isNaN(startedAt)) {
            const more = Math.floor(policy.effectiveMaxMinutes - (endsAt - startedAt) / 60000);
            allowance = more >= 15 ? `Up to ${minutesLabel(more)} more without approval` : 'Longer needs the owner\'s approval';
        }
        const notes = [];
        if (pending) notes.push(Utils.html`<span class="lease-pill" title="${pending.reason || ''}">Waiting for approval: +${minutesLabel(pending.minutes)}</span>`);
        else if (allowance) notes.push(Utils.escapeHtml(allowance));
        if (String(env && env.serviceType || '').toUpperCase() === 'EKS') {
            notes.push('Node groups scale to 0 in reverse order');
        }
        if (who) notes.push(Utils.html`Kept running by ${who}`);
        const requestRows = (requests || []).map(r => Utils.html`
            <li class="lease-request">
                <span><strong>${r.requesterName || r.requesterUserId}</strong> asks for +${minutesLabel(r.minutes)}${r.reason ? ': ' + r.reason : ''}</span>
                <span class="lease-request-actions">
                    <button type="button" class="btn btn-sm btn-outline-success" data-action="lease-approve" data-request-id="${r.requestId}">Approve</button>
                    <button type="button" class="btn btn-sm btn-danger" data-action="lease-deny" data-request-id="${r.requestId}">Deny</button>
                </span>
            </li>`).join('');

        return Utils.html`<div class="env-fact lease-fact ${soon ? 'lease-ending-soon' : ''}">
                <div class="env-fact-head"><span>Lease</span><span class="env-fact-actions">${Utils.raw(extend)}${Utils.raw(settings)}</span></div>
                <div class="env-fact-value">Stops at <span class="mono">${when(endsAt)}</span> · <span class="lease-countdown" data-ends-at="${endsAt}">${untilLabel(endsAt)}</span></div>
                ${notes.length ? Utils.raw(`<div class="env-fact-note">${notes.join(' · ')}</div>`) : ''}
                ${requestRows ? Utils.raw(`<ul class="lease-requests" aria-label="Extension requests">${requestRows}</ul>`) : ''}
            </div>`;
    }

    /** The gold Lease alert when the lease ends within the hour (U07-T07): "Stops in 12 minutes." */
    function endingAlert({ env, lease }) {
        if (typeof EnvAlerts === 'undefined') return;
        const endsAt = lease ? new Date(lease.endsAt).getTime() : NaN;
        const left = Math.round((endsAt - Date.now()) / 60000);
        if (Number.isNaN(endsAt) || left > 60) {
            EnvAlerts.set('lease', null, env.environmentId);
            return;
        }
        const inWords = left <= 0 ? 'Stopping now.' : `Stops in ${left} minute${left === 1 ? '' : 's'}.`;
        EnvAlerts.set('lease', {
            tone: 'gold', title: inWords,
            detail: `The lease ends at ${clock(endsAt)}. You're reminded 60, 15 and 5 minutes before.`,
            action: lease.permissions && lease.permissions.canExtend ? { label: 'Extend', dataAction: 'lease-extend' } : null,
        }, env.environmentId);
    }

    function updateCountdown() {
        document.querySelectorAll('#env-fact-lease .lease-countdown').forEach(el => {
            const endsAt = Number(el.dataset.endsAt);
            el.textContent = untilLabel(endsAt);
            const banner = el.closest('.lease-fact');
            if (banner) banner.classList.toggle('lease-ending-soon', endsAt - Date.now() < ENDING_SOON_MS);
        });
    }

    function startTimers(envId) {
        if (typeof RealTime === 'undefined') return;
        RealTime.startPolling('leaseCountdown', updateCountdown, 30000, { pageScoped: true, immediate: false });
        RealTime.startPolling('lease', () => {
            if (current && current.env.environmentId === envId) render(current.env);
        }, 60000, { pageScoped: true, immediate: false });
    }

    /** The bell's lease warning links here with extendLease=1: open Extend once. */
    function openExtendFromRoute() {
        if (typeof ContentRouter === 'undefined' || !ContentRouter.query) return;
        const q = ContentRouter.query();
        if (q.extendLease === '1' && current && current.lease && current.lease.permissions.canExtend) {
            showExtendDialog(current.env.environmentId, current.lease, current.policy, () => render(current.env));
            if (window.history && window.history.replaceState) {
                window.history.replaceState(null, '', window.location.hash.replace(/[?&]extendLease=1/, ''));
            }
        }
    }

    // ---------------------------------------------------------------- dialogs

    /**
     * Extend dialog (LockLease.dc.html), reusable from My Account (E15-T07): "Extend by" chips
     * (30 min to 12 h, within the platform's limit), the new stop time, what it costs, and a reason
     * only when the new length needs the owner's approval.
     */
    function showExtendDialog(envId, lease, policy, onDone) {
        const limit = policy && (policy.platformAbsoluteMaxMinutes || policy.platformMaxMinutes);
        const values = EXTEND_OPTIONS.filter(m => !limit || m <= limit);
        const endsAt = new Date(lease.endsAt).getTime();
        const chips = ChoiceChips.markup({ id: 'lease-extend-minutes', legend: 'Extend by', selected: values.includes(120) ? 120 : values[0],
            options: values.map(m => ({ value: m, label: minutesLabel(m) })) });
        Modals.show({
            id: 'leaseExtendModal',
            title: 'Keep it running longer',
            body: `
                <p class="dialog-lead">It stops at <span class="mono">${Utils.escapeHtml(when(endsAt))}</span>, ${Utils.escapeHtml(untilLabel(endsAt))}.
                    You're reminded 60, 15 and 5 minutes before.</p>
                ${chips}
                <div class="lease-extend-facts">
                    <div><div class="lease-extend-label">New stop time</div><div class="mono" id="lease-extend-new-stop"></div></div>
                    <div><div class="lease-extend-label">Costs about</div><div id="lease-extend-cost" aria-live="polite">—</div></div>
                </div>
                <p class="small text-muted mb-2" id="lease-extend-preview" aria-live="polite"></p>
                <div id="lease-extend-reason-wrap" hidden>
                    <label class="form-label" for="lease-extend-reason">Reason for the owner</label>
                    <input type="text" class="form-control" id="lease-extend-reason" maxlength="500"
                           placeholder="Longer than the maximum needs the owner's OK">
                </div>
                <div class="text-danger small mt-2" id="lease-extend-error" role="alert"></div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Extend', class: 'btn-primary', id: 'lease-extend-submit' }
            ],
            onShow: function() {
                const minutes = () => Number(ChoiceChips.value('lease-extend-minutes'));
                const update = () => {
                    const m = minutes();
                    const base = Math.max(Date.now(), endsAt);
                    const newEnd = base + m * 60000;
                    const total = (newEnd - new Date(lease.startedAt).getTime()) / 60000;
                    const needsApproval = !!(policy && total > policy.effectiveMaxMinutes);
                    const tomorrow = new Date(newEnd).toDateString() !== new Date().toDateString();
                    $('#lease-extend-new-stop').text(clock(newEnd) + (tomorrow ? ' tomorrow' : ''));
                    $('#lease-extend-preview').text(needsApproval
                        ? 'Needs owner approval: longer than the maximum.' : '');
                    $('#lease-extend-reason-wrap').prop('hidden', !needsApproval);
                    $('#lease-extend-submit').text(needsApproval ? 'Ask the owner' : `Extend by ${minutesLabel(m)}`);
                    return needsApproval;
                };
                // The extra cost of keeping the running VMs up that long (E25-T02).
                const updateCost = () => {
                    if (typeof CostPreview === 'undefined') return;
                    const m = minutes();
                    CostPreview.render($('#lease-extend-cost'), envId, null, Math.ceil(m / 60), true,
                        () => $('#lease-extend-cost').length > 0 && minutes() === m);
                };
                $('#lease-extend-minutes').on('choice-change', () => { update(); updateCost(); });
                update();
                updateCost();
                $('#lease-extend-submit').on('click', function() {
                    const needsApproval = update();
                    const reason = ($('#lease-extend-reason').val() || '').trim();
                    if (needsApproval && reason.length < 5) {
                        $('#lease-extend-error').text('Tell the owner why (at least 5 characters).');
                        return;
                    }
                    const $btn = $(this).prop('disabled', true);
                    ApiClient.post(Config.API.leases.extend(envId),
                        { minutes: minutes(), reason: reason || null },
                        { suppressGlobalError: true })
                        .done((data, _s, xhr) => {
                            Modals.hide('leaseExtendModal');
                            if (data && data.approvalRequestId) {
                                // Held by an approval policy (E25-T05).
                                const who = (data.approvers || []).join(', ');
                                Notifications.show(`Sent for approval${who ? ' to ' + who : ''}. The lease is unchanged until then.`, 'info', 8000);
                            } else {
                                Notifications.show(xhr && xhr.status === 202
                                    ? 'Sent to the owner for approval.' : 'Extended.', xhr && xhr.status === 202 ? 'info' : 'success');
                            }
                            if (onDone) onDone();
                        })
                        .fail(xhr => {
                            $btn.prop('disabled', false);
                            $('#lease-extend-error').text((xhr.responseJSON && xhr.responseJSON.message) || 'Could not extend.');
                        });
                });
            }
        });
    }

    function showPolicyDialog(envId, policy, onDone) {
        const max = policy.platformMaxMinutes;
        const opts = (selected, fallback) => POLICY_OPTIONS.filter(m => m <= max)
            .map(m => `<option value="${m}" ${m === (selected || fallback) ? 'selected' : ''}>${minutesLabel(m)}</option>`).join('');
        Modals.show({
            id: 'leasePolicyModal',
            title: 'Lease settings',
            body: `
                <div class="form-check form-switch mb-3">
                    <input class="form-check-input" type="checkbox" role="switch" id="lease-policy-enabled" ${policy.enabled ? 'checked' : ''}>
                    <label class="form-check-label" for="lease-policy-enabled">Running environments stop when their lease ends</label>
                </div>
                <label class="form-label" for="lease-policy-default">Default length</label>
                <select class="form-select mb-2" id="lease-policy-default">${opts(policy.defaultMinutes, policy.effectiveDefaultMinutes)}</select>
                <label class="form-label" for="lease-policy-max">Maximum without approval</label>
                <select class="form-select" id="lease-policy-max">${opts(policy.maxMinutes, policy.effectiveMaxMinutes)}</select>
                <p class="small text-muted mt-2 mb-0">Platform limit: ${minutesLabel(max)}.</p>
                <div class="text-danger small mt-2" id="lease-policy-error" role="alert"></div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Save', class: 'btn-primary', id: 'lease-policy-save' }
            ],
            onShow: function() {
                $('#lease-policy-save').on('click', function() {
                    const body = {
                        enabled: $('#lease-policy-enabled').is(':checked'),
                        defaultMinutes: Number($('#lease-policy-default').val()),
                        maxMinutes: Number($('#lease-policy-max').val())
                    };
                    if (body.defaultMinutes > body.maxMinutes) {
                        $('#lease-policy-error').text('The default cannot be longer than the maximum.');
                        return;
                    }
                    const $btn = $(this).prop('disabled', true);
                    ApiClient.put(Config.API.leases.policy(envId), body, { suppressGlobalError: true })
                        .done(saved => {
                            policies[envId] = saved;
                            Modals.hide('leasePolicyModal');
                            Notifications.success('Lease settings saved.');
                            if (onDone) onDone();
                        })
                        .fail(xhr => {
                            $btn.prop('disabled', false);
                            $('#lease-policy-error').text((xhr.responseJSON && xhr.responseJSON.message) || 'Could not save.');
                        });
                });
            }
        });
    }

    function decide(requestId, approve) {
        const url = approve ? Config.API.leases.approve(requestId) : Config.API.leases.deny(requestId);
        ApiClient.post(url, {}, { suppressGlobalError: true })
            .done(() => {
                Notifications.success(approve ? 'Extension approved.' : 'Extension denied.');
                if (current) render(current.env);
            })
            .fail(xhr => Notifications.error((xhr.responseJSON && xhr.responseJSON.message) || 'Could not decide.'));
    }

    // ---------------------------------------------------------------- start dialog support

    /** "Run for" options for a START on this environment, or null when leases do not apply. */
    function runForOptions(envId) {
        const policy = policies[envId];
        if (!policy || !policy.enabled) return null;
        const values = POLICY_OPTIONS.filter(m => m <= policy.effectiveMaxMinutes);
        if (!values.includes(policy.effectiveDefaultMinutes)) values.push(policy.effectiveDefaultMinutes);
        values.sort((a, b) => a - b);
        return { values, defaultValue: policy.effectiveDefaultMinutes, label: minutesLabel };
    }

    /** The lease policy last read for an environment, or null (U08-T02). */
    function policyFor(envId) {
        return policies[envId] || null;
    }

    return {
        render,
        policyFor,
        showExtendDialog,
        showPolicyDialog,
        runForOptions,
        extend: () => current && current.lease && showExtendDialog(current.env.environmentId, current.lease, current.policy,
            () => render(current.env)),
        settings: () => current && current.policy && showPolicyDialog(current.env.environmentId, current.policy,
            () => render(current.env)),
        decide
    };
})();

window.Leases = Leases;

Actions.registerAll({
    'lease-extend': () => Leases.extend(),
    'lease-settings': () => Leases.settings(),
    'lease-approve': el => Leases.decide(el.dataset.requestId, true),
    'lease-deny': el => Leases.decide(el.dataset.requestId, false)
});
