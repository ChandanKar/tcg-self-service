/**
 * VM Self-Service Platform - Start and stop confirmation (U08-T02, StartConfirm.dc.html)
 * "Start 2 VMs in Payments Dev?" with what starts now and what already runs, the VMs and their
 * sizes, how long to keep them running (the lease's lengths, plus the schedule's stop when it
 * comes sooner), what each length costs and who must approve it, and a button that says what
 * happens: "Start 2 VMs" or "Send for approval". Stops ask the same way without duration or cost.
 * The words and choices are pure functions (OperationConfirm.model), tested in
 * src/test/js/operation-confirm.test.mjs. The server checks the lock, the lease and the approval
 * again on submit.
 */
const OperationConfirm = (function() {
    'use strict';

    const pad = n => String(n).padStart(2, '0');
    const isTransitional = s => s === 'STARTING' || s === 'STOPPING';

    function vmsOf(env) {
        return (env.groups || []).slice()
            .sort((a, b) => ((a.group || a).sequencePosition ?? 0) - ((b.group || b).sequencePosition ?? 0))
            .flatMap(gd => (gd.vms || []).map(vm => ({ vm, group: gd.group || gd })));
    }

    /**
     * The VMs an operation acts on: in scope ({level: environment | group | vm, groupId, vmId}),
     * not yet running for a START, running for a STOP; transitional VMs are left alone.
     */
    function targetsFor(env, opType, scope) {
        const inScope = vmsOf(env).filter(({ vm, group }) =>
            scope.level === 'vm' ? vm.vmId === scope.vmId
                : scope.level === 'group' ? group.groupId === scope.groupId : true);
        return inScope.filter(({ vm }) => !isTransitional(vm.status)
            && (opType === 'STOP' ? vm.status === 'RUNNING' : vm.status !== 'RUNNING'));
    }

    /** "A", "A and B", "A, B and C". */
    function joinNames(names) {
        if (names.length <= 1) return names.join('');
        return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
    }

    function groupName(group) {
        return group.displayName || group.name;
    }

    /**
     * "The Web group starts now. Database and Application are already running." For a VM:
     * "web-01 starts now." For a stop: "The Web group stops now."
     */
    function groupSentence(env, opType, scope, targets) {
        const starting = opType !== 'STOP';
        const verb = n => starting ? (n === 1 ? 'starts' : 'start') : (n === 1 ? 'stops' : 'stop');
        if (scope.level === 'vm') {
            const t = targets[0];
            return t ? `${t.vm.displayName || t.vm.name} ${verb(1)} now.` : '';
        }
        const acted = [];
        targets.forEach(({ group }) => { if (!acted.includes(groupName(group))) acted.push(groupName(group)); });
        if (!acted.length) {
            const inScope = vmsOf(env).filter(({ group }) => scope.level !== 'group' || group.groupId === scope.groupId);
            if (!inScope.length) return '';
            return starting ? 'Everything here is already running.' : 'Nothing here is running.';
        }
        let sentence = acted.length === 1 ? `The ${acted[0]} group ${verb(1)} now.` : `${joinNames(acted)} ${verb(acted.length)} now.`;
        if (starting && scope.level === 'environment') {
            const running = (env.groups || [])
                .map(gd => ({ group: gd.group || gd, vms: gd.vms || [] }))
                .filter(g => g.vms.length && g.vms.every(v => v.status === 'RUNNING'))
                .sort((a, b) => (a.group.sequencePosition ?? 0) - (b.group.sequencePosition ?? 0))
                .map(g => groupName(g.group));
            if (running.length) sentence += ` ${joinNames(running)} ${running.length === 1 ? 'is' : 'are'} already running.`;
        }
        return sentence;
    }

    function hoursLabel(minutes) {
        if (minutes < 60) return `${minutes} minutes`;
        const h = minutes / 60;
        return Number.isInteger(h) ? `${h} hour${h === 1 ? '' : 's'}` : `${Math.round(h * 10) / 10} hours`;
    }

    /**
     * The "Keep them running until" chips, at most four: the schedule's next stop when it comes
     * before the lease's default (labelled "Schedule stop, 20:00"), then lengths the lease allows
     * (2, 8 and 24 hours, and the default). Each is {minutes, label, schedule?}. None without a
     * lease policy that is on. The default is chosen first: the schedule stop, else the lease default.
     */
    function durationChips(policy, nextStopAt, now = new Date()) {
        if (!policy || !policy.enabled) return { chips: [], selected: null };
        const max = policy.effectiveMaxMinutes || policy.maxMinutes || 0;
        const def = policy.effectiveDefaultMinutes || policy.defaultMinutes || 0;
        const chips = [];
        const stop = nextStopAt ? new Date(nextStopAt) : null;
        if (stop && !Number.isNaN(stop.getTime())) {
            const minutes = Math.round((stop.getTime() - now.getTime()) / 60000);
            if (minutes >= 15 && minutes < def && minutes <= max) {
                chips.push({ minutes, label: `Schedule stop, ${pad(stop.getHours())}:${pad(stop.getMinutes())}`, schedule: true });
            }
        }
        const lengths = [...new Set([120, 480, 1440, def].filter(m => m > 0 && m <= max))].sort((a, b) => a - b);
        lengths.forEach(m => { if (chips.length < 4) chips.push({ minutes: m, label: hoursLabel(m) }); });
        const selected = chips.find(c => c.schedule) || chips.find(c => c.minutes === def) || chips[0] || null;
        return { chips, selected: selected ? selected.minutes : null };
    }

    /**
     * What a length means: "Stops with the rest of the environment at 20:00 (in 2 h 15 min).",
     * "Stops at 19:45. You get a reminder 15 minutes before." or, past the schedule's stop,
     * "Runs past the schedule stop, until 01:45 tomorrow."
     */
    function hintFor(chip, nextStopAt, now = new Date()) {
        const until = new Date(now.getTime() + chip.minutes * 60000);
        const at = `${pad(until.getHours())}:${pad(until.getMinutes())}`;
        const tomorrow = until.toDateString() !== now.toDateString() ? ' tomorrow' : '';
        if (chip.schedule) {
            const h = Math.floor(chip.minutes / 60), m = chip.minutes % 60;
            const inText = h ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`;
            return `Stops with the rest of the environment at ${at} (in ${inText}).`;
        }
        const stop = nextStopAt ? new Date(nextStopAt) : null;
        if (stop && !Number.isNaN(stop.getTime()) && stop > now && until > stop) {
            return `Runs past the schedule stop, until ${at}${tomorrow}.`;
        }
        return `Stops at ${at}${tomorrow}. You get a reminder 15 minutes before.`;
    }

    /** "No approval needed", "Needs Sanjay Rao" or the block reason, from an evaluate answer. */
    function approvalText(decision) {
        if (!decision) return { text: 'Approval is checked when you start', needs: false, blocked: false };
        if (decision.blocked) {
            return { text: (decision.warnings && decision.warnings[0]) || 'Blocked by the team quota', needs: false, blocked: true };
        }
        if (decision.requiresApproval) {
            const names = [...new Set((decision.matches || []).flatMap(m => m.approverNames || []))];
            return { text: names.length ? `Needs ${joinNames(names)}` : 'Needs approval', needs: true, blocked: false };
        }
        return { text: 'No approval needed', needs: false, blocked: false };
    }

    /**
     * "Start 2 VMs in Payments Dev?", "Stop 1 VM in Payments Dev?", "Scale 2 node groups in Orders
     * to 0?"; "Start Payments Dev?" when nothing is listed to act on (the server decides).
     */
    function titleFor(env, opType, targets) {
        const n = targets.length;
        const envName = env.displayName || env.name || 'this environment';
        const eks = String(env.serviceType || '').toUpperCase() === 'EKS';
        const things = eks ? (n === 1 ? 'node group' : 'node groups') : (n === 1 ? 'VM' : 'VMs');
        if (opType === 'STOP') {
            if (!n) return `Stop ${envName}?`;
            return eks ? `Scale ${n} ${things} in ${envName} to 0?` : `Stop ${n} ${things} in ${envName}?`;
        }
        return n ? `Start ${n} ${things} in ${envName}?` : `Start ${envName}?`;
    }

    /**
     * The note under the cost: "$0.09 an hour for both", with "for 8 h" first when no length was
     * chosen, and "price unknown for 1 VM, approximate" when it applies. Null when nothing is priced.
     */
    function costNote(preview, count, withHours) {
        if (!preview || !preview.vms || !preview.vms.length) return null;
        const usd = v => '$' + Number(v || 0).toFixed(2);
        const who = count === 2 ? ' for both' : count > 2 ? ` for all ${count}` : '';
        const parts = [(withHours && preview.hours ? `for ${preview.hours} h, ` : '') + `${usd(preview.hourlyTotal)} an hour${who}`];
        const extra = [];
        if (preview.unknownPriceCount > 0) {
            extra.push(`price unknown for ${preview.unknownPriceCount} VM${preview.unknownPriceCount === 1 ? '' : 's'}`);
        }
        if (preview.approximate) extra.push('approximate');
        if (extra.length) parts.push(extra.join(', '));
        return parts.join(' · ');
    }

    // ---------------------------------------------------------------- the dialog

    const MODAL_ID = 'operationConfirmModal';
    let state = null;   // {env, opType, scope, targets, chips, selected, cost: {length: preview}, approval: {length: decision}, token}

    function money(v) {
        return typeof CostPreview !== 'undefined' && CostPreview.money ? CostPreview.money(v) : '$' + Number(v || 0).toFixed(2);
    }

    function scopeParam(scope) {
        return scope.level === 'group' ? { groupId: scope.groupId } : scope.level === 'vm' ? { vmId: scope.vmId } : null;
    }

    function noun(env, n) {
        const eks = String(env.serviceType || '').toUpperCase() === 'EKS';
        return eks ? (n === 1 ? 'node group' : 'node groups') : (n === 1 ? 'VM' : 'VMs');
    }

    function namesLine(targets) {
        const shown = targets.slice(0, 6).map(({ vm }) => vm.displayName || vm.name);
        const more = targets.length - shown.length;
        const sizes = [...new Set(targets.map(({ vm }) => vm.instanceType).filter(Boolean))];
        return Utils.escapeHtml(shown.join(', ') + (more > 0 ? ` +${more} more` : ''))
            + (sizes.length === 1 ? Utils.html` · <span class="mono">${sizes[0]}</span>` : '');
    }

    function stripHtml(env, targets) {
        const ids = new Set(targets.map(t => t.vm.vmId));
        const groups = (env.groups || []).slice()
            .sort((a, b) => ((a.group || a).sequencePosition ?? 0) - ((b.group || b).sequencePosition ?? 0))
            .map(gd => ({ name: groupName(gd.group || gd),
                lights: (gd.vms || []).map(vm => ids.has(vm.vmId) ? 'B' : PowerStrip.lightFor(vm)) }));
        return PowerStrip.render(groups, { showCount: false });
    }

    function bodyHtml() {
        const { env, opType, scope, targets, chips, selected } = state;
        const starting = opType !== 'STOP';
        const chipsHtml = starting && chips.length ? `
            <div class="op-confirm-duration">
                ${ChoiceChips.markup({ id: 'op-confirm-chips', legend: 'Keep them running until', selected,
                    options: chips.map(c => ({ value: c.minutes, label: c.label })) })}
                <p class="op-confirm-hint" id="op-confirm-hint" aria-live="polite"></p>
            </div>` : '';
        const facts = starting ? `
            <div class="op-confirm-facts">
                <div class="op-confirm-fact"><div class="op-confirm-label">Estimated cost</div>
                    <div class="mono op-confirm-value" id="op-confirm-cost">…</div>
                    <div class="op-confirm-note" id="op-confirm-rate"></div></div>
                <div class="op-confirm-fact"><div class="op-confirm-label">Approval</div>
                    <div class="op-confirm-value" id="op-confirm-approval">…</div>
                    <div class="op-confirm-note" id="op-confirm-approval-note"></div></div>
            </div>
            <div class="op-confirm-note" id="quota-preview-line" aria-live="polite" hidden></div>` : '';
        return `
            <p class="dialog-lead" id="op-confirm-sentence">${Utils.escapeHtml(groupSentence(env, opType, scope, targets))}
                <span id="op-confirm-estimate"></span></p>
            ${targets.length ? `<div class="op-confirm-targets">${stripHtml(env, targets)}<span>${namesLine(targets)}</span></div>` : ''}
            ${chipsHtml}
            ${facts}`;
    }

    function ctaText() {
        const { env, opType, targets } = state;
        const n = targets.length;
        if (opType === 'STOP') {
            if (!n) return 'Stop';
            return String(env.serviceType || '').toUpperCase() === 'EKS' ? `Scale ${n} ${noun(env, n)} to 0` : `Stop ${n} ${noun(env, n)}`;
        }
        if (approvalText(state.approval[state.selected ?? 'none']).needs) return 'Send for approval';
        return n ? `Start ${n} ${noun(env, n)}` : 'Start';
    }

    /** Start stays off while the approval check or the team quota blocks it; the quota says why. */
    function updateButton() {
        const $btn = $('#op-confirm-btn');
        const key = state.selected ?? 'none';
        const approvalBlocked = approvalText(state.approval[key]).blocked;
        const quotaBlocked = $('#quota-preview-line').attr('data-quota') === 'block';
        $btn.text(ctaText()).prop('disabled', approvalBlocked || quotaBlocked);
        if (!quotaBlocked) $btn.removeAttr('title');
    }

    /** Cost and approval for the chosen length, loaded once per length. */
    function refreshChoice() {
        if (!state || state.opType === 'STOP') return;
        const key = state.selected ?? 'none';
        const hours = state.selected ? Math.ceil(state.selected / 60) : null;
        const chip = state.chips.find(c => c.minutes === state.selected);
        $('#op-confirm-hint').text(chip ? hintFor(chip, state.nextStop) : '');
        $('#quota-preview-line').prop('hidden', true).removeAttr('data-quota').empty();
        const token = state.token;
        const current = () => state && state.token === token && (state.selected ?? 'none') === key && $('#' + MODAL_ID).length > 0;

        const showCost = preview => {
            if (!current()) return;
            const note = costNote(preview, state.targets.length, !chip);
            $('#op-confirm-cost').text(note ? money(preview.estimatedTotal) : '—');
            $('#op-confirm-rate').text(note || 'No price known');
            updateButton();
            if (note && typeof CostPreview !== 'undefined' && CostPreview.renderQuota) {
                CostPreview.renderQuota($('#quota-preview-line'), state.env.teamId || null, preview, $('#op-confirm-btn'))
                    .then(() => { if (current()) updateButton(); });
            }
        };
        const showApproval = decision => {
            if (!current()) return;
            const a = approvalText(decision);
            const match = a.needs && decision.matches && decision.matches[0];
            $('#op-confirm-approval').text(a.text);
            $('#op-confirm-approval-note').text(match ? (match.name || '') : '');
            updateButton();
        };
        if (key in state.cost) showCost(state.cost[key]);
        else if (typeof CostPreview !== 'undefined') {
            CostPreview.load(state.env.environmentId, scopeParam(state.scope), hours, false)
                .then(p => p, () => null)
                .then(p => { if (state && state.token === token) state.cost[key] = p; showCost(p); });
        }
        if (key in state.approval) showApproval(state.approval[key]);
        else {
            const body = Object.assign({ environmentId: state.env.environmentId, hours }, scopeParam(state.scope) || {});
            ApiClient.post(Config.API.approvalPolicies.evaluate, body, { suppressGlobalError: true })
                .done(d => { if (state && state.token === token) state.approval[key] = d; showApproval(d); })
                .fail(() => showApproval(null));
        }
    }

    function loadEstimate() {
        const { env, opType, scope, token } = state;
        ApiClient.get(Config.API.operations.estimates(env.environmentId, opType, scopeParam(scope)), { suppressGlobalError: true })
            .done(e => {
                if (!state || state.token !== token || !e || !e.sampleCount || !e.avgEnvironmentSeconds) return;
                const minutes = Math.max(1, Math.round(e.avgEnvironmentSeconds / 60));
                $('#op-confirm-estimate').text(`It usually takes about ${minutes} minute${minutes === 1 ? '' : 's'}.`);
            });
    }

    /**
     * Open the confirmation. opts: {env, opType: START | STOP, scope: {level, groupId?, vmId?},
     * onConfirm(leaseMinutes)} where onConfirm returns a promise. Someone else's lock stops it
     * before the dialog opens.
     */
    function open(opts) {
        const { env, opType, scope } = opts;
        const proceed = () => show(opts);
        if (typeof Locks === 'undefined' || !Locks.getStatus) return proceed();
        // A failed lock check opens the dialog anyway (the server checks again); an error in the
        // dialog itself must not open it a second time, so the two are handled apart.
        Locks.getStatus(env.environmentId).then(lockStatus => {
            if (!Locks.canOperate(lockStatus)) {
                const holder = lockStatus.lockedByDisplayName || 'another user';
                Notifications.warning(Utils.html`Locked by ${holder}. You can see it but not ${opType === 'STOP' ? 'stop' : 'start'} VMs.`,
                    5000, { html: true });
                return;
            }
            proceed();
        }, proceed);
    }

    function show({ env, opType, scope, onConfirm }) {
        const targets = targetsFor(env, opType, scope);
        const policy = opType === 'STOP' || typeof Leases === 'undefined' || !Leases.policyFor ? null : Leases.policyFor(env.environmentId);
        const nextStop = typeof EnvCost !== 'undefined' && EnvCost.scheduleFor ? (EnvCost.scheduleFor(env.environmentId) || {}).nextStop : null;
        const { chips, selected } = durationChips(policy, nextStop);
        state = { env, opType, scope, targets, chips, selected, nextStop, cost: {}, approval: {}, token: Date.now() + Math.random() };
        Modals.show({
            id: MODAL_ID,
            title: titleFor(env, opType, targets),
            body: bodyHtml(),
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: ctaText(), class: opType === 'STOP' ? 'btn-danger' : 'btn-success', id: 'op-confirm-btn' }
            ],
            onShow: () => $('#op-confirm-btn').trigger('focus')
        });
        // Bound now, not on "shown": the dialog works while it is still fading in.
        $('#op-confirm-chips').on('choice-change', e => {
            state.selected = Number(e.detail) || null;
            refreshChoice();
        });
        $('#op-confirm-btn').on('click', function() {
            const minutes = state && state.opType !== 'STOP' && state.chips.length ? state.selected : null;
            $(this).prop('disabled', true);
            Modals.hideWhenReady(document.getElementById(MODAL_ID));
            Promise.resolve(onConfirm(minutes)).catch(err => console.error('Operation failed:', err));
        });
        loadEstimate();
        refreshChoice();
    }

    return { open, model: { targetsFor, groupSentence, durationChips, hintFor, approvalText, titleFor, costNote, joinNames } };
})();

if (typeof window !== 'undefined') window.OperationConfirm = OperationConfirm;
