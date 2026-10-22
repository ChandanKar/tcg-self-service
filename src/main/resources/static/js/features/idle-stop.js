/**
 * Idle auto-stop on the environment page (E16-T05, G5), since U07-T06 the "Idle auto-stop" fact in
 * "About this environment" (#env-fact-idle): the mode and threshold, what a dry run would have
 * saved and a pause. "Pause or review" opens one dialog (U08-T09, IdleStop.dc.html): how it is going
 * with the recent decisions, "Working late? Pause it for 2 hours / 4 hours / 8 hours", and for
 * environment admins the rule form, every switch and field named. Renders nothing while the feature
 * flag (automation.idle-stop.enabled) is off; the page does not even ask then. The words are pure
 * (IdleStop.model), tested in src/test/js/idle-stop.test.mjs.
 */
const IdleStop = (function() {
    'use strict';

    const DAY_MS = 24 * 60 * 60 * 1000;
    const DIALOG = 'idleStopDialog';
    const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const pad = n => String(n).padStart(2, '0');
    let current = null;       // { env, status }
    let saving = false;       // one rule Save in flight at a time
    let renderToken = 0;      // drops a late response for an environment no longer shown

    /** "14:30", 24-hour like the rest of the app. */
    function time(ts) {
        if (!ts) return '';
        const d = new Date(ts);
        return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    /** "Sun 03:10" within the week, else "12 Oct 09:15". */
    function when(ts, now = new Date()) {
        const d = new Date(ts);
        if (Number.isNaN(d.getTime())) return '';
        const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        return now.getTime() - d.getTime() < 6 * DAY_MS ? `${DAYS[d.getDay()]} ${clock}` : `${d.getDate()} ${MONTHS[d.getMonth()]} ${clock}`;
    }

    function money(amount) {
        return typeof Utils !== 'undefined' && Utils.formatCurrency ? Utils.formatCurrency(Number(amount || 0)) : '$' + Number(amount || 0).toFixed(2);
    }

    function envRule(status) {
        return (status.rules || []).find(r => r.scopeType === 'ENVIRONMENT') || (status.rules || [])[0] || null;
    }

    /** "Dry run · after 60 min idle", "On · after 60 min idle", "Off" or "Production — excluded". */
    function modeText(status, rule) {
        if (status.production) return 'Production — excluded';
        if (!rule || !rule.enabled) return 'Off';
        const after = rule.idleMinutes ? ` · after ${rule.idleMinutes} min idle` : '';
        return (rule.mode === 'ENFORCE' ? 'On' : 'Dry run') + after;
    }

    function minDays(status) {
        return Number(status && status.minDryRunDays) || 14;
    }

    /** The day stopping becomes available: dry run start + the minimum days. */
    function enforceDate(status, rule) {
        if (!rule || !rule.dryRunStartedAt) return null;
        return new Date(new Date(rule.dryRunStartedAt).getTime() + minDays(status) * DAY_MS);
    }

    /** Stopping is allowed after the dry run, at once for administrators (the server checks too). */
    function enforceAllowed(status, rule, now = new Date()) {
        if (status && status.canEnforceEarly) return true;
        const date = enforceDate(status, rule);
        return !!date && now.getTime() >= date.getTime();
    }

    /** The headline: {title, sub} such as "Dry run · day 9 of 14" / "Records what it would do; stops nothing yet". */
    function headline(status, rule, now = new Date()) {
        if (status.production) return { title: 'Production — never stopped', sub: 'Idle auto-stop does not apply to production environments' };
        if (!rule) return { title: 'No rule yet', sub: 'Nothing is watched or stopped' };
        if (!rule.enabled) return { title: 'Off', sub: 'The rule is switched off' };
        if (rule.mode === 'ENFORCE') return { title: `On · after ${rule.idleMinutes} min idle`, sub: 'Stops the environment when all its VMs are idle' };
        const start = rule.dryRunStartedAt ? new Date(rule.dryRunStartedAt).getTime() : now.getTime();
        const day = Math.max(1, Math.floor((now.getTime() - start) / DAY_MS) + 1);
        const total = minDays(status);
        return { title: day <= total ? `Dry run · day ${day} of ${total}` : `Dry run · day ${day}`, sub: 'Records what it would do; stops nothing yet' };
    }

    /** "In the last 14 days it would have stopped the environment 11 times, saving about $46." */
    function summary(status, rule) {
        if (!rule || !rule.enabled || status.production) return '';
        if (rule.mode === 'ENFORCE') {
            const n = status.stoppedCount || 0;
            return `In the last ${status.days} days it stopped the environment ${n} time${n === 1 ? '' : 's'}, saving about ${money(status.savedEstimate)}.`;
        }
        const n = status.wouldStopEpisodes || 0;
        return `In the last ${status.days} days it would have stopped the environment ${n} time${n === 1 ? '' : 's'}, saving about ${money(status.wouldHaveSaved)}.`;
    }

    /** The recent decisions, newest first: {time, text, tag, tone}. */
    function decisions(status, now = new Date()) {
        return (status.recentEvents || (status.latestEvent ? [status.latestEvent] : [])).map(e => {
            const reason = e.reason || '';
            let tag, tone;
            switch (e.outcome) {
                case 'WOULD_STOP': tag = 'Would stop'; tone = 'would'; break;
                case 'STOPPED': tag = 'Stopped'; tone = 'would'; break;
                case 'SNOOZED': tag = 'Skipped: paused'; tone = 'skip'; break;
                case 'SKIPPED': tag = /lock/i.test(reason) ? 'Skipped: locked' : 'Skipped'; tone = 'skip'; break;
                default: tag = 'Kept running'; tone = 'keep';
            }
            return { time: when(e.evaluatedAt, now), text: reason, tag, tone };
        });
    }

    /** The notes under the fact: savings, pause, and why the last check skipped. */
    function noteLines(status, rule) {
        const lines = [];
        if (status.snoozedUntil) {
            lines.push(Utils.html`<div class="env-fact-note" id="idle-snoozed">Paused until ${time(status.snoozedUntil)}${status.snoozedByDisplayName ? ' by ' + status.snoozedByDisplayName : ''}</div>`);
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

    // ---------------------------------------------------------------- the fact

    /** Fetch the status and render the fact into #env-fact-idle (and the dialog when it is open). */
    function renderPanel(env) {
        const $panel = $('#env-fact-idle');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        ApiClient.get(Config.API.idleStop.status(env.environmentId), { suppressGlobalError: true })
            .done(function(status) {
                if (token !== renderToken) return;
                current = { env, status };
                $panel.html(buildFact(env, status));
                if ($(`#${DIALOG}`).length) renderDialog();
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

    /** The fact; "Pause or review" for those who may pause it or change the rule. */
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

    // ---------------------------------------------------------------- the dialog

    /** Open the dialog (the fact, the VM panel's Idle tab). */
    function openDialog() {
        if (!current) return;
        const name = current.env.displayName || current.env.name;
        const withRule = current.status.canAdminister && !current.status.production;
        const rule = envRule(current.status);
        Modals.show({
            id: DIALOG,
            title: name ? `Idle auto-stop · ${name}` : 'Idle auto-stop',
            // Two columns with the rule beside how it is going, as on the IdleStop board (U17-T10).
            size: withRule ? 'xl' : 'lg',
            body: '<div class="idle-dialog"></div>',
            // The rule's actions sit in the button strip, always in view (U18-T07).
            buttons: withRule ? [
                { text: 'Delete rule', class: 'btn-outline-danger me-auto', id: 'idleDeleteRule', action: 'idle-delete-rule', hidden: !rule },
                { text: 'Close', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Save rule', class: 'btn-primary', id: 'idleSaveRule', action: 'idle-save-rule' }
            ] : [{ text: 'Close', class: 'btn-outline-secondary', dismiss: true }]
        });
        renderDialog();
    }

    function renderDialog() {
        const $root = $(`#${DIALOG} .idle-dialog`);
        if (!$root.length || !current) return;
        const { status } = current;
        const rule = envRule(status);
        const head = headline(status, rule);
        const list = decisions(status);
        const canPause = status.canOperate && rule && rule.enabled && !status.production;
        let pause = '';
        if (status.snoozedUntil) {
            pause = Utils.html`<div class="idle-pause"><span id="idle-dialog-paused">Paused until ${time(status.snoozedUntil)}${status.snoozedByDisplayName ? ' by ' + status.snoozedByDisplayName : ''}</span>
                ${Utils.raw(status.canOperate ? '<button type="button" class="btn btn-sm btn-outline-secondary" data-action="idle-end-snooze">Resume now</button>' : '')}</div>`;
        } else if (canPause) {
            pause = `<div class="idle-pause" role="group" aria-label="Pause idle auto-stop"><span>Working late? Pause it for</span>${[2, 4, 8].map(h =>
                `<button type="button" class="btn btn-sm btn-outline-secondary" data-action="idle-snooze" data-hours="${h}">${h} hours</button>`).join('')}</div>`;
        }
        const withRule = status.canAdminister && !status.production;
        $('#idleDeleteRule').prop('hidden', !rule);
        $root.toggleClass('has-rule', withRule).html(Utils.html`
            <p class="dialog-lead">Stops the environment when all its VMs have been idle for a while, in reverse start order.</p>
            <div class="idle-cols">
            <section class="idle-going" aria-label="How it is going">
                <div class="idle-head"><b>${head.title}</b><span>${head.sub}</span></div>
                ${Utils.raw(summary(status, rule) ? Utils.html`<p class="idle-summary">${summary(status, rule)}</p>` : '')}
                <div class="idle-decisions"><div class="idle-label">Recent decisions</div>
                    ${Utils.raw(list.length ? list.map(d => Utils.html`<div class="idle-decision"><span class="mono">${d.time}</span>
                        <span>${d.text}</span><span class="idle-tag" data-tone="${d.tone}">${d.tag}</span></div>`).join('')
                        : '<p class="idle-empty">No decisions yet.</p>')}
                </div>
                ${Utils.raw(pause)}
                <p class="idle-note">Anyone who can start this environment can pause it. Production environments are never stopped.</p>
            </section>
            ${Utils.raw(withRule ? ruleForm(status, rule) : '')}
            </div>`);
    }

    function ruleForm(status, rule) {
        const groups = ((current.env && current.env.groups) || []).map(gd => gd.group || gd).filter(g => g && g.groupId);
        const allowed = rule ? enforceAllowed(status, rule) || rule.mode === 'ENFORCE' : false;
        const date = enforceDate(status, rule);
        const days = minDays(status);
        const enforceNote = !rule ? 'New rules start as a dry run'
            : allowed ? 'Stops the environment when all its VMs are idle'
                : `Available after ${days} days of dry run${date ? `, on ${date.getDate()} ${MONTHS[date.getMonth()]}` : ''}`;
        const mode = rule ? rule.mode : 'DRY_RUN';
        return Utils.html`
            <form class="idle-rule" aria-labelledby="idle-rule-title" novalidate>
                <div class="idle-rule-head"><h3 id="idle-rule-title">Rule <span class="idle-note-inline">· environment admins</span></h3>
                    <div class="form-check form-check-reverse m-0">
                        <input class="form-check-input" type="checkbox" id="idleEnabled" ${!rule || rule.enabled ? 'checked' : ''}>
                        <label class="form-check-label" for="idleEnabled">On</label>
                    </div></div>
                <label class="idle-field">Applies to
                    <select class="form-select" id="idleScope" ${rule ? 'disabled' : ''}>
                        <option value="">The whole environment</option>
                        ${Utils.raw(groups.map(g => Utils.html`<option value="${g.groupId}" ${rule && rule.groupId === g.groupId ? 'selected' : ''}>${g.displayName || g.name} group only</option>`).join(''))}
                    </select></label>
                <div class="idle-fields">
                    <label class="idle-field">Idle for (min)<input type="number" class="form-control" id="idleMinutes" min="30" max="1440" value="${rule ? rule.idleMinutes : 60}"></label>
                    <label class="idle-field">CPU below (%)<input type="number" class="form-control" id="idleCpu" min="0.5" max="20" step="0.5" value="${rule ? rule.cpuMaxPercent : 5}"></label>
                    <label class="idle-field">Network (MB/day)<input type="number" class="form-control" id="idleNetwork" min="0.1" max="1000" step="0.1" value="${rule ? rule.networkMbPerDay : 5}"></label>
                </div>
                <p class="idle-note">Defaults follow AWS Compute Optimizer's definition of idle.</p>
                <fieldset class="idle-mode"><legend>Mode</legend>
                    <label class="idle-mode-option"><input type="radio" name="idleMode" value="DRY_RUN" ${mode !== 'ENFORCE' ? 'checked' : ''}>
                        <span><b>Dry run</b><span>Record what would happen</span></span></label>
                    <label class="idle-mode-option"><input type="radio" name="idleMode" value="ENFORCE" ${mode === 'ENFORCE' ? 'checked' : ''} ${allowed ? '' : 'disabled'}>
                        <span><b>Stop idle VMs</b><span id="idle-enforce-note">${enforceNote}</span></span></label>
                </fieldset>
            </form>`;
    }

    function snooze(hours) {
        if (!current) return;
        ApiClient.post(Config.API.idleStop.snooze(current.env.environmentId), { hours: Number(hours) }, { suppressGlobalError: true })
            .done(function(result) {
                if (result && result.snoozedUntil) current.status.snoozedUntil = result.snoozedUntil;
                renderDialog();
                Notifications.success(`Idle auto-stop paused for ${hours} hours`);
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not pause idle auto-stop');
            });
    }

    function endSnooze() {
        if (!current) return;
        ApiClient.delete(Config.API.idleStop.snooze(current.env.environmentId), { suppressGlobalError: true })
            .done(function() {
                current.status.snoozedUntil = null;
                renderDialog();
                Notifications.success('Idle auto-stop resumed');
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not resume idle auto-stop');
            });
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
            body.mode = $(`#${DIALOG} input[name="idleMode"]:checked`).val() || rule.mode;
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
                Modals.hide(DIALOG);
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
                Modals.hide(DIALOG);
                Notifications.success('Idle auto-stop rule deleted');
                refresh();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Could not delete the rule');
            });
    }

    return { renderPanel, refresh, snooze, endSnooze, openDialog, openReview: openDialog, saveRule, deleteRule,
        model: { headline, summary, decisions, enforceAllowed, enforceDate, modeText } };
})();

if (typeof window !== 'undefined') window.IdleStop = IdleStop;

if (typeof Actions !== 'undefined') {
    Actions.registerAll({
        'idle-snooze': el => IdleStop.snooze(el.dataset.hours),
        'idle-end-snooze': () => IdleStop.endSnooze(),
        'idle-review': () => IdleStop.openDialog(),
        'idle-save-rule': () => IdleStop.saveRule(),
        'idle-delete-rule': () => IdleStop.deleteRule()
    });
}
