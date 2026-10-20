/**
 * Idle auto-stop on the environment page (E16-T05, G5), since U07-T06 the "Idle auto-stop" fact in
 * "About this environment" (#env-fact-idle): the mode and threshold, what a dry run would have
 * saved, a snooze, and "Pause or review" opening snooze and the rule settings. Renders nothing
 * while the feature flag (automation.idle-stop.enabled) is off; the page does not even ask then.
 */
const IdleStop = (function() {
    'use strict';

    const DAY_MS = 24 * 60 * 60 * 1000;
    const MIN_DRY_RUN_DAYS = 14;
    let current = null;       // { env, status }
    let saving = false;       // one rule Save in flight at a time
    let renderToken = 0;      // drops a late response for an environment no longer shown

    /** "14:30", 24-hour like the rest of the app. */
    function time(ts) {
        if (!ts) return '';
        const d = new Date(ts);
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    }

    function dateTime(ts) {
        return ts ? new Date(ts).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
    }

    function money(amount) {
        return Utils.formatCurrency ? Utils.formatCurrency(Number(amount || 0)) : '$' + Number(amount || 0).toFixed(2);
    }

    function envRule(status) {
        return (status.rules || []).find(r => r.scopeType === 'ENVIRONMENT') || (status.rules || [])[0] || null;
    }

    /** Fetch the status and render the fact into #env-fact-idle. */
    function renderPanel(env) {
        const $panel = $('#env-fact-idle');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        ApiClient.get(Config.API.idleStop.status(env.environmentId), { suppressGlobalError: true })
            .done(function(status) {
                if (token !== renderToken) return;
                current = { env, status };
                $panel.html(buildFact(env, status));
                if (typeof RealTime !== 'undefined' && RealTime.startPolling) {
                    RealTime.startPolling('idleStopPanel', () => refresh(), 60000, { pageScoped: true, immediate: false });
                }
            })
            .fail(function() {
                if (token === renderToken) $panel.empty(); // the panel is optional; the page still works
            });
    }

    function refresh() {
        if (current && $('#env-fact-idle').length) renderPanel(current.env);
    }

    /** "Dry run · after 60 min idle", "On · after 60 min idle", "Off" or "Production — excluded". */
    function modeText(status, rule) {
        if (status.production) return 'Production — excluded';
        if (!rule || !rule.enabled) return 'Off';
        const after = rule.idleMinutes ? ` · after ${rule.idleMinutes} min idle` : '';
        return (rule.mode === 'ENFORCE' ? 'On' : 'Dry run') + after;
    }

    /** The notes under the value: savings, snooze, and why the last check skipped. */
    function noteLines(status, rule) {
        const lines = [];
        if (status.snoozedUntil) {
            lines.push(Utils.html`<div class="env-fact-note" id="idle-snoozed">Snoozed until ${time(status.snoozedUntil)}${status.snoozedByDisplayName ? ' by ' + status.snoozedByDisplayName : ''}</div>`);
        }
        if (rule && rule.enabled && rule.mode === 'DRY_RUN') {
            lines.push(Utils.html`<div class="env-fact-note" id="idle-would-have-saved"
                title="${status.wouldStopEpisodes} idle period${status.wouldStopEpisodes === 1 ? '' : 's'}">Would have saved
                <strong>${money(status.wouldHaveSaved)}</strong> in the last ${status.days} days</div>`);
        } else if (rule && rule.enabled && rule.mode === 'ENFORCE') {
            lines.push(Utils.html`<div class="env-fact-note" id="idle-saved">Saved about <strong>${money(status.savedEstimate)}</strong>
                in ${status.days} days (${status.stoppedCount} automatic stop${status.stoppedCount === 1 ? '' : 's'})</div>`);
        }
        const latest = status.latestEvent;
        if (latest && (latest.outcome === 'SKIPPED' || latest.outcome === 'SNOOZED') && latest.reason) {
            lines.push(Utils.html`<div class="env-fact-note">Last check: ${latest.reason}</div>`);
        }
        return lines.join('');
    }

    /** The fact; "Pause or review" for those who may snooze or change the rule. */
    function buildFact(env, status) {
        if (!status.featureEnabled) return '';
        const rule = envRule(status);
        const canAct = !status.production && (status.canAdminister || (status.canOperate && rule && rule.enabled));
        const action = canAct
            ? '<button type="button" class="btn btn-link env-fact-action" data-action="idle-review">Pause or review</button>' : '';
        return Utils.html`
            <div class="env-fact idle-fact" id="idle-stop-card">
                <div class="env-fact-head"><span>Idle auto-stop</span><span class="env-fact-actions">${Utils.raw(action)}</span></div>
                <div class="env-fact-value">${modeText(status, rule)}</div>
                ${Utils.raw(noteLines(status, rule))}
            </div>`;
    }

    /**
     * "Pause or review": snooze for 1, 4 or 8 hours (or end the snooze) and, for those who
     * administer the environment, the rule settings. U08-T09 replaces it with the full dialog.
     */
    function openReview() {
        if (!current) return;
        const { status } = current;
        const rule = envRule(status);
        let actions = '';
        if (status.snoozedUntil) {
            if (status.canOperate) {
                actions += '<button type="button" class="btn btn-secondary" data-action="idle-end-snooze">End snooze</button>';
            }
        } else if (status.canOperate && rule && rule.enabled && !status.production) {
            actions += '<div class="idle-snooze-row"><span>Pause for</span>' + [1, 4, 8].map(h =>
                `<button type="button" class="btn btn-secondary" data-action="idle-snooze" data-hours="${h}">${h} h</button>`).join('') + '</div>';
        }
        if (status.canAdminister && !status.production) {
            actions += '<button type="button" class="btn btn-link px-0" data-action="idle-rule-settings">Rule settings</button>';
        }
        Modals.show({
            id: 'idleStopReviewModal',
            title: 'Idle auto-stop',
            body: Utils.html`<p class="dialog-lead mb-2">${modeText(status, rule)}</p>`
                + noteLines(status, rule)
                + `<div class="idle-review-actions">${actions}</div>`,
            buttons: [{ text: 'Close', class: 'btn-secondary', dismiss: true }]
        });
    }

    function closeReview() {
        const el = document.getElementById('idleStopReviewModal');
        if (el && typeof Modals !== 'undefined' && Modals.hide) Modals.hide('idleStopReviewModal');
    }

    function snooze(hours) {
        if (!current) return;
        ApiClient.post(Config.API.idleStop.snooze(current.env.environmentId), { hours: Number(hours) }, { suppressGlobalError: true })
            .done(function() {
                closeReview();
                Notifications.success(`Idle auto-stop snoozed for ${hours} h`);
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not snooze');
            });
    }

    function endSnooze() {
        if (!current) return;
        ApiClient.delete(Config.API.idleStop.snooze(current.env.environmentId), { suppressGlobalError: true })
            .done(function() {
                closeReview();
                Notifications.success('Snooze ended');
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not end the snooze');
            });
    }

    /** ENFORCE needs 14 days of dry run unless the user is an admin (the server checks too). */
    function enforceAllowed(rule) {
        if (Auth.isAdmin && Auth.isAdmin()) return true;
        if (!rule || !rule.dryRunStartedAt) return false;
        return Date.now() - new Date(rule.dryRunStartedAt).getTime() >= MIN_DRY_RUN_DAYS * DAY_MS;
    }

    function openRuleModal() {
        closeReview();
        if (!current) return;
        const { env, status } = current;
        const rule = envRule(status);
        const enforceOk = rule ? enforceAllowed(rule) || rule.mode === 'ENFORCE' : false;
        const groups = (env.groups || []).map(gd => gd.group || gd).filter(g => g && g.groupId);
        $('#idleStopRuleModal').remove();
        $('body').append(Utils.html`
            <div class="modal fade" id="idleStopRuleModal" tabindex="-1" aria-labelledby="idleStopRuleModalLabel">
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="idleStopRuleModalLabel">Idle auto-stop settings</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <div class="mb-3">
                                <label class="form-label" for="idleScope">Applies to</label>
                                <select class="form-select" id="idleScope" ${rule ? 'disabled' : ''}>
                                    <option value="">Entire environment</option>
                                    ${Utils.raw(groups.map(g => Utils.html`<option value="${g.groupId}" ${rule && rule.groupId === g.groupId ? 'selected' : ''}>${g.displayName || g.name}</option>`).join(''))}
                                </select>
                            </div>
                            <div class="row g-2 mb-2">
                                <div class="col-4">
                                    <label class="form-label" for="idleMinutes">Idle minutes</label>
                                    <input type="number" class="form-control" id="idleMinutes" min="30" max="1440" value="${rule ? rule.idleMinutes : 60}">
                                </div>
                                <div class="col-4">
                                    <label class="form-label" for="idleCpu">CPU below %</label>
                                    <input type="number" class="form-control" id="idleCpu" min="0.5" max="20" step="0.5" value="${rule ? rule.cpuMaxPercent : 5}">
                                </div>
                                <div class="col-4">
                                    <label class="form-label" for="idleNetwork">Network MB/day</label>
                                    <input type="number" class="form-control" id="idleNetwork" min="0.1" max="1000" step="0.1" value="${rule ? rule.networkMbPerDay : 5}">
                                </div>
                            </div>
                            <div class="form-text mb-3">Defaults follow AWS Compute Optimizer's idle definition.</div>
                            <div class="mb-3">
                                <label class="form-label" for="idleMode">Mode</label>
                                <select class="form-select" id="idleMode" ${rule ? '' : 'disabled'}>
                                    <option value="DRY_RUN" ${!rule || rule.mode === 'DRY_RUN' ? 'selected' : ''}>Dry run (record only)</option>
                                    <option value="ENFORCE" ${rule && rule.mode === 'ENFORCE' ? 'selected' : ''} ${enforceOk ? '' : 'disabled'}
                                            title="${enforceOk ? '' : 'Available after 14 days of dry run'}">Enforce (stop idle VMs)</option>
                                </select>
                                ${!enforceOk ? Utils.raw('<div class="form-text">Enforce is available after 14 days of dry run.</div>') : ''}
                            </div>
                            <div class="form-check">
                                <input class="form-check-input" type="checkbox" id="idleEnabled" ${!rule || rule.enabled ? 'checked' : ''}>
                                <label class="form-check-label" for="idleEnabled">Enabled</label>
                            </div>
                        </div>
                        <div class="modal-footer">
                            ${rule ? Utils.raw('<button type="button" class="btn btn-outline-danger me-auto" data-action="idle-delete-rule">Delete rule</button>') : ''}
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="idleSaveRule" data-action="idle-save-rule">Save</button>
                        </div>
                    </div>
                </div>
            </div>`);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('idleStopRuleModal')).show();
    }

    function saveRule() {
        if (!current || saving) return;
        const { env, status } = current;
        const rule = envRule(status);
        const groupId = $('#idleScope').val();
        const body = {
            idleMinutes: Number($('#idleMinutes').val()),
            cpuMaxPercent: Number($('#idleCpu').val()),
            networkMbPerDay: Number($('#idleNetwork').val()),
            enabled: $('#idleEnabled').is(':checked')
        };
        if (rule) {
            body.mode = $('#idleMode').val();
        } else {
            body.scopeType = groupId ? 'GROUP' : 'ENVIRONMENT';
            body.groupId = groupId || null;
        }
        saving = true;
        const $btn = $('#idleSaveRule').prop('disabled', true);
        const request = rule
            ? ApiClient.put(Config.API.idleStop.rule(env.environmentId, rule.ruleId), body, { suppressGlobalError: true })
            : ApiClient.post(Config.API.idleStop.rules(env.environmentId), body, { suppressGlobalError: true });
        request
            .done(function() {
                bootstrap.Modal.getInstance(document.getElementById('idleStopRuleModal'))?.hide();
                Notifications.success(rule ? 'Idle auto-stop rule saved' : 'Idle auto-stop rule created (dry run)');
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not save the rule');
            })
            .always(function() {
                saving = false;
                $btn.prop('disabled', false);
            });
    }

    function deleteRule() {
        if (!current) return;
        const rule = envRule(current.status);
        if (!rule) return;
        ApiClient.delete(Config.API.idleStop.rule(current.env.environmentId, rule.ruleId), { suppressGlobalError: true })
            .done(function() {
                bootstrap.Modal.getInstance(document.getElementById('idleStopRuleModal'))?.hide();
                Notifications.success('Idle auto-stop rule deleted');
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not delete the rule');
            });
    }

    return { renderPanel, refresh, snooze, endSnooze, openReview, openRuleModal, saveRule, deleteRule };
})();

window.IdleStop = IdleStop;

Actions.registerAll({
    'idle-snooze': el => IdleStop.snooze(el.dataset.hours),
    'idle-end-snooze': () => IdleStop.endSnooze(),
    'idle-review': () => IdleStop.openReview(),
    'idle-rule-settings': () => IdleStop.openRuleModal(),
    'idle-save-rule': () => IdleStop.saveRule(),
    'idle-delete-rule': () => IdleStop.deleteRule()
});
