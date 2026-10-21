// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T04: the Holiday calendars tab's wording (AutomationTabs.dc.html).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/holiday-calendars.js', 'utf8') + '\nthis.H = HolidayCalendars;', context);
const M = context.H.model;

test('dates, days and the list line', () => {
    assert.equal(M.dateText('2026-10-02'), '02-10-2026');
    assert.equal(M.dayText('2026-10-02'), 'Fri');
    assert.equal(M.summary({ dateCount: 14, nextHoliday: '2026-10-20' }), '14 dates · next 20 Oct');
    assert.equal(M.summary({ dateCount: 1, nextHoliday: null }), '1 date · none ahead');
    assert.equal(M.isPast('2026-10-20', new Date(2026, 9, 21)), true);
    assert.equal(M.isPast('2026-10-21', new Date(2026, 9, 21)), false);
});

test('used by schedules', () => {
    assert.equal(M.usedText(9), 'Used by 9 schedules');
    assert.equal(M.usedText(1), 'Used by 1 schedule');
    assert.equal(M.usedText(0), 'No schedule uses it');
});

test('the import result line', () => {
    assert.equal(M.importText({ added: 4, duplicates: 2, invalid: [{ line: 7, content: '31-02-2026,Bad', reason: 'Not a date (expected yyyy-MM-dd)' }] }),
        'Imported: 4 added, 2 already there, 1 line skipped (row 7: "31-02-2026" is not a date).');
    assert.equal(M.importText({ added: 3, duplicates: 0, invalid: [] }), 'Imported: 3 added, 0 already there.');
});
