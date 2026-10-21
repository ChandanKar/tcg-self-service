// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T03: the schedule editor's days, hours a week, holiday choices and request body.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/ui/week-strip.js', 'utf8') + '\nthis.WeekStrip = WeekStrip;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/rule-editor.js', 'utf8') + '\nthis.R = RuleEditor;', context);
const M = context.R.model;
const plain = x => JSON.parse(JSON.stringify(x));
const officeHours = [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' }];

test('days read as ranges', () => {
    assert.equal(M.daysText(['FRI', 'MON', 'TUE', 'WED', 'THU']), 'Mon–Fri');
    assert.equal(M.daysText(['MON', 'WED', 'SAT']), 'Mon, Wed, Sat');
    assert.equal(M.daysText(['MON', 'TUE', 'SAT', 'SUN']), 'Mon, Tue, Sat, Sun');
    assert.equal(M.daysText(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']), 'Every day');
    assert.equal(M.windowText({ daysOfWeek: ['SAT'], stopTime: '14:00' }), 'Sat stop 14:00');
});

test('Mon–Fri 08:00–20:00 runs 60 hours a week instead of 168', () => {
    assert.equal(M.runHours(officeHours), 60);
    assert.equal(M.hoursText(officeHours), 'Runs 60 hours a week instead of 168');
    // An overnight window and an overlapping second window are counted once per hour.
    assert.equal(M.runHours([{ daysOfWeek: ['MON'], startTime: '22:00', stopTime: '06:00' }]), 8);
    assert.equal(M.runHours([{ daysOfWeek: ['MON'], startTime: '08:00', stopTime: '12:00' },
        { daysOfWeek: ['MON'], startTime: '10:00', stopTime: '14:30' }]), 6.5);
    assert.equal(M.hoursText([{ daysOfWeek: ['MON'], startTime: '08:00', stopTime: '10:30' }]), 'Runs 2.5 hours a week instead of 168');
    assert.equal(M.hoursText([{ daysOfWeek: ['SAT'], startTime: null, stopTime: '20:00' }]), 'Only stops: nothing is started on a schedule');
});

test('one set of times is the simple editor; different times per day are advanced', () => {
    assert.deepEqual(plain(M.simpleOf(officeHours)), { days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], start: '08:00', stop: '20:00' });
    assert.equal(M.simpleOf([officeHours[0], { daysOfWeek: ['SAT'], startTime: '10:00', stopTime: '14:00' }]), null);
    assert.deepEqual(plain(M.windowsFromSimple({ days: ['WED', 'MON'], start: '', stop: '19:00' })),
        [{ daysOfWeek: ['MON', 'WED'], startTime: null, stopTime: '19:00' }]);
});

test('On holidays: per calendar, don\'t start / don\'t start or stop, and run as usual', () => {
    const cals = [{ calendarId: 'c1', name: 'India public' }];
    assert.deepEqual(plain(M.holidayChoices(cals, null)).map(o => o.label),
        ["Don't start on India public holidays", 'Run as usual', "Don't start or stop on India public holidays"]);
    assert.equal(M.holidayValue({ holidayCalendarId: 'c1', holidayBehavior: 'SKIP_ALL' }), 'SKIP_ALL:c1');
    assert.equal(M.holidayValue({}), '');
    assert.deepEqual(plain(M.parseHoliday('SKIP_ALL:c1')), { holidayCalendarId: 'c1', holidayBehavior: 'SKIP_ALL' });
    assert.deepEqual(plain(M.parseHoliday('')), { holidayCalendarId: null, holidayBehavior: 'SKIP_START' });
    // A linked calendar that no longer lists still shows by name.
    assert.equal(M.holidayChoices([], { holidayCalendarId: 'old', holidayCalendarName: 'Old' }).length, 3);
});

test('the request body and what is missing', () => {
    const dto = plain(M.dtoOf({ environmentId: 'e1', name: ' Office hours ', scope: 'GROUP:g1', windows: officeHours,
        timezone: 'Asia/Kolkata', holiday: 'SKIP_ALL:c1' }));
    assert.deepEqual(dto, { environmentId: 'e1', name: 'Office hours', description: null, scopeType: 'GROUP', scopeId: 'g1',
        triggerType: 'SCHEDULE', skipIfAlreadyInTargetState: true, enabled: true, windows: officeHours, timezone: 'Asia/Kolkata',
        holidayCalendarId: 'c1', holidayBehavior: 'SKIP_ALL' });
    assert.equal(M.dtoOf({ scope: '' }).scopeType, 'ENVIRONMENT');
    assert.deepEqual(plain(M.problems({ name: '', environmentId: 'e1', windows: [{ daysOfWeek: [], startTime: '08:00' }] })),
        { name: 'Give the schedule a name.', times: 'Pick at least one day.' });
    assert.deepEqual(plain(M.problems({ name: 'x', environmentId: 'e1', windows: [{ daysOfWeek: ['MON'] }] })),
        { times: 'Set a start time, a stop time or both.' });
});

test('server messages land next to their field; the first start comes from the preview', () => {
    assert.equal(M.fieldOf('Windows overlap on SAT'), 'times');
    assert.equal(M.fieldOf('Window 2: At least one day of week is required for a schedule rule'), 'times');
    assert.equal(M.fieldOf('Invalid timezone: Mars/Base'), 'tz');
    assert.equal(M.fieldOf('Holiday calendar not found: x'), 'holidays');
    assert.equal(M.fieldOf('Selected group does not belong to environment dev'), 'environment');
    assert.equal(M.fieldOf('Name is required'), 'name');
    assert.equal(M.fieldOf('Something else'), 'form');
    assert.equal(M.firstText([{ action: 'STOP', label: 'Mon 20 Oct, 20:00 IST' }, { action: 'START', skipped: true, label: 'x' },
        { action: 'START', label: 'Tue 21 Oct, 08:00 IST' }]), 'first start Tue 21 Oct, 08:00 IST');
    assert.equal(M.firstText([{ action: 'STOP', label: 'Sat 25 Oct, 20:00 IST' }]), 'first stop Sat 25 Oct, 20:00 IST');
});
