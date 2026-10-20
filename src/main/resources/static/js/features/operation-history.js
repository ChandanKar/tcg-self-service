/**
 * VM Self-Service Platform - Operation history panel (U08-T04, OperationHistory.dc.html)
 * "History · Analytics QA": the environment's starts and stops newest first, 7 to a page, each a
 * row button (when, what, who, how long, result) that expands to its steps by group with the
 * failure reason and "Retry report-worker" for a VM that failed. Who names the person, or the
 * schedule ('Schedule "Nightly batch"'), lease, idle auto-stop or budget that started it. A run
 * still going can be cancelled from here. The words are pure (OperationHistory.model), tested in
 * src/test/js/operation-history.test.mjs.
 */
const OperationHistory = (function() {
    'use strict';

    const PAGE_SIZE = 7;
    const PANEL = '#dynamicSlideoutPanel[data-variant="op-history"]';
    const RUNNING = ['PENDING', 'IN_PROGRESS'];
    const up = s => String(s || '').toUpperCase();
    const pad = n => String(n).padStart(2, '0');

    function ms(ts) {
        if (ts === null || ts === undefined || ts === '') return null;
        const t = typeof ts === 'number' ? ts : Date.parse(ts);
        return Number.isNaN(t) ? null : t;
    }

    /** "Today 06:30", "Fri 14:22" within the week, else "12 Oct 09:15"; 24-hour like the rest of the app. */
    function whenText(ts, now = new Date()) {
        const t = ms(ts);
        if (t === null) return '—';
        const d = new Date(t);
        const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        if (t >= startOfToday) return `Today ${clock}`;
        if (t >= startOfToday - 86400000) return `Yesterday ${clock}`;
        if (t >= startOfToday - 6 * 86400000) {
            return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${clock}`;
        }
        return `${d.getDate()} ${d.toLocaleDateString('en-GB', { month: 'short' })} ${clock}`;
    }

    function verb(operationType) {
        const t = up(operationType);
        return t === 'STOP' ? 'Stop' : t === 'RESTART' ? 'Restart' : 'Start';
    }

    /** "Start, whole environment", "Start report-worker", "Stop Application group". */
    function whatText(run) {
        const v = verb(run.operationType);
        if (!run.scopeLabel) return v;
        return run.scopeLabel === 'whole environment' ? `${v}, whole environment` : `${v} ${run.scopeLabel}`;
    }

    /** The person, or what started it for them. */
    function whoText(run) {
        const name = run.triggerName;
        switch (up(run.triggerSource)) {
            case 'SCHEDULE': return name ? `Schedule "${name}"` : 'Schedule';
            case 'ACCESS_AUTOMATION': return name ? `Rule "${name}"` : 'Automation rule';
            case 'LEASE': return 'Lease';
            case 'IDLE_STOP': return 'Idle auto-stop';
            case 'BUDGET': return 'Budget';
            case 'SYSTEM': return 'Platform';
            default: return run.initiatedByDisplayName || run.initiatedByUserId || 'Someone';
        }
    }

    /** "3:58"; "—" while running or without times. */
    function tookText(run) {
        const a = ms(run.startedAt), b = ms(run.completedAt);
        if (a === null || b === null || RUNNING.includes(up(run.status))) return '—';
        const total = Math.max(0, Math.round((b - a) / 1000));
        const h = Math.floor(total / 3600), m = Math.floor(total / 60) % 60, s = total % 60;
        return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
    }

    /** The result chip: {text, tone: ok | part | fail | run | skip}. */
    function resultChip(run) {
        const failed = Number(run.failedTargets) || 0;
        const skipped = Number(run.skippedTargets) || 0;
        switch (up(run.status)) {
            case 'COMPLETED': return { text: 'Done', tone: 'ok' };
            case 'PARTIAL_SUCCESS':
                return failed ? { text: `${failed} failed`, tone: 'part' }
                    : { text: skipped ? `${skipped} skipped` : 'Done with problems', tone: 'part' };
            case 'FAILED': return { text: 'Failed', tone: 'fail' };
            case 'CANCELLED': return { text: 'Cancelled', tone: 'skip' };
            default: return { text: 'Running', tone: 'run' };
        }
    }

    const STEP_RESULT = {
        COMPLETED: ['Done', 'ok'], FAILED: ['Failed', 'part'], SKIPPED: ['Skipped', 'skip'],
        CANCELLED: ['Cancelled', 'skip'], IN_PROGRESS: ['Running', 'run'], PENDING: ['Not run', 'skip']
    };

    /**
     * A run's steps as lines {group, vm, note, result, tone, retry?}: per group in run order; a
     * group whose VMs all ended the same way without a note is one line ("2 VMs"). A failed VM
     * step carries retry {vmId, name}. Without steps, one "Whole run" line says what happened.
     */
    function stepLines(run) {
        const steps = (run.details || []).slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        if (!steps.length) {
            const chip = resultChip(run);
            return [{ group: 'Whole run', vm: run.errorMessage || (chip.text === 'Running' ? 'Getting ready' : chip.text),
                note: '', result: chip.text, tone: chip.tone }];
        }
        const groups = new Map();
        steps.forEach(s => {
            const key = s.groupId || s.groupName || '';
            if (!groups.has(key)) groups.set(key, { name: s.groupName || 'VMs', steps: [] });
            groups.get(key).steps.push(s);
        });
        const lines = [];
        groups.forEach(g => {
            const statuses = new Set(g.steps.map(s => up(s.status) || 'PENDING'));
            const quiet = g.steps.every(s => !s.errorMessage);
            if (g.steps.length > 1 && statuses.size === 1 && quiet) {
                const [text, tone] = STEP_RESULT[[...statuses][0]] || STEP_RESULT.PENDING;
                lines.push({ group: g.name, vm: `${g.steps.length} VMs`, note: '', result: text, tone });
                return;
            }
            g.steps.forEach(s => {
                const status = up(s.status) || 'PENDING';
                const [text, tone] = STEP_RESULT[status] || STEP_RESULT.PENDING;
                const name = s.targetName || s.targetId || 'VM';
                const line = { group: g.name, vm: name, note: s.errorMessage || '', result: text, tone };
                if (status === 'FAILED' && String(s.targetType || 'vm').toLowerCase() === 'vm' && s.targetId) {
                    line.retry = { vmId: s.targetId, name };
                }
                lines.push(line);
            });
        });
        return lines;
    }

    /** "1–7 of 23" for a zero-based page. */
    function rangeText(page, size, total) {
        if (!total) return '0 of 0';
        const from = page * size + 1;
        return `${from}–${Math.min(total, from + size - 1)} of ${total}`;
    }

    // ---------------------------------------------------------------- the panel

    let state = null;   // {env, page, data, open: {id: true}, runs: {id: dto}, token}

    /** Open the panel for an environment ({environmentId, displayName|name, groups?}). */
    function open(env) {
        state = { env, page: 0, data: null, open: {}, runs: {}, token: Date.now() + Math.random() };
        Slideout.open(`History · ${env.displayName || env.name || 'environment'}`,
            `<div class="op-history">
                <p class="op-history-sub">Starts and stops, newest first</p>
                <div class="op-history-list" id="op-history-list" aria-live="polite">
                    <div class="op-history-empty">Loading…</div></div>
            </div>`,
            { variant: 'op-history', width: 760, footer: '<div class="op-history-pager" id="op-history-pager"></div>' });
        load(0);
    }

    function load(page) {
        if (!state) return;
        const token = state.token;
        ApiClient.get(Config.API.operations.page(state.env.environmentId, page, PAGE_SIZE), { suppressGlobalError: true })
            .done(data => {
                if (!state || state.token !== token) return;
                state.page = page;
                state.data = data || { content: [] };
                render();
            })
            .fail(() => {
                if (!state || state.token !== token) return;
                $('#op-history-list').html(`<div class="op-history-empty" role="alert">Couldn't load the history.
                    <button type="button" class="btn btn-link btn-sm p-0 align-baseline" data-oh-retry-load>Try again</button></div>`);
            });
    }

    function render() {
        const runs = (state.data && state.data.content) || [];
        const meta = (state.data && state.data.page) || {};
        const total = meta.totalElements ?? runs.length;
        if (!runs.length) {
            $('#op-history-list').html('<div class="op-history-empty">No starts or stops yet.</div>');
        } else {
            $('#op-history-list').html(runs.map(runHtml).join(''));
        }
        const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);
        $('#op-history-pager').html(Utils.html`<span>${rangeText(state.page, PAGE_SIZE, total)}</span>
            <span class="op-history-pager-buttons">
                <button type="button" class="btn btn-sm btn-outline-secondary" data-oh-page="${state.page - 1}" ${state.page <= 0 ? 'disabled' : ''}>Previous</button>
                <button type="button" class="btn btn-sm btn-outline-secondary" data-oh-page="${state.page + 1}" ${state.page >= lastPage ? 'disabled' : ''}>Next</button>
            </span>`);
    }

    function runHtml(run) {
        const id = run.executionId;
        const isOpen = !!state.open[id];
        const chip = resultChip(run);
        const running = RUNNING.includes(up(run.status));
        return Utils.html`<div class="op-history-run${isOpen ? ' open' : ''}" data-execution-id="${id}">
            <button type="button" class="op-history-row" aria-expanded="${String(isOpen)}" aria-controls="op-history-steps-${id}" data-oh-toggle="${id}">
                <span class="op-history-when mono">${whenText(run.startedAt)}</span>
                <span class="op-history-what">${whatText(run)}</span>
                <span class="op-history-who">${whoText(run)}</span>
                <span class="op-history-took mono">${tookText(run)}</span>
                <span class="op-history-chip" data-tone="${chip.tone}" data-col="result">${chip.text}</span>
            </button>
            ${Utils.raw(running ? Utils.html`<div class="op-history-actions"><button type="button" class="btn btn-link btn-sm op-history-cancel"
                data-op-cancel data-execution-id="${id}">Cancel the rest</button></div>` : '')}
            ${Utils.raw(isOpen ? Utils.html`<div class="op-history-steps" id="op-history-steps-${id}">${Utils.raw(stepsHtml(id))}</div>` : '')}
        </div>`;
    }

    function stepsHtml(id) {
        const run = state.runs[id];
        if (run === undefined) return '<div class="op-history-empty">Loading steps…</div>';
        if (run === null) return '<div class="op-history-empty" role="alert">Couldn\'t load the steps.</div>';
        const lines = stepLines(run);
        const retries = lines.filter(l => l.retry);
        return lines.map(l => Utils.html`<div class="op-history-step">
                <span class="op-history-step-group">${l.group}</span>
                <span>${l.vm}${Utils.raw(l.note ? Utils.html`<span class="op-history-step-note">${l.note}</span>` : '')}</span>
                <span class="op-history-chip" data-tone="${l.tone}">${l.result}</span>
            </div>`).join('')
            + (retries.length ? `<div class="op-history-retry">${retries.map(l => Utils.html`<button type="button" class="btn btn-link btn-sm p-0"
                data-oh-retry="${l.retry.vmId}" data-oh-retry-name="${l.retry.name}" data-oh-retry-type="${up(run.operationType) === 'STOP' ? 'STOP' : 'START'}">Retry ${l.retry.name}</button>`).join('')}</div>` : '');
    }

    function toggle(id) {
        if (!state) return;
        state.open[id] = !state.open[id];
        if (state.open[id] && !(id in state.runs)) {
            const token = state.token;
            ApiClient.get(Config.API.operations.get(state.env.environmentId, id), { suppressGlobalError: true })
                .done(run => { if (state && state.token === token) { state.runs[id] = run; render(); } })
                .fail(() => { if (state && state.token === token) { state.runs[id] = null; render(); } });
        }
        render();
        $(`${PANEL} [data-oh-toggle="${CSS.escape(id)}"]`).trigger('focus');
    }

    /** Retry one VM: the panel closes and the start (or stop) dialog opens for that VM. */
    function retry(vmId, name, opType) {
        if (!state) return;
        const env = state.env;
        const envId = env.environmentId;
        Slideout.close();
        OperationConfirm.open({
            env: env.groups ? env : Object.assign({ groups: [] }, env),
            opType,
            scope: { level: 'vm', vmId, name },
            onConfirm: minutes => opType === 'STOP' ? VmOperations.stopVm(envId, vmId, name)
                : VmOperations.startVm(envId, vmId, name, minutes)
        });
    }

    if (typeof document !== 'undefined' && typeof $ !== 'undefined') {
        $(document)
            .on('click', `${PANEL} [data-oh-toggle]`, function() { toggle(this.getAttribute('data-oh-toggle')); })
            .on('click', `${PANEL} [data-oh-page]`, function() { load(Number(this.getAttribute('data-oh-page'))); })
            .on('click', `${PANEL} [data-oh-retry-load]`, () => state && load(state.page))
            .on('click', `${PANEL} [data-oh-retry]`, function() {
                retry(this.getAttribute('data-oh-retry'), this.getAttribute('data-oh-retry-name'), this.getAttribute('data-oh-retry-type'));
            })
            .on('click', `${PANEL} [data-op-cancel]`, function() {
                if (!state) return;
                const page = state.page;
                VmOperations.confirmCancel(state.env.environmentId, this.getAttribute('data-execution-id'), $(this), () => load(page));
            });
    }

    return { open, model: { whenText, whatText, whoText, tookText, resultChip, stepLines, rangeText } };
})();

if (typeof window !== 'undefined') window.OperationHistory = OperationHistory;
