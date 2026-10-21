/**
 * VM Self-Service Platform - Home model (U06-T02)
 * The words and order of Home's environment cards and "Needs you" tiles, without any DOM, so the
 * rules are tested on their own (src/test/js/home-model.test.mjs). Cards come from
 * GET /api/v1/dashboard/summary (U06-T01).
 */
const HomeModel = (function() {
    'use strict';

    const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    const pad = n => String(n).padStart(2, '0');
    const n = v => Number(v) || 0;
    const plural = (count, one, many) => `${count} ${count === 1 ? one : (many || one + 's')}`;

    function toDate(value) {
        if (!value) return null;
        const d = value instanceof Date ? value : new Date(value);
        return Number.isNaN(d.getTime()) ? null : d;
    }

    function sameDay(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }

    /** "20:00" today, "Mon 08:00" on another day. */
    function when(value, now) {
        const d = toDate(value);
        if (!d) return '';
        const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        return sameDay(d, now) ? time : `${DAYS[d.getDay()]} ${time}`;
    }

    /** "22 Oct". */
    function day(value) {
        const d = toDate(value);
        return d ? `${d.getDate()} ${MONTHS[d.getMonth()]}` : '';
    }

    /** "2 h 15 min", "40 min", "less than a minute". */
    function inTime(value, now) {
        const d = toDate(value);
        if (!d) return '';
        const minutes = Math.max(0, Math.round((d.getTime() - now.getTime()) / 60000));
        if (minutes < 1) return 'less than a minute';
        const h = Math.floor(minutes / 60);
        const m = minutes % 60;
        if (!h) return `${m} min`;
        return m ? `${h} h ${m} min` : `${h} h`;
    }

    function running(card) { return n(card.runningVms); }
    function total(card) { return n(card.totalVms); }
    function transitional(card) { return n(card.transitionalVms); }
    function starting(card) { return n(card.startingVms); }
    function failed(card) { return n(card.failedVms); }

    /** The state chip: {label, kind} with kind running | waiting | failed | stopped (chip-* classes). */
    function chip(card) {
        if (transitional(card) > 0) {
            return starting(card) > 0 ? { label: 'Starting', kind: 'waiting' } : { label: 'Stopping', kind: 'waiting' };
        }
        if (failed(card) > 0) return { label: 'Problems', kind: 'failed' };
        if (running(card) > 0) return { label: 'Running', kind: 'running' };
        return { label: 'Stopped', kind: 'stopped' };
    }

    /** The one group that is fully stopped while others run, if exactly one. */
    function onlyStoppedGroup(card) {
        const groups = Array.isArray(card.groupStates) ? card.groupStates : [];
        const stopped = groups.filter(g => n(g.total) > 0 && n(g.running) === 0);
        return stopped.length === 1 && groups.some(g => n(g.running) > 0) ? stopped[0] : null;
    }

    /** The earlier of the lease end and the next scheduled stop. */
    function nextStopAt(card) {
        const times = [card.leaseEndsAt, card.nextStop].map(toDate).filter(Boolean);
        return times.length ? new Date(Math.min(...times.map(t => t.getTime()))) : null;
    }

    /**
     * The card's status line: at most two parts joined with " · ". The first says what is
     * happening (starting, failed, a stopped group, how many run, when it starts); then the lock;
     * then when it stops.
     */
    function cardLine(card, now = new Date()) {
        let primary = '';
        let time = '';
        const run = running(card);
        const all = total(card);
        if (transitional(card) > 0) {
            const s = starting(card);
            const stopping = transitional(card) - s;
            primary = s > 0 ? `Starting ${plural(s, 'VM')}` : `Stopping ${plural(stopping, 'VM')}`;
        } else if (failed(card) > 0) {
            primary = `${failed(card)} failed to start`;
        } else if (run > 0 && run < all) {
            const group = onlyStoppedGroup(card);
            primary = group ? `${group.name} group stopped` : `${run} of ${all} running`;
        } else if (run === 0 && all > 0 && card.nextStart) {
            primary = 'Stopped';
            time = `starts ${when(card.nextStart, now)}`;
        }
        if (run > 0 && !time) {
            const stop = nextStopAt(card);
            if (stop) time = `stops at ${when(stop, now)}`;
        }
        let lock = '';
        if (card.lockedByMe) {
            lock = card.lockExpiresAt ? `locked by you until ${when(card.lockExpiresAt, now)}` : 'locked by you';
        } else if (card.lockedByName) {
            lock = `Locked by ${card.lockedByName}`;
        } else if (card.locked) {
            lock = 'Locked';
        }
        const parts = [primary, lock, time].filter(Boolean).slice(0, 2);
        if (!parts.length) return all === 0 ? 'No VMs yet' : (run === all ? 'All running' : 'Stopped');
        const line = parts.join(' · ');
        return line.charAt(0).toUpperCase() + line.slice(1);
    }

    /** The viewer's access in words. */
    function accessLabel(level) {
        switch (level) {
            case 'ADMIN': return 'Admin';
            case 'USER': return 'Operator';
            case 'VIEWER': return 'View only';
            default: return 'Some groups';
        }
    }

    /**
     * The cards Home shows: locked by me first, then those running or changing, then the rest by
     * name; at most {@code max}.
     */
    function pickCards(cards, max = 4) {
        const rank = c => (c.lockedByMe ? 0 : (running(c) > 0 || transitional(c) > 0) ? 1 : 2);
        const name = c => String(c.displayName || c.name || '').toLowerCase();
        return (Array.isArray(cards) ? cards.slice() : [])
            .sort((a, b) => rank(a) - rank(b) || name(a).localeCompare(name(b)))
            .slice(0, max);
    }

    /**
     * A "Needs you" tile: {kind, environment, before, at, after, action, href | extend}. The screen
     * puts the environment in bold and the time in mono.
     */
    function needsYouItem(item, now = new Date()) {
        if (item.kind === 'LEASE_ENDING') {
            return {
                kind: item.kind,
                environment: item.environmentName || 'An environment',
                before: '',
                middle: ' stops at ',
                at: when(item.at, now),
                after: `, in ${inTime(item.at, now)}`,
                action: 'Extend lease',
                href: `#/environments/${encodeURIComponent(item.environmentId)}?extendLease=1`,
            };
        }
        if (item.kind === 'ACCESS_ENDING') {
            return {
                kind: item.kind,
                environment: item.environmentName || 'an environment',
                before: 'Your access to ',
                middle: ' ends ',
                at: day(item.at),
                after: '',
                action: 'Ask for more time',
                extend: item.referenceId,
            };
        }
        return null;
    }

    /**
     * The phone Home's gold row (U16-T02, PhoneHome.dc.html): the lease ending soonest within 4 hours, as
     * {environment, at, href}; null when none does.
     */
    function phoneAlert(items, now = new Date()) {
        const soon = (Array.isArray(items) ? items : [])
            .filter(i => i && i.kind === 'LEASE_ENDING' && toDate(i.at))
            .map(i => ({ item: i, t: toDate(i.at).getTime() }))
            .filter(x => x.t >= now.getTime() && x.t - now.getTime() <= 4 * 3600000)
            .sort((a, b) => a.t - b.t)[0];
        if (!soon) return null;
        return {
            environment: soon.item.environmentName || 'An environment',
            at: when(soon.item.at, now),
            href: `#/environments/${encodeURIComponent(soon.item.environmentId)}?extendLease=1`,
        };
    }

    /** The header line's numbers: running and total VMs and environments across the cards. */
    function totals(cards) {
        const list = Array.isArray(cards) ? cards : [];
        return {
            running: list.reduce((s, c) => s + running(c), 0),
            total: list.reduce((s, c) => s + total(c), 0),
            environments: list.length,
        };
    }

    // ---------------------------------------------------------------- Your recent activity (U06-T03)

    const LEVEL_WORDS = { ADMIN: 'Admin', USER: 'Operator', VIEWER: 'View only' };

    /** One activity line: "Started Payments Dev (5 VMs)", "Asked for Operator access to CRM UAT". */
    function activityText(item) {
        const env = item.environmentName || 'an environment';
        const where = item.scopeName ? `${item.scopeName} in ${env}` : env;
        const level = LEVEL_WORDS[item.accessLevel] ? `${LEVEL_WORDS[item.accessLevel]} ` : '';
        if (item.kind === 'OPERATION') {
            const verb = { START: 'Started', STOP: 'Stopped', RESTART: 'Restarted' }[item.event] || 'Changed';
            const count = n(item.totalTargets);
            return count ? `${verb} ${env} (${plural(count, 'VM')})` : `${verb} ${env}`;
        }
        switch (item.event) {
            case 'REQUESTED': return `Asked for ${level}access to ${where}`;
            case 'APPROVED': return `Access to ${where} approved`;
            case 'DENIED': return `Request for ${where} declined`;
            case 'CANCELLED': return `Request for ${where} cancelled`;
            case 'EXTENDED': return `Access to ${where} extended`;
            case 'GRANTED': return `Given ${level}access to ${where}`;
            case 'UPDATED': return `Access to ${where} changed to ${level.trim() || 'a new level'}`;
            case 'REVOKED': return `Access to ${where} removed`;
            case 'EXPIRED': return `Access to ${where} ended`;
            default: return `Access to ${where} changed`;
        }
    }

    /** The result column: {label, kind} with kind done | failed | waiting, or null when there is none. */
    function resultLabel(item) {
        if (item.kind === 'OPERATION') {
            switch (item.status) {
                case 'COMPLETED': return { label: 'Done', kind: 'done' };
                case 'FAILED': return { label: 'Failed', kind: 'failed' };
                case 'PARTIAL_SUCCESS': return { label: 'Partly done', kind: 'failed' };
                case 'CANCELLED': return { label: 'Cancelled', kind: 'waiting' };
                default: return { label: 'In progress', kind: 'waiting' };
            }
        }
        if (item.event === 'DENIED') return { label: 'Declined', kind: 'failed' };
        if (item.event === 'REQUESTED') return { label: 'Waiting', kind: 'waiting' };
        return null;
    }

    /** "08:02" today, "Fri" within the past week, else "16 Oct". */
    function activityTime(value, now = new Date()) {
        const d = toDate(value);
        if (!d) return '';
        if (sameDay(d, now)) return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        const days = (now.getTime() - d.getTime()) / 86400000;
        return days < 7 ? DAYS[d.getDay()] : day(d);
    }

    /** Running VMs that are barely used, the busiest-wasting first (longest idle), at most max. */
    function idleRows(idleVms, max = 2) {
        return (Array.isArray(idleVms) ? idleVms : [])
            .filter(vm => vm.status === 'RUNNING')
            .sort((a, b) => n(b.idleDurationMinutes) - n(a.idleDurationMinutes))
            .slice(0, max);
    }

    return { chip, cardLine, accessLabel, pickCards, needsYouItem, phoneAlert, totals, when, day, inTime,
        activityText, resultLabel, activityTime, idleRows };
})();

if (typeof window !== 'undefined') window.HomeModel = HomeModel;
