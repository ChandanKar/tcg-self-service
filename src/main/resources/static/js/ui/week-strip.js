/**
 * VM Self-Service Platform - Week strip (U12-T02, AutomationRules.dc.html)
 * A schedule drawn as Mon–Sun rows on a 0–24 h scale: a bar from each start to its stop, in the
 * viewer's time. A start without a stop runs to the end of the day; a stop without a start draws
 * a short mark. A day whose last run was skipped is drawn in gold.
 *
 *   WeekStrip.bars(windows, ruleTz, viewerTz, at)  -> [7 × [{from, to, kind}]] (hours, Mon first)
 *   WeekStrip.html(days, {label, skippedDay, off})  -> markup with an aria-label
 */
const WeekStrip = (function() {
    'use strict';

    const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
    const LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const MARK = 0.35; // hours: the width of a stop-only (or start-only) mark

    function hours(hhmm) {
        if (!hhmm) return null;
        const [h, m] = String(hhmm).split(':').map(Number);
        return h + (m || 0) / 60;
    }

    /** Minutes a time zone is ahead of UTC at a moment (Intl; 0 when unknown). */
    function offsetMinutes(tz, at) {
        if (!tz) return 0;
        try {
            const parts = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit',
                day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(at || new Date());
            const get = t => Number(parts.find(p => p.type === t).value);
            const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'));
            const d = at || new Date();
            return Math.round((asUtc - Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes())) / 60000);
        } catch (e) {
            return 0;
        }
    }

    /** Hours to add to a time in {@code ruleTz} to read it in {@code viewerTz}. */
    function shiftHours(ruleTz, viewerTz, at) {
        if (!ruleTz || !viewerTz || ruleTz === viewerTz) return 0;
        return (offsetMinutes(viewerTz, at) - offsetMinutes(ruleTz, at)) / 60;
    }

    /** Puts [from, to) of day d (hours, may run past 24 or below 0) onto the week, splitting at midnight. */
    function place(week, d, from, to, kind) {
        let day = d;
        let a = from;
        let b = to;
        while (a < 0) { a += 24; b += 24; day -= 1; }
        while (a >= 24) { a -= 24; b -= 24; day += 1; }
        const idx = ((day % 7) + 7) % 7;
        if (b <= 24) {
            week[idx].push({ from: a, to: b, kind });
        } else {
            week[idx].push({ from: a, to: 24, kind });
            place(week, day + 1, 0, b - 24, kind);
        }
    }

    function bars(windows, ruleTz, viewerTz, at) {
        const week = DAYS.map(() => []);
        const shift = shiftHours(ruleTz, viewerTz, at);
        (windows || []).forEach(w => {
            const start = hours(w.startTime);
            const stop = hours(w.stopTime);
            (w.daysOfWeek || []).forEach(code => {
                const d = DAYS.indexOf(String(code).toUpperCase());
                if (d < 0) return;
                if (start !== null && stop !== null) {
                    // A stop at or before the start ends on the next day (e.g. 22:00–06:00).
                    const end = stop > start ? stop : stop + 24;
                    place(week, d, start + shift, end + shift, 'run');
                } else if (start !== null) {
                    place(week, d, start + shift, 24 + shift, 'run');
                } else if (stop !== null) {
                    place(week, d, stop + shift - MARK, stop + shift, 'stop');
                }
            });
        });
        week.forEach(day => day.sort((x, y) => x.from - y.from));
        return week;
    }

    function pct(h) {
        return Math.round((h / 24) * 10000) / 100;
    }

    function html(days, opts = {}) {
        const label = opts.label || 'Weekly schedule';
        const rows = (days || []).map((bars, i) => {
            const skipped = opts.skippedDay === i;
            const spans = bars.map(b => `<span class="week-bar${b.kind === 'stop' ? ' week-mark' : ''}${skipped ? ' week-skipped' : ''}"
                style="left:${pct(b.from)}%;width:${pct(Math.max(b.to - b.from, 0.2))}%"></span>`).join('');
            return `<div class="week-row"><span class="week-day">${LABELS[i]}</span><span class="week-track">${spans}</span></div>`;
        }).join('');
        const esc = typeof Utils !== 'undefined' && Utils.escapeHtml ? Utils.escapeHtml : (x => String(x));
        return `<div class="week-strip${opts.off ? ' week-off' : ''}" role="img" aria-label="${esc(label)}">${rows}
            <div class="week-ticks" aria-hidden="true"><span>00</span><span>06</span><span>12</span><span>18</span><span>24</span></div></div>`;
    }

    /** Monday = 0 … Sunday = 6 of a moment, in a time zone. */
    function weekdayIndex(at, tz) {
        try {
            const name = new Intl.DateTimeFormat('en-US', { timeZone: tz || undefined, weekday: 'short' }).format(at);
            return LABELS.indexOf(name);
        } catch (e) {
            return (at.getDay() + 6) % 7;
        }
    }

    return { bars, html, shiftHours, weekdayIndex, LABELS };
})();

if (typeof window !== 'undefined') window.WeekStrip = WeekStrip;
