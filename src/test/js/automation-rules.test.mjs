// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T02: the Automation rules page's counts and schedule lines.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/ui/week-strip.js', 'utf8') + '\nthis.WeekStrip = WeekStrip;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/automation-rules.js', 'utf8') + '\nthis.A = AutomationRules;', context);
const M = context.A.model;
const now = new Date(2026, 9, 21, 12, 0); // a Wednesday, local time

test('next run in the viewer\'s words', () => {
    assert.equal(M.clockText(new Date(2026, 9, 21, 20, 0), now), 'today 20:00');
    assert.equal(M.clockText(new Date(2026, 9, 22, 6, 30), now), 'tomorrow 06:30');
    assert.equal(M.clockText(new Date(2026, 9, 26, 9, 0), now), 'Mon 09:00');
    assert.equal(M.clockText(new Date(2026, 10, 3, 9, 0), now), '3 Nov 09:00');
    assert.equal(M.nextText({ nextRunAt: new Date(2026, 9, 21, 20, 0).toISOString(), nextRunAction: 'STOP' }, now), 'Stop today 20:00');
    assert.equal(M.nextText({}, now), '');
});

test('counts: rules on, environments covered, runs skipped last week because of a lock', () => {
    const rules = [
        { enabled: true, environmentId: 'a', lastRunReason: 'LOCKED', lastRunAt: new Date(2026, 9, 19).toISOString() },
        { enabled: true, environmentId: 'a' },
        { enabled: true, environmentId: 'b', lastRunReason: 'LOCKED', lastRunAt: new Date(2026, 9, 1).toISOString() },
        { enabled: false, environmentId: 'c' }
    ];
    assert.deepEqual(JSON.parse(JSON.stringify(M.countsOf(rules, now))), { on: 3, environments: 2, skippedLocked: 1 });
});

test('the schedule line: summary, zone, holidays, local time and a recent skip', () => {
    const rule = { scheduleSummary: 'Mon–Fri 08:00–20:00', timezone: 'Asia/Kolkata', holidayCalendarName: 'India public',
        holidayBehavior: 'SKIP_ALL', windows: [{ daysOfWeek: ['MON'], startTime: '08:00', stopTime: '20:00' }] };
    assert.equal(M.scheduleLine(rule, now, 'Asia/Kolkata'), 'Mon–Fri 08:00–20:00 · Asia/Kolkata · off on India public holidays');
    const utc = { scheduleSummary: 'Every day 01:00–05:00', timezone: 'UTC', windows: [{ daysOfWeek: ['MON'], startTime: '01:00', stopTime: '05:00' }],
        lastRunStatus: 'SKIPPED', lastRunReason: 'LOCKED', lastRunAt: new Date(Date.UTC(2026, 9, 21, 1, 0)).toISOString() };
    assert.equal(M.scheduleLine(utc, now, 'Asia/Kolkata'), 'Every day 01:00–05:00 · UTC (06:30–10:30 here) · skipped Wed: environment locked');
    assert.equal(M.hhmm(25.5), '01:30');
    // U12-T04: an access rule in a sentence.
    assert.equal(M.accessLine({ accessGrantMode: 'LOCK_ACQUIRE', scopeType: 'ENVIRONMENT', environmentName: 'CRM UAT' }),
        'Starts CRM UAT when someone takes the lock, in their name');
    assert.equal(M.accessLine({ accessGrantMode: 'ACCESS_APPROVED', scopeType: 'GROUP', scopeName: 'Web', environmentName: 'CRM UAT',
        createdByDisplayName: 'Asha' }), "Starts Web in CRM UAT when an access request is approved, in Asha's name");
});
