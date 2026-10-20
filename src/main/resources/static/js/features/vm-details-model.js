/**
 * VM Self-Service Platform - VM details: idle hours and history sentences (U08-T07)
 * Pure helpers for the Idle and History tabs of the VM panel (VmDetails.dc.html): idle hours per
 * day over the last 7 days from hourly CPU samples, and one plain sentence per state change
 * ("Started · Priya Nair, as part of "Start 5 VMs"", "Stopped · Schedule "Office hours"", "State
 * changed outside the platform (stopped in the AWS console) · Found by the 11:00 sync"). No DOM;
 * tested in src/test/js/vm-details-model.test.mjs. Never shows instance IDs: the server's text
 * is not used, only statuses, names and times.
 */
const VmDetailsModel = (function() {
    'use strict';

    const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const pad = n => String(n).padStart(2, '0');

    function toDate(ts) {
        if (ts === null || ts === undefined || ts === '') return null;
        const d = new Date(ts);
        return Number.isNaN(d.getTime()) ? null : d;
    }

    const dayKey = d => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;

    /**
     * Idle hours per day for the last 7 days, oldest first: each hourly sample under the CPU
     * threshold counts one idle hour on its local day. [{label: 'Mon', hours, sampled, today}].
     */
    function idleHoursPerDay(samples, threshold = 5, now = new Date()) {
        const days = [];
        for (let i = 6; i >= 0; i--) {
            const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
            days.push({ key: dayKey(d), label: DAYS[d.getDay()], hours: 0, sampled: 0, today: i === 0 });
        }
        const byKey = new Map(days.map(d => [d.key, d]));
        (samples || []).forEach(s => {
            const t = toDate(s.sampleTime);
            const cpu = s.cpuUtilization === null || s.cpuUtilization === undefined ? null : Number(s.cpuUtilization);
            if (!t || cpu === null || !Number.isFinite(cpu)) return;
            const day = byKey.get(dayKey(t));
            if (!day) return;
            day.sampled++;
            if (cpu < threshold) day.hours++;
        });
        return days.map(({ label, hours, sampled, today }) => ({ label, hours: Math.min(24, hours), sampled, today }));
    }

    /** "Idle hours per day: 12, 11, 12, 11, 12, 24, 24" for the chart's label. */
    function idleLabel(days) {
        return `Idle hours per day, last 7 days: ${days.map(d => d.hours).join(', ')}`;
    }

    /** "Today 08:05", "Yesterday 19:00", "Fri 19:00" within the week, else "14 Sep". */
    function when(ts, now = new Date()) {
        const d = toDate(ts);
        if (!d) return '—';
        const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        if (d.getTime() >= start) return `Today ${clock}`;
        if (d.getTime() >= start - 86400000) return `Yesterday ${clock}`;
        if (d.getTime() >= start - 6 * 86400000) return `${DAYS[d.getDay()]} ${clock}`;
        return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
    }

    const DONE = { RUNNING: 'Started', STOPPED: 'Stopped', STARTING: 'Starting', STOPPING: 'Stopping', ERROR: 'Failed',
        TERMINATED: 'Terminated', NOT_FOUND: 'Not found in AWS' };
    const CONSOLE = { RUNNING: 'started', STOPPED: 'stopped', TERMINATED: 'terminated' };
    const TONE = { RUNNING: 'green', STOPPED: 'grey', ERROR: 'red', TERMINATED: 'red', NOT_FOUND: 'red' };

    /** Who or what made a change through a run: the schedule, the lease, or the person and the run. */
    function byRun(item) {
        const name = item.triggerName;
        switch (String(item.triggerSource || '').toUpperCase()) {
            case 'SCHEDULE': return name ? `Schedule "${name}"` : 'A schedule';
            case 'ACCESS_AUTOMATION': return name ? `Rule "${name}"` : 'An automation rule';
            case 'LEASE': return 'The lease ended';
            case 'IDLE_STOP': return 'Idle auto-stop';
            case 'BUDGET': return 'The budget';
            case 'SYSTEM': return 'The platform';
            default: {
                const person = item.changedByUsername || 'Someone';
                const n = Number(item.operationTargets) || 0;
                if (n > 1) {
                    const verb = String(item.operationType || '').toUpperCase() === 'STOP' ? 'Stop' : 'Start';
                    return `${person}, as part of "${verb} ${n} VMs"`;
                }
                return person;
            }
        }
    }

    /**
     * One history line: {time, text, by, tone}. A change found by the sync is a change outside the
     * platform; AWS's own starts and stops (Spot, RDS) say so.
     */
    function historyText(item, now = new Date()) {
        const status = String(item.newStatus || '').toUpperCase();
        const time = when(item.changedAt, now);
        const source = String(item.changeSource || '').toLowerCase();
        if (item.drift || source === 'state_sync') {
            const changed = toDate(item.changedAt);
            const how = CONSOLE[status] ? `${CONSOLE[status]} in the AWS console` : `now ${String(DONE[status] || status).toLowerCase()}`;
            return { time, text: `State changed outside the platform (${how})`,
                by: changed ? `Found by the ${pad(changed.getHours())}:${pad(changed.getMinutes())} sync` : 'Found by the sync', tone: 'gold' };
        }
        const text = DONE[status] || 'Changed';
        if (source === 'spot_interruption') return { time, text: 'Stopped by AWS', by: 'The Spot capacity was taken back', tone: 'red' };
        if (source === 'rds_auto_start') return { time, text: 'Started by AWS', by: 'AWS starts a stopped database after 7 days', tone: 'gold' };
        if (source === 'rds_keep_stopped') return { time, text, by: 'Kept stopped by the platform', tone: TONE[status] || 'grey' };
        if (source === 'operation' || item.operationId) return { time, text, by: byRun(item), tone: TONE[status] || 'grey' };
        return { time, text, by: item.changedByUsername || '', tone: TONE[status] || 'grey' };
    }

    /**
     * "Idle auto-stop for this environment: dry run, after 60 min idle" from the idle-stop status,
     * using the rule for the VM's group when there is one; "off" without a rule.
     */
    function idleStopLine(status, groupId) {
        if (!status || status.featureEnabled === false) return 'Idle auto-stop is off on this platform';
        const rules = (status.rules || []).filter(r => r.enabled !== false);
        const rule = rules.find(r => r.groupId && r.groupId === groupId) || rules.find(r => !r.groupId);
        if (!rule) return 'Idle auto-stop for this environment: off';
        const mode = String(rule.mode || '').toUpperCase() === 'ENFORCE' ? 'on' : 'dry run';
        const scope = rule.groupId ? `the ${rule.groupName || 'VM\'s'} group` : 'this environment';
        return `Idle auto-stop for ${scope}: ${mode}, after ${rule.idleMinutes} min idle`;
    }

    return { idleHoursPerDay, idleLabel, when, historyText, idleStopLine };
})();

if (typeof window !== 'undefined') window.VmDetailsModel = VmDetailsModel;
