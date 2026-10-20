/**
 * VM Self-Service Platform - Access level wording (U10-T03)
 * One vocabulary for access everywhere (GrantAccess.dc.html): View only / Operator / Admin, what
 * each lets a person do, the chip class, and when a grant ends.
 *
 *   AccessLevels.label('USER')            'Operator'
 *   AccessLevels.description('USER')      'Start, stop and lock'
 *   AccessLevels.chipClass('VIEWER')      'level-viewer'
 *   AccessLevels.endsText(expiresAt, now) {text: 'No end' | '20 Oct · 1 day' | '16 Jan 2027', soon, ended}
 *   AccessLevels.durationOptions()        [{value: 7, label: '7 days'}, …, {value: 'none'}, {value: 'date'}]
 */
const AccessLevels = (function() {
    'use strict';

    const LEVELS = {
        VIEWER: { label: 'View only', description: 'See state, cost, history', chip: 'level-viewer' },
        USER: { label: 'Operator', description: 'Start, stop and lock', chip: 'level-user' },
        ADMIN: { label: 'Admin', description: 'Also grant access and edit', chip: 'level-admin' }
    };
    const ORDER = ['VIEWER', 'USER', 'ADMIN'];
    const DAY_MS = 86400000;
    const SOON_DAYS = 7;

    function key(level) {
        return String(level || '').toUpperCase();
    }

    /** 'View only', 'Operator', 'Admin'; an unknown level is shown as given. */
    function label(level) {
        const l = LEVELS[key(level)];
        return l ? l.label : String(level || '');
    }

    function description(level) {
        const l = LEVELS[key(level)];
        return l ? l.description : '';
    }

    function chipClass(level) {
        const l = LEVELS[key(level)];
        return l ? l.chip : 'level-user';
    }

    function startOfDay(d) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    }

    /**
     * When a grant ends: 'No end'; within 7 days '20 Oct · 1 day' (soon); later '16 Jan 2027'
     * (the year only when it is not this one: '16 Jan'); in the past 'Ended 3 Oct' (ended).
     */
    function endsText(expiresAt, now) {
        if (!expiresAt) return { text: 'No end', soon: false, ended: false };
        const end = new Date(expiresAt);
        const n = now || new Date();
        const sameYear = end.getFullYear() === n.getFullYear();
        const day = end.toLocaleDateString('en-GB', sameYear ? { day: 'numeric', month: 'short' }
            : { day: 'numeric', month: 'short', year: 'numeric' });
        if (end <= n) return { text: `Ended ${day}`, soon: false, ended: true };
        const days = Math.round((startOfDay(end) - startOfDay(n)) / DAY_MS);
        if (days <= SOON_DAYS) {
            const left = days <= 0 ? 'today' : `${days} day${days === 1 ? '' : 's'}`;
            return { text: `${day} · ${left}`, soon: true, ended: false };
        }
        return { text: day, soon: false, ended: false };
    }

    /** The durations a grant can be given for. */
    function durationOptions() {
        return [
            { value: 7, label: '7 days' },
            { value: 30, label: '30 days' },
            { value: 90, label: '90 days' },
            { value: 'none', label: 'No end' },
            { value: 'date', label: 'Until a date' }
        ];
    }

    /** True when {@code a} gives at least what {@code b} gives. */
    function atLeast(a, b) {
        return ORDER.indexOf(key(a)) >= ORDER.indexOf(key(b));
    }

    return { label, description, chipClass, endsText, durationOptions, atLeast, LEVELS: ORDER };
})();

if (typeof window !== 'undefined') window.AccessLevels = AccessLevels;
