/**
 * VM Self-Service Platform - Operation progress by group (U08-T03, OperationProgress.dc.html)
 * Pure helpers for the progress dialog: a run's steps grouped into stages in the order they run
 * ("1st · Database  Done in 0:48", "2nd · Application  Starting", "3rd · Web  Waits for
 * Application"), one line per VM ("Running · 0:41", "Starting · 0:54", "Waiting", "Failed: <the
 * server's reason>"), the live line ("3 of 6 running · Application is starting; Web waits for it")
 * and the words for a finished run ("Done", "Done with problems: 1 failed"). No DOM here; tested
 * in src/test/js/operation-stages.test.mjs.
 */
const OperationStages = (function() {
    'use strict';

    const up = s => String(s || '').toUpperCase();
    const ORDINAL = ['1st', '2nd', '3rd'];

    function ordinal(n) {
        return ORDINAL[n - 1] || `${n}th`;
    }

    /** A timestamp as milliseconds: ISO strings and epoch numbers both arrive. */
    function ms(ts) {
        if (ts === null || ts === undefined || ts === '') return null;
        const t = typeof ts === 'number' ? ts : Date.parse(ts);
        return Number.isNaN(t) ? null : t;
    }

    /** "0:41", "12:05", "1:02:09". */
    function clock(millis) {
        const total = Math.max(0, Math.round(millis / 1000));
        const h = Math.floor(total / 3600), m = Math.floor(total / 60) % 60, s = total % 60;
        const ss = String(s).padStart(2, '0');
        return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
    }

    /** The words for the operation: Running / Starting for a start, Stopped / Stopping for a stop. */
    function words(operationType) {
        switch (up(operationType)) {
            case 'STOP': return { done: 'Stopped', busy: 'Stopping', count: 'stopped', verb: 'stopping' };
            case 'RESTART': return { done: 'Restarted', busy: 'Restarting', count: 'restarted', verb: 'restarting' };
            default: return { done: 'Running', busy: 'Starting', count: 'running', verb: 'starting' };
        }
    }

    /** One VM's line: {name, light: R | B | S | E | K, state}. */
    function vmLine(step, operationType, now = Date.now()) {
        const w = words(operationType);
        const status = up(step.status);
        const started = ms(step.startedAt), ended = ms(step.completedAt);
        const name = step.targetName || step.vmName || step.targetId || 'VM';
        switch (status) {
            case 'COMPLETED':
                return { name, light: 'R', state: started && ended ? `${w.done} · ${clock(ended - started)}` : w.done };
            case 'IN_PROGRESS':
                return { name, light: 'B', state: started ? `${w.busy} · ${clock(now - started)}` : w.busy };
            case 'FAILED':
                return { name, light: 'E', state: step.errorMessage ? `Failed: ${step.errorMessage}` : 'Failed' };
            case 'SKIPPED':
                return { name, light: 'K', state: step.errorMessage ? `Skipped: ${step.errorMessage}` : 'Skipped' };
            case 'CANCELLED':
                return { name, light: 'S', state: 'Cancelled' };
            default:
                return { name, light: 'S', state: 'Waiting' };
        }
    }

    /**
     * The run's steps as stages, in the order they run: steps are grouped by their VM group
     * (groupId, else groupName) in order of their first step. Each stage is
     * {order: "1st", name, tone: done | busy | wait | failed | skipped, tag, vms: [vmLine]}.
     */
    function stages(execution, now = Date.now()) {
        if (!execution) return [];
        const w = words(execution.operationType);
        const steps = (execution.details || []).slice()
            .sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        const byKey = new Map();
        steps.forEach(step => {
            const key = step.groupId || step.groupName || '';
            if (!byKey.has(key)) byKey.set(key, { name: step.groupName || (key ? key : 'VMs'), steps: [] });
            byKey.get(key).steps.push(step);
        });
        const list = [];
        byKey.forEach(group => {
            const counts = { COMPLETED: 0, IN_PROGRESS: 0, FAILED: 0, SKIPPED: 0, CANCELLED: 0, PENDING: 0 };
            group.steps.forEach(s => {
                const st = up(s.status);
                counts[st in counts ? st : 'PENDING']++;
            });
            const n = group.steps.length;
            const previous = list.length ? list[list.length - 1] : null;
            let tone, tag;
            if (counts.FAILED) {
                tone = 'failed';
                tag = `${counts.FAILED} failed`;
            } else if (counts.COMPLETED === n) {
                const starts = group.steps.map(s => ms(s.startedAt)).filter(t => t !== null);
                const ends = group.steps.map(s => ms(s.completedAt)).filter(t => t !== null);
                tone = 'done';
                tag = starts.length && ends.length ? `Done in ${clock(Math.max(...ends) - Math.min(...starts))}` : 'Done';
            } else if (counts.IN_PROGRESS || (counts.COMPLETED && counts.PENDING)) {
                tone = 'busy';
                tag = w.busy;
            } else if (counts.SKIPPED === n) {
                tone = 'skipped';
                tag = 'Skipped';
            } else if (counts.CANCELLED && !counts.PENDING) {
                tone = 'skipped';
                tag = 'Cancelled';
            } else if (counts.PENDING && previous && previous.tone !== 'done') {
                tone = 'wait';
                tag = `Waits for ${previous.name}`;
            } else {
                tone = 'wait';
                tag = 'Waiting';
            }
            list.push({ order: ordinal(list.length + 1), name: group.name, tone, tag,
                vms: group.steps.map(s => vmLine(s, execution.operationType, now)) });
        });
        return list;
    }

    /** VMs done and in all: from the steps, else the run's own totals. */
    function doneCount(execution) {
        if (!execution) return { done: 0, total: 0 };
        const steps = execution.details || [];
        if (steps.length) return { done: steps.filter(s => up(s.status) === 'COMPLETED').length, total: steps.length };
        return { done: Number(execution.completedTargets) || 0, total: Number(execution.totalTargets) || 0 };
    }

    /**
     * The live line under the bar: {done, total, word, text}, e.g. done 3, total 6, word "running",
     * text "Application is starting; Web waits for it" (empty when nothing is moving).
     */
    function liveLine(execution) {
        const w = words(execution && execution.operationType);
        const { done, total } = doneCount(execution);
        const list = stages(execution);
        const busy = list.find(s => s.tone === 'busy');
        const waiting = busy ? list.find(s => s.tone === 'wait' && s.tag === `Waits for ${busy.name}`) : null;
        let text = '';
        if (busy) text = `${busy.name} is ${w.verb}` + (waiting ? `; ${waiting.name} waits for it` : '');
        return { done, total, word: w.count, text };
    }

    /**
     * What a finished run says: "Done", "Done with problems: 1 failed, 1 skipped", "Failed: <reason>",
     * "Cancelled"; null while it runs.
     */
    function statusText(execution) {
        if (!execution) return null;
        const steps = execution.details || [];
        const failed = steps.length ? steps.filter(s => up(s.status) === 'FAILED').length : Number(execution.failedTargets) || 0;
        const skipped = steps.length ? steps.filter(s => up(s.status) === 'SKIPPED').length : Number(execution.skippedTargets) || 0;
        switch (up(execution.status)) {
            case 'COMPLETED': return 'Done';
            case 'PARTIAL_SUCCESS': {
                const parts = [];
                if (failed) parts.push(`${failed} failed`);
                if (skipped) parts.push(`${skipped} skipped`);
                return parts.length ? `Done with problems: ${parts.join(', ')}` : 'Done with problems';
            }
            case 'FAILED': return `Failed: ${execution.errorMessage || 'no VM finished'}`;
            case 'CANCELLED': return 'Cancelled';
            default: return null;
        }
    }

    return { stages, vmLine, liveLine, doneCount, statusText, clock, ordinal };
})();

if (typeof window !== 'undefined') window.OperationStages = OperationStages;
