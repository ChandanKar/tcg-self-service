/**
 * Holiday calendars (E19-T02, U12-T04 AutomationTabs.dc.html): the Holiday calendars tab on the
 * Automation page. A list of calendars ("14 dates · next 20 Oct") beside the chosen one: "Used by
 * <n> schedules", its dates (past ones muted) and what a holiday does to a schedule. Administrators
 * create, rename and delete calendars, add and remove dates and import 'yyyy-MM-dd,label' CSV
 * files; environment admins read them. Schedules pick a calendar in the schedule editor.
 */
const HolidayCalendars = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };

    let $root = null;
    let calendars = [];
    let selectedId = null;
    let dates = [];
    let lastImport = null;
    let canEdit = false;
    let adding = false;
    let onChange = () => {};

    // ------------------------------------------------------------------ model

    function parse(isoDate) {
        return new Date(`${isoDate}T00:00:00Z`);
    }

    /** "02-10-2026" */
    function dateText(isoDate) {
        const [y, m, d] = String(isoDate || '').split('-');
        return d && m && y ? `${d}-${m}-${y}` : '—';
    }

    /** "Fri" */
    function dayText(isoDate) {
        return parse(isoDate).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
    }

    /** "14 dates · next 20 Oct", or "· none ahead". */
    function summary(calendar) {
        const n = calendar.dateCount || 0;
        const next = calendar.nextHoliday
            ? `next ${parse(calendar.nextHoliday).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' })}`
            : 'none ahead';
        return `${n} date${n === 1 ? '' : 's'} · ${next}`;
    }

    function usedText(count) {
        const n = count || 0;
        return n ? `Used by ${n} schedule${n === 1 ? '' : 's'}` : 'No schedule uses it';
    }

    /** Before today, in the viewer's calendar. */
    function isPast(isoDate, today) {
        const now = today || new Date();
        const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        return String(isoDate) < todayIso;
    }

    /** One skipped line: 'row 7: "31-02-2026" is not a date'. */
    function skippedText(line) {
        const first = String(line.content || '').split(',')[0].trim();
        const why = /^not a date/i.test(line.reason || '') ? 'is not a date'
            : String(line.reason || 'was skipped').replace(/^./, c => c.toLowerCase());
        return `row ${line.line}: "${first}" ${why}`;
    }

    /** 'Imported: 4 added, 2 already there, 1 line skipped (row 7: "31-02-2026" is not a date).' */
    function importText(result) {
        const invalid = result.invalid || [];
        let text = `Imported: ${result.added} added, ${result.duplicates || 0} already there`;
        if (invalid.length) {
            text += `, ${invalid.length} line${invalid.length === 1 ? '' : 's'} skipped (${invalid.map(skippedText).join('; ')})`;
        }
        return `${text}.`;
    }

    // ------------------------------------------------------------------ page

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    /**
     * Renders into $container; changed() is told when calendars change. opts.canEdit: administrators
     * (the server allows writes to them only).
     */
    function render($container, changed, opts) {
        $root = $container;
        onChange = changed || (() => {});
        canEdit = !!(opts && opts.canEdit);
        adding = false;
        $root.html('<div class="ar-empty" role="status">Loading calendars…</div>');
        refresh();
    }

    async function refresh() {
        try {
            calendars = await call('GET', Config.API.holidayCalendars.list) || [];
            if (selectedId && !calendars.some(c => c.calendarId === selectedId)) {
                selectedId = null;
            }
            if (!selectedId && calendars.length) {
                selectedId = calendars[0].calendarId;
            }
            if (selectedId) {
                const page = await call('GET', Config.API.holidayCalendars.dates(selectedId));
                dates = (page && page.content) || [];
            } else {
                dates = [];
            }
            draw();
            onChange(calendars);
        } catch (error) {
            $root.html(Utils.html`<div class="alert alert-danger" role="alert">${serverMessage(error, "Couldn't load holiday calendars.")}</div>`);
        }
    }

    function draw() {
        const selected = calendars.find(c => c.calendarId === selectedId);
        const items = calendars.map(c => Utils.html`
            <button type="button" class="hc-item hc-select" data-id="${c.calendarId}"${Utils.raw(c.calendarId === selectedId ? ' aria-current="true"' : '')}>
                <b>${c.name}</b><span>${summary(c)}</span></button>`).join('');
        $root.html(Utils.html`
            <div class="hc-layout">
                <nav class="hc-nav" aria-label="Calendars" id="hc-calendars">
                    ${Utils.raw(items || '<p class="hc-none">No holiday calendars yet.</p>')}
                    ${Utils.raw(canEdit ? '<div class="hc-nav-foot"><button type="button" class="hc-link" id="hc-new-btn">+ New calendar</button></div>' : '')}
                </nav>
                ${Utils.raw(selected ? detailHtml(selected) : (canEdit ? '' : '<p class="ar-empty">No holiday calendars yet. Administrators keep them.</p>'))}
            </div>`);
        bind();
    }

    function detailHtml(calendar) {
        const today = new Date();
        const rows = dates.length ? dates.map(d => Utils.html`
            <tr${Utils.raw(isPast(d.date, today) ? ' class="hc-past"' : '')}>
                <td class="hc-date">${dateText(d.date)}</td>
                <td class="hc-day">${dayText(d.date)}</td>
                <td>${d.label || ''}</td>
                ${Utils.raw(canEdit ? Utils.html`<td class="hc-remove"><button type="button" class="hc-link hc-danger hc-remove-date" data-id="${d.holidayId}"
                    aria-label="Remove ${dateText(d.date)}">Remove</button></td>` : '')}
            </tr>`).join('')
            : `<tr><td colspan="${canEdit ? 4 : 3}" class="hc-none">No dates in this calendar yet.</td></tr>`;
        const result = lastImport && lastImport.calendarId === calendar.calendarId
            ? Utils.html`<div class="hc-result${Utils.raw(lastImport.result.invalid && lastImport.result.invalid.length ? ' hc-result-warn' : '')}" role="status">${importText(lastImport.result)}</div>` : '';
        const actions = canEdit ? `<div class="hc-actions">
                <button type="button" class="btn btn-secondary btn-sm" id="hc-import-btn">Import CSV</button>
                <input type="file" class="visually-hidden" id="hc-file" accept=".csv,text/csv" tabindex="-1" aria-hidden="true">
                <button type="button" class="btn btn-secondary btn-sm" id="hc-add-btn" aria-expanded="${adding}" aria-controls="hc-add-form">Add date</button>
            </div>` : '';
        const addForm = canEdit ? `<form class="hc-add-form" id="hc-add-form" novalidate${adding ? '' : ' hidden'}>
                <label>Date<input type="date" class="form-control form-control-sm" id="hc-date" required></label>
                <label>Label<input type="text" class="form-control form-control-sm" id="hc-label" maxlength="100" placeholder="Optional"></label>
                <button type="submit" class="btn btn-primary btn-sm">Add</button>
            </form>` : '';
        return Utils.html`
            <section class="hc-detail" id="hc-detail" aria-labelledby="hc-detail-title">
                <div class="hc-head">
                    <div>
                        <h2 id="hc-detail-title">${calendar.name}</h2>
                        <div class="hc-used" id="hc-used">${usedText(calendar.usedByRuleCount)}${Utils.raw(calendar.description
                            ? Utils.html` · ${calendar.description}` : '')}</div>
                        ${Utils.raw(canEdit ? Utils.html`<div class="hc-manage">
                            <button type="button" class="hc-link hc-rename" data-id="${calendar.calendarId}" aria-label="Rename ${calendar.name}">Rename</button>
                            <button type="button" class="hc-link hc-danger hc-delete" data-id="${calendar.calendarId}" aria-label="Delete ${calendar.name}">Delete calendar</button>
                        </div>` : '')}
                    </div>
                    ${Utils.raw(actions)}
                </div>
                ${Utils.raw(addForm)}
                <div id="hc-import-result" aria-live="polite">${Utils.raw(result)}</div>
                <div class="hc-table-wrap"><table class="hc-dates" id="hc-dates-table">
                    <caption class="visually-hidden">Dates in ${calendar.name}</caption>
                    <thead class="visually-hidden"><tr><th>Date</th><th>Day</th><th>Label</th>${Utils.raw(canEdit ? '<th>Remove</th>' : '')}</tr></thead>
                    <tbody>${Utils.raw(rows)}</tbody>
                </table></div>
                <p class="hc-note">On these dates a schedule either skips its start only (stops still run, the usual choice) or skips everything.
                    Each schedule chooses; skipped runs show "holiday" in Recent runs.</p>
            </section>`;
    }

    function bind() {
        $root.find('.hc-select').on('click', function() {
            selectedId = $(this).data('id');
            adding = false;
            refresh();
        });
        if (!canEdit) return;
        $root.find('#hc-new-btn').on('click', () => openCalendarModal(null));
        $root.find('.hc-rename').on('click', function() {
            openCalendarModal(calendars.find(c => c.calendarId === $(this).data('id')));
        });
        $root.find('.hc-delete').on('click', function() {
            const calendar = calendars.find(c => c.calendarId === $(this).data('id'));
            if (!calendar) return;
            const used = calendar.usedByRuleCount ? ` ${usedText(calendar.usedByRuleCount)}; they will run on every day again.` : '';
            Modals.confirm('Delete holiday calendar',
                `Delete "${calendar.name}" and its ${calendar.dateCount} date(s)?${used}`,
                async () => {
                    try {
                        await call('DELETE', Config.API.holidayCalendars.delete(calendar.calendarId));
                        Notifications.show('Holiday calendar deleted.', 'success');
                        refresh();
                    } catch (error) {
                        Notifications.showError(serverMessage(error, 'Failed to delete the calendar.'));
                    }
                }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
        });
        $root.find('#hc-add-btn').on('click', function() {
            adding = !adding;
            $(this).attr('aria-expanded', String(adding));
            $root.find('#hc-add-form').prop('hidden', !adding);
            if (adding) $root.find('#hc-date').trigger('focus');
        });
        $root.find('#hc-add-form').on('submit', async function(e) {
            e.preventDefault();
            const date = $('#hc-date').val();
            if (!date) {
                Notifications.showError('Choose a date.');
                return;
            }
            try {
                await call('POST', Config.API.holidayCalendars.addDate(selectedId), { date, label: $('#hc-label').val().trim() || null });
                adding = true; // keep the form open for the next date
                await refresh();
                $root.find('#hc-date').trigger('focus');
            } catch (error) {
                Notifications.showError(serverMessage(error, 'Failed to add the date.'));
            }
        });
        $root.find('.hc-remove-date').on('click', async function() {
            try {
                await call('DELETE', Config.API.holidayCalendars.removeDate(selectedId, $(this).data('id')));
                refresh();
            } catch (error) {
                Notifications.showError(serverMessage(error, 'Failed to remove the date.'));
            }
        });
        $root.find('#hc-import-btn').on('click', () => $root.find('#hc-file').trigger('click'));
        $root.find('#hc-file').on('change', importFile);
    }

    async function importFile() {
        const file = $('#hc-file')[0] && $('#hc-file')[0].files[0];
        if (!file) return;
        const form = new FormData();
        form.append('file', file);
        const headers = {};
        const token = ApiClient.readCsrfToken();
        if (token) headers['X-XSRF-TOKEN'] = token;
        const calendarId = selectedId;
        try {
            const response = await fetch(Config.API.holidayCalendars.import(calendarId),
                { method: 'POST', body: form, headers, credentials: 'same-origin' });
            const body = await response.json().catch(() => null);
            if (!response.ok) {
                Notifications.showError((body && body.message) || 'Import failed.');
                return;
            }
            lastImport = { calendarId, result: body };
            refresh();
        } catch (error) {
            console.error('Holiday import failed:', error);
            Notifications.showError('Import failed.');
        }
    }

    function openCalendarModal(calendar) {
        Modals.show({
            id: 'holidayCalendarModal',
            title: calendar ? 'Rename holiday calendar' : 'New holiday calendar',
            body: Utils.html`
                <div class="mb-3">
                    <label class="form-label" for="hc-name">Name <span class="text-danger">*</span></label>
                    <input type="text" class="form-control" id="hc-name" maxlength="100" value="${calendar ? calendar.name : ''}" required>
                </div>
                <div class="mb-0">
                    <label class="form-label" for="hc-description">Description</label>
                    <input type="text" class="form-control" id="hc-description" maxlength="500" value="${calendar ? (calendar.description || '') : ''}">
                </div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: calendar ? 'Save' : 'Create', class: 'btn-primary', id: 'hc-save-btn' }
            ],
            onShow: () => $('#hc-name').trigger('focus')
        });
        $('#hc-save-btn').on('click', async function() {
            const name = $('#hc-name').val().trim();
            if (!name) {
                Notifications.showError('Name is required.');
                return;
            }
            const body = { name, description: $('#hc-description').val().trim() || null };
            $(this).prop('disabled', true);
            try {
                const saved = calendar
                    ? await call('PUT', Config.API.holidayCalendars.update(calendar.calendarId), body)
                    : await call('POST', Config.API.holidayCalendars.create, body);
                selectedId = saved.calendarId;
                bootstrap.Modal.getInstance(document.getElementById('holidayCalendarModal')).hide();
                refresh();
            } catch (error) {
                Notifications.showError(serverMessage(error, 'Failed to save the calendar.'));
                $(this).prop('disabled', false);
            }
        });
    }

    return {
        render,
        model: { dateText, dayText, summary, usedText, isPast, importText, skippedText }
    };
})();

window.HolidayCalendars = HolidayCalendars;
