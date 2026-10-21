// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T02: a schedule as Mon–Sun bars on 0–24 h, in the viewer's time.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/ui/week-strip.js', 'utf8') + '\nthis.W = WeekStrip;', context);
const W = context.W;
const plain = x => JSON.parse(JSON.stringify(x));
const at = new Date(Date.UTC(2026, 9, 21, 12));

test('office hours: five bars from 08:00 to 20:00, none at the weekend', () => {
    const week = plain(W.bars([{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' }],
        'Asia/Kolkata', 'Asia/Kolkata', at));
    assert.deepEqual(week.slice(0, 5), Array(5).fill([{ from: 8, to: 20, kind: 'run' }]));
    assert.deepEqual(week.slice(5), [[], []]);
    const markup = W.html(week, { label: 'Mon–Fri 08:00–20:00' });
    assert.match(markup, /left:33.33%;width:50%/);
    assert.match(markup, /aria-label="Mon–Fri 08:00–20:00"/);
});

test('a window across midnight continues on the next day; Sunday wraps to Monday', () => {
    const week = plain(W.bars([{ daysOfWeek: ['SUN'], startTime: '22:00', stopTime: '06:00' }], 'UTC', 'UTC', at));
    assert.deepEqual(week[6], [{ from: 22, to: 24, kind: 'run' }]);
    assert.deepEqual(week[0], [{ from: 0, to: 6, kind: 'run' }]);
});

test('a UTC rule shows in the viewer\'s time', () => {
    const week = plain(W.bars([{ daysOfWeek: ['WED'], startTime: '01:00', stopTime: '05:00' }], 'UTC', 'Asia/Kolkata', at));
    assert.deepEqual(week[2], [{ from: 6.5, to: 10.5, kind: 'run' }]);
    assert.equal(W.shiftHours('UTC', 'Asia/Kolkata', at), 5.5);
    assert.equal(W.shiftHours('Asia/Kolkata', 'Asia/Kolkata', at), 0);
});

test('a stop without a start is a mark; a skipped day is gold; an off rule is muted', () => {
    const week = plain(W.bars([{ daysOfWeek: ['FRI'], stopTime: '20:00' }], 'UTC', 'UTC', at));
    assert.equal(week[4][0].kind, 'stop');
    assert.ok(Math.abs(week[4][0].to - 20) < 1e-9);
    const markup = W.html(week, { skippedDay: 4, off: true });
    assert.match(markup, /week-strip week-off/);
    assert.match(markup, /week-bar week-mark week-skipped/);
    assert.equal(W.weekdayIndex(new Date(Date.UTC(2026, 9, 21, 12)), 'UTC'), 2); // a Wednesday
});
