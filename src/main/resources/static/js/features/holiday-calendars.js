/**
 * Holiday calendars (E19-T02): the admin-only tab on the Automation page. Lists calendars,
 * creates/renames/deletes them, and for the selected calendar adds and removes dates and imports
 * 'yyyy-MM-dd,label' CSV files. Schedule rules link to a calendar in the rule modal.
 */
const HolidayCalendars = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };

    let $root = null;
    let calendars = [];
    let selectedId = null;
    let dates = [];
    let lastImport = null;
    let onChange = () => {};

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    /** Renders into $container; changed() is told when calendars change (the rule modal's list). */
    function render($container, changed) {
        $root = $container;
        onChange = changed || (() => {});
        $root.html('<div class="text-muted small p-3"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading calendars…</div>');
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

    function formatDay(isoDate) {
        if (!isoDate) return '—';
        const d = new Date(`${isoDate}T00:00:00Z`);
        return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
    }

    function draw() {
        const selected = calendars.find(c => c.calendarId === selectedId);
        const rows = calendars.length ? calendars.map(c => Utils.html`
            <tr class="${c.calendarId === selectedId ? 'table-active' : ''}">
                <td data-label="Calendar"><button type="button" class="btn btn-link p-0 hc-select" data-id="${c.calendarId}"
                        aria-current="${c.calendarId === selectedId ? 'true' : 'false'}">${c.name}</button>
                    ${Utils.raw(c.description ? Utils.html`<div class="small text-muted">${c.description}</div>` : '')}</td>
                <td data-label="Dates">${c.dateCount}</td>
                <td data-label="Next holiday">${c.nextHoliday ? `${formatDay(c.nextHoliday)}${c.nextHolidayLabel ? ' · ' + c.nextHolidayLabel : ''}` : '—'}</td>
                <td data-label="Actions" class="text-nowrap">
                    <button type="button" class="btn btn-sm btn-ghost hc-rename" data-id="${c.calendarId}" aria-label="Rename ${c.name}">
                        <i class="fas fa-pen" aria-hidden="true"></i></button>
                    <button type="button" class="btn btn-sm btn-ghost text-danger hc-delete" data-id="${c.calendarId}" aria-label="Delete ${c.name}">
                        <i class="fas fa-trash" aria-hidden="true"></i></button>
                </td>
            </tr>`).join('')
            : '<tr><td colspan="4" class="text-muted text-center py-3">No holiday calendars yet</td></tr>';

        $root.html(Utils.html`
            <div class="hc-layout">
                <section class="card">
                    <div class="card-body">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <h2 class="h6 mb-0">Calendars</h2>
                            <button type="button" class="btn btn-sm btn-primary" id="hc-new-btn">
                                <i class="fas fa-plus" aria-hidden="true"></i> New calendar</button>
                        </div>
                        <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="hc-calendars-table">
                            <thead><tr><th>Calendar</th><th>Dates</th><th>Next holiday</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
                            <tbody>${Utils.raw(rows)}</tbody>
                        </table></div>
                    </div>
                </section>
                ${Utils.raw(selected ? detailHtml(selected) : '')}
            </div>`);
        bind();
    }

    function detailHtml(calendar) {
        const dateRows = dates.length ? dates.map(d => Utils.html`
            <tr>
                <td data-label="Date">${formatDay(d.date)}</td>
                <td data-label="Label">${d.label || '—'}</td>
                <td data-label="Remove"><button type="button" class="btn btn-sm btn-ghost hc-remove-date" data-id="${d.holidayId}"
                        aria-label="Remove ${d.date}"><i class="fas fa-times" aria-hidden="true"></i></button></td>
            </tr>`).join('')
            : '<tr><td colspan="3" class="text-muted text-center py-3">No dates in this calendar</td></tr>';
        const result = lastImport && lastImport.calendarId === calendar.calendarId ? importResultHtml(lastImport.result) : '';
        return Utils.html`
            <section class="card" id="hc-detail" aria-labelledby="hc-detail-title">
                <div class="card-body">
                    <h2 class="h6" id="hc-detail-title">${calendar.name}</h2>
                    <form class="hc-add-form" id="hc-add-form" novalidate>
                        <label class="visually-hidden" for="hc-date">Date</label>
                        <input type="date" class="form-control form-control-sm" id="hc-date" required>
                        <label class="visually-hidden" for="hc-label">Label</label>
                        <input type="text" class="form-control form-control-sm" id="hc-label" maxlength="100" placeholder="Label (optional)">
                        <button type="submit" class="btn btn-sm btn-tonal btn-primary">Add date</button>
                    </form>
                    <div class="hc-import">
                        <label class="form-label small mb-1" for="hc-file">Import CSV (one <code>yyyy-MM-dd,label</code> per line, up to 500)</label>
                        <div class="d-flex gap-2 flex-wrap">
                            <input type="file" class="form-control form-control-sm hc-file" id="hc-file" accept=".csv,text/csv">
                            <button type="button" class="btn btn-sm btn-ghost" id="hc-import-btn"><i class="fas fa-file-import" aria-hidden="true"></i> Import</button>
                        </div>
                        <div id="hc-import-result" aria-live="polite">${Utils.raw(result)}</div>
                    </div>
                    <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="hc-dates-table">
                        <thead><tr><th>Date</th><th>Label</th><th><span class="visually-hidden">Remove</span></th></tr></thead>
                        <tbody>${Utils.raw(dateRows)}</tbody>
                    </table></div>
                </div>
            </section>`;
    }

    function importResultHtml(result) {
        const invalid = (result.invalid || []).map(l => Utils.html`<li>Line ${l.line}: ${l.reason} — <code>${l.content}</code></li>`).join('');
        return Utils.html`<div class="alert ${result.invalid && result.invalid.length ? 'alert-warning' : 'alert-success'} small mt-2 mb-0" role="status">
            Added ${result.added} date(s)${result.duplicates ? `, skipped ${result.duplicates} already in the calendar` : ''}.
            ${Utils.raw(invalid ? `<ul class="mb-0 mt-1">${invalid}</ul>` : '')}</div>`;
    }

    function bind() {
        $root.find('#hc-new-btn').on('click', () => openCalendarModal(null));
        $root.find('.hc-select').on('click', function() {
            selectedId = $(this).data('id');
            refresh();
        });
        $root.find('.hc-rename').on('click', function() {
            openCalendarModal(calendars.find(c => c.calendarId === $(this).data('id')));
        });
        $root.find('.hc-delete').on('click', function() {
            const calendar = calendars.find(c => c.calendarId === $(this).data('id'));
            if (!calendar) return;
            Modals.confirm('Delete holiday calendar',
                `Delete "${calendar.name}" and its ${calendar.dateCount} date(s)? Rules using it will run on every day again.`,
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
        $root.find('#hc-add-form').on('submit', async function(e) {
            e.preventDefault();
            const date = $('#hc-date').val();
            if (!date) {
                Notifications.showError('Choose a date.');
                return;
            }
            try {
                await call('POST', Config.API.holidayCalendars.addDate(selectedId), { date, label: $('#hc-label').val().trim() || null });
                refresh();
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
        $root.find('#hc-import-btn').on('click', importFile);
    }

    async function importFile() {
        const file = $('#hc-file')[0] && $('#hc-file')[0].files[0];
        if (!file) {
            Notifications.showError('Choose a CSV file to import.');
            return;
        }
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

    return { render };
})();

window.HolidayCalendars = HolidayCalendars;
