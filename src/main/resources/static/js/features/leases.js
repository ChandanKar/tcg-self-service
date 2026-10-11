/**
 * Environment leases on the environment page (E15-T06): when the environment stops, a live
 * countdown, Extend (with owner approval beyond the maximum), pending requests for owners and the
 * owner's lease settings. Buttons follow the permissions the server sends; nothing renders while
 * the feature is off (the environment's leaseEnabled is null).
 */
const Leases = (function() {
    'use strict';

    const EXTEND_OPTIONS = [30, 60, 120, 240];
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

    function clock(ts) {
        return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    // ---------------------------------------------------------------- rendering

    /** Load the lease and policy and render them into #env-lease-section. */
    function render(env) {
        const $section = $('#env-lease-section');
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
                        $section.html(buildSection(current));
                        startTimers(envId);
                        openExtendFromRoute();
                    });
            });
    }

    function buildSection({ env, lease, policy, requests }) {
        const canEditPolicy = policy && policy.canEdit;
        const settings = canEditPolicy
            ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="lease-settings">
                   <i class="fas fa-sliders-h" aria-hidden="true"></i> Lease settings</button>`
            : '';

        if (!lease) {
            if (!policy || (!policy.enabled && !canEditPolicy)) return '';
            const text = policy.enabled
                ? `Runs for ${minutesLabel(policy.effectiveDefaultMinutes)} when started (up to ${minutesLabel(policy.effectiveMaxMinutes)}).`
                : 'Leases are off for this environment.';
            return Utils.html`<div class="lease-banner lease-idle" role="status">
                    <i class="fas fa-hourglass-start" aria-hidden="true"></i>
                    <span class="lease-text">${text}</span>
                    ${Utils.raw(settings)}
                </div>`;
        }

        const endsAt = new Date(lease.endsAt).getTime();
        const soon = endsAt - Date.now() < ENDING_SOON_MS;
        const who = (lease.participants || []).map(p => p.displayName || p.userId).join(', ');
        const pending = lease.pendingExtension;
        const extend = lease.permissions && lease.permissions.canExtend
            ? Utils.html`<button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="lease-extend">
                   <i class="fas fa-clock" aria-hidden="true"></i> Extend</button>`
            : '';
        const pendingPill = pending
            ? Utils.html`<span class="lease-pill" title="${pending.reason || ''}">Pending approval: +${minutesLabel(pending.minutes)}</span>`
            : '';
        const requestRows = (requests || []).map(r => Utils.html`
            <li class="lease-request">
                <span><strong>${r.requesterName || r.requesterUserId}</strong> asks for +${minutesLabel(r.minutes)}${r.reason ? ': ' + r.reason : ''}</span>
                <span class="lease-request-actions">
                    <button type="button" class="btn btn-sm btn-tonal btn-success" data-action="lease-approve" data-request-id="${r.requestId}">Approve</button>
                    <button type="button" class="btn btn-sm btn-tonal btn-danger" data-action="lease-deny" data-request-id="${r.requestId}">Deny</button>
                </span>
            </li>`).join('');

        return Utils.html`<div class="lease-banner ${soon ? 'lease-ending-soon' : ''}" role="status">
                <i class="fas fa-hourglass-half" aria-hidden="true"></i>
                <div class="lease-text">
                    <strong>Running until ${clock(endsAt)}</strong>
                    (<span class="lease-countdown" data-ends-at="${endsAt}">${untilLabel(endsAt)}</span>)
                    ${Utils.raw(pendingPill)}
                    ${who ? Utils.raw(Utils.html`<div class="lease-participants">Kept running by ${who}</div>`) : ''}
                </div>
                <div class="lease-actions">${Utils.raw(extend)}${Utils.raw(settings)}</div>
            </div>
            ${requestRows ? Utils.raw(`<ul class="lease-requests" aria-label="Extension requests">${requestRows}</ul>`) : ''}`;
    }

    function updateCountdown() {
        document.querySelectorAll('#env-lease-section .lease-countdown').forEach(el => {
            const endsAt = Number(el.dataset.endsAt);
            el.textContent = untilLabel(endsAt);
            const banner = el.closest('.lease-banner');
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

    /** Extend dialog; reusable from My Account (E15-T07). */
    function showExtendDialog(envId, lease, policy, onDone) {
        const options = EXTEND_OPTIONS.map(m => `<option value="${m}">${minutesLabel(m)}</option>`).join('');
        Modals.show({
            id: 'leaseExtendModal',
            title: 'Keep the environment running',
            body: `
                <label class="form-label" for="lease-extend-minutes">Extend by</label>
                <select class="form-select" id="lease-extend-minutes">${options}</select>
                <p class="small text-muted mt-2 mb-2" id="lease-extend-preview" aria-live="polite"></p>
                <p class="small fw-semibold mb-2" id="lease-extend-cost" aria-live="polite" hidden></p>
                <div id="lease-extend-reason-wrap" hidden>
                    <label class="form-label" for="lease-extend-reason">Reason for the owner</label>
                    <textarea class="form-control" id="lease-extend-reason" rows="2" maxlength="500"></textarea>
                </div>
                <div class="text-danger small mt-2" id="lease-extend-error" role="alert"></div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Extend', class: 'btn-primary', id: 'lease-extend-submit' }
            ],
            onShow: function() {
                const update = () => {
                    const minutes = Number($('#lease-extend-minutes').val());
                    const base = Math.max(Date.now(), new Date(lease.endsAt).getTime());
                    const newEnd = base + minutes * 60000;
                    const total = (newEnd - new Date(lease.startedAt).getTime()) / 60000;
                    const needsApproval = policy && total > policy.effectiveMaxMinutes;
                    $('#lease-extend-preview').text(`Runs until ${clock(newEnd)}.` +
                        (needsApproval ? ' Needs owner approval: longer than the maximum.' : ''));
                    $('#lease-extend-reason-wrap').prop('hidden', !needsApproval);
                    $('#lease-extend-submit').text(needsApproval ? 'Ask the owner' : 'Extend');
                    return needsApproval;
                };
                // The extra cost of keeping the running VMs up that long (E25-T02).
                const updateCost = () => {
                    if (typeof CostPreview === 'undefined') return;
                    const minutes = Number($('#lease-extend-minutes').val());
                    CostPreview.render($('#lease-extend-cost'), envId, null, Math.ceil(minutes / 60), true,
                        () => $('#lease-extend-cost').length > 0 && Number($('#lease-extend-minutes').val()) === minutes);
                };
                $('#lease-extend-minutes').on('change', () => { update(); updateCost(); });
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
                        { minutes: Number($('#lease-extend-minutes').val()), reason: reason || null },
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

    return {
        render,
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
