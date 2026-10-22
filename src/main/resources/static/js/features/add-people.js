/**
 * VM Self-Service Platform - Add people (U13-T05, AddUsers.dc.html), #/user-management/add
 * Add colleagues before their first sign-in and give them access in the same step:
 *   1 · Who     people from the Microsoft directory (when lookup is on) or by hand (several
 *               addresses may be pasted at once), each with department, team and project code
 *   2 · Role    User / Environment admin / Administrator for the new ones
 *   3 · Access  optional environments (or some of their groups), each with a level and how long
 * The confirm bar previews the whole step on the server (POST /users/bulk-onboard?dryRun=true):
 * "Add 2 new people as Environment admin, 4 access grants", who is skipped and why; "Add N people"
 * applies it in one go (U13-T02) and returns to Users.
 */
const AddPeople = (function() {
    'use strict';

    const NS = '.addPeople';
    const ROLES = [
        ['USER', 'User', 'Uses the environments they are given'],
        ['ENV_ADMIN', 'Environment admin', 'Also approves access, runs environments'],
        ['ADMIN', 'Administrator', 'Everything, for every environment']
    ];
    const DURATIONS = [['30', '30 days'], ['7', '7 days'], ['90', '90 days'], ['', 'No end']];
    const SOURCES = { DIRECTORY: 'Directory', EXISTING: 'Already a user', MANUAL: 'By hand' };
    const EMAIL = /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/;

    let s = null; // page state

    // ---- pure model (node tests: src/test/js/add-people-parse.test.mjs) ----

    /** Addresses in pasted text: split on commas, semicolons, spaces and new lines; "Name <a@x.com>" works too. */
    function parseAddresses(text) {
        const out = [];
        const seen = new Set();
        String(text || '').split(/[,;\n\r\t ]+/).forEach(part => {
            const email = part.replace(/^[<"']+|[>"']+$/g, '').trim().toLowerCase();
            if (EMAIL.test(email) && !seen.has(email)) {
                seen.add(email);
                out.push(email);
            }
        });
        return out;
    }

    function roleLabel(role) {
        return (ROLES.find(r => r[0] === role) || ROLES[0])[1];
    }

    /** "Add 2 new people as Environment admin, 4 access grants" (and existing people, when there are any). */
    function summaryText(result, role) {
        if (!result) return '';
        const n = result.newPeople || 0;
        const grants = (result.newGrants || 0) + (result.changedGrants || 0);
        let text = `Add ${n} new ${n === 1 ? 'person' : 'people'} as ${roleLabel(role)}, ${grants} access grant${grants === 1 ? '' : 's'}`;
        if (result.existingPeople) {
            text += `; ${result.existingPeople} already ${result.existingPeople === 1 ? 'uses' : 'use'} the app (access and team only)`;
        }
        return text;
    }

    /** The request body for /users/bulk-onboard. */
    function requestOf(state) {
        return {
            people: state.people.map(p => ({
                directoryObjectId: p.directoryObjectId || null,
                email: p.email,
                displayName: p.name || null,
                department: p.department || null,
                projectCode: p.projectCode || null,
                teamId: p.teamId || null
            })),
            role: state.role,
            access: state.access.map(a => ({
                environmentId: a.environmentId,
                scopeType: a.groupIds.length ? 'GROUP' : 'ENVIRONMENT',
                groupIds: a.groupIds,
                accessLevel: a.level,
                durationDays: a.days ? Number(a.days) : null
            })),
            emailPeople: false
        };
    }

    // ---- page ----

    function call(method, url, body) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, body || null, { suppressGlobalError: true }).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    function guardUnload(e) {
        if (s && s.people.length) {
            e.preventDefault();
            e.returnValue = '';
        }
    }

    async function load() {
        const token = ContentRouter.token();
        s = { token, people: [], role: 'USER', access: [], teams: [], environments: [], groups: {}, result: null, problem: '',
            previewSeq: 0, previewTimer: null, searchTimer: null, sending: false, results: [], nextKey: 1,
            directory: typeof Capabilities !== 'undefined' && Capabilities.has('directory') };
        window.addEventListener('beforeunload', guardUnload);
        ContentRouter.onLeave(() => {
            window.removeEventListener('beforeunload', guardUnload);
            $(document).off(NS);
            if (s) { clearTimeout(s.previewTimer); clearTimeout(s.searchTimer); }
        });
        Loading.showPage('Loading…');
        const [teams, envs] = await Promise.all([
            call('GET', Config.API.teams.list()).catch(() => []),
            call('GET', Config.API.environments.list).catch(() => [])
        ]);
        if (!current()) return;
        s.teams = (Array.isArray(teams) ? teams : []).filter(t => t.active !== false)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
        s.environments = (Array.isArray(envs) ? envs : []).filter(e => e.viewerCanManageAccess !== false && e.isActive !== false)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
        render();
        bind();
        drawPeople();
        drawAccess();
        drawConfirm();
    }

    function teamOptions(selected) {
        return ['<option value="">No team</option>'].concat(s.teams.map(t => Utils.html`<option value="${t.teamId}"${Utils.raw(t.teamId === selected ? ' selected' : '')}>${t.displayName || t.name}</option>`)).join('');
    }

    function render() {
        const roles = ROLES.map(([value, title, text]) => Utils.html`<button type="button" role="radio" class="adp-role" data-adp-role="${value}"
                aria-checked="${s.role === value ? 'true' : 'false'}" tabindex="${s.role === value ? '0' : '-1'}"><b>${title}</b><span>${text}</span></button>`).join('');
        const search = s.directory ? `<label class="adp-search">
                <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                <span class="visually-hidden">Find in the Microsoft directory</span>
                <input type="search" class="form-control" id="adp-search" autocomplete="off" placeholder="Find in the Microsoft directory by name or email"
                       role="combobox" aria-expanded="false" aria-controls="adp-results" aria-autocomplete="list">
                <ul class="adp-results" id="adp-results" role="listbox" aria-label="People in the directory" hidden></ul>
            </label>` : '';
        $('#content-area').html(`
            <div class="content-view page page-frame adp-page" id="add-people-view">
                ${PageHeader.markup({ title: 'Add people', breadcrumb: [{ label: 'Users', href: '#/user-management' }, { label: 'Add people' }],
                    summaryHtml: 'Add colleagues before their first sign-in, and give them access in the same step.' })}
                <div class="adp-grid">
                    <section class="adp-card adp-who" aria-labelledby="adp-who-title">
                        <div class="adp-who-head"><h2 id="adp-who-title">1 · Who</h2>${search}</div>
                        <div class="adp-table-wrap"><table class="adp-table" id="adp-people">
                            <thead><tr><th scope="col">Person</th><th scope="col">Department</th><th scope="col">Team</th>
                                <th scope="col">Project code</th><th scope="col">Source</th></tr></thead>
                            <tbody></tbody></table></div>
                        <form class="adp-manual" id="adp-manual" novalidate aria-labelledby="adp-manual-title">
                            <h3 id="adp-manual-title">${s.directory ? 'Not in the directory? Add by hand' : 'Add by hand'}</h3>
                            <div class="adp-manual-grid">
                                <label class="ap-field">Email *<input type="text" class="form-control" id="adp-email" placeholder="name@company.com" autocomplete="off"></label>
                                <label class="ap-field">Name<input type="text" class="form-control" id="adp-name" maxlength="255"></label>
                                <label class="ap-field">Department<input type="text" class="form-control" id="adp-dept" maxlength="100"></label>
                                <label class="ap-field">Team<select class="form-select" id="adp-team">${teamOptions('')}</select></label>
                                <label class="ap-field">Project code<input type="text" class="form-control" id="adp-project" maxlength="50"></label>
                                <button type="submit" class="btn btn-secondary">Add to list</button>
                            </div>
                            <div class="re-error" id="adp-manual-error" role="alert" hidden></div>
                            <p class="adp-note">People added by hand are marked unverified until they sign in with Microsoft. You can also paste several addresses at once.</p>
                        </form>
                    </section>
                    <div class="adp-side">
                        <section class="adp-card" aria-labelledby="adp-role-title">
                            <h2 id="adp-role-title">2 · Their role</h2>
                            <div class="adp-roles" role="radiogroup" aria-labelledby="adp-role-title">${roles}</div>
                        </section>
                        <section class="adp-card" aria-labelledby="adp-access-title">
                            <div class="adp-access-head"><h2 id="adp-access-title">3 · Access <span>optional</span></h2>
                                <button type="button" class="adp-link" id="adp-add-env" aria-expanded="false" aria-controls="adp-picker">Add environment</button></div>
                            <div class="adp-picker" id="adp-picker" hidden></div>
                            <div class="adp-access" id="adp-access"></div>
                        </section>
                        <section class="adp-card adp-confirm" aria-label="Confirm">
                            <div id="adp-summary" aria-live="polite"></div>
                            <div class="adp-confirm-actions">
                                <a class="btn btn-secondary" href="#/user-management" id="adp-cancel">Cancel</a>
                                <button type="button" class="btn btn-primary" id="adp-submit" disabled>Add people</button>
                            </div>
                        </section>
                    </div>
                </div>
            </div>`);
        watchColumns();
    }

    // ---- 1 · Who

    function drawPeople() {
        const rows = s.people.map(p => {
            const outcome = outcomeOf(p);
            const source = outcome && outcome.outcome === 'EXISTING' ? 'EXISTING' : p.source;
            const label = p.name || p.email;
            return Utils.html`<tr data-key="${p.key}"${Utils.raw(outcome && outcome.outcome === 'SKIPPED' ? ' class="adp-row-skip"' : '')}>
                <td><div class="adp-name">${label}</div>${Utils.raw(p.name ? Utils.html`<div class="adp-email">${p.email}</div>` : '')}${Utils.raw(outcome && outcome.outcome === 'SKIPPED'
                    ? Utils.html`<div class="adp-skip">Skipped: ${outcome.reason}</div>` : '')}</td>
                <td><input type="text" class="form-control form-control-sm" data-adp-field="department" maxlength="100" value="${p.department || ''}" aria-label="Department for ${label}"></td>
                <td><select class="form-select form-select-sm" data-adp-field="teamId" aria-label="Team for ${label}">${Utils.raw(teamOptions(p.teamId))}</select></td>
                <td><input type="text" class="form-control form-control-sm" data-adp-field="projectCode" maxlength="50" value="${p.projectCode || ''}" aria-label="Project code for ${label}"></td>
                <td><div class="adp-source"><span class="adp-chip adp-chip-${source.toLowerCase()}">${SOURCES[source]}</span>
                    <button type="button" class="adp-remove" data-adp-remove="${p.key}" aria-label="Remove ${label}">×</button></div></td>
            </tr>`;
        }).join('');
        $('#adp-people tbody').html(rows || `<tr class="adp-empty"><td colspan="5">${s.directory
            ? 'Find people in the directory above, or add them by hand below.' : 'Add people by hand below.'}</td></tr>`);
    }

    function outcomeOf(person) {
        if (!s.result || !s.result.people) return null;
        return s.result.people.find(r => r.email && r.email.toLowerCase() === person.email.toLowerCase()) || null;
    }

    function addPerson(p) {
        if (s.people.some(x => x.email.toLowerCase() === p.email.toLowerCase())) return false;
        s.people.push(Object.assign({ key: String(s.nextKey++), department: '', teamId: '', projectCode: '' }, p));
        return true;
    }

    function showResults(list) {
        s.results = (list || []).slice(0, 8);
        const $list = $('#adp-results');
        $list.html(s.results.length ? s.results.map((u, i) => Utils.html`<li role="option" class="adp-option" tabindex="-1" data-idx="${i}">
                <b>${u.displayName || u.email}</b> <span>${u.email || ''}${Utils.raw(u.alreadyInApp ? ' · already a user' : '')}</span></li>`).join('')
            : '<li class="adp-option-none" role="option" aria-disabled="true">No one in the directory matches</li>').prop('hidden', false);
        $('#adp-search').attr('aria-expanded', 'true');
    }

    function hideResults() {
        $('#adp-results').prop('hidden', true).empty();
        $('#adp-search').attr('aria-expanded', 'false');
    }

    function pickResult(i) {
        const u = s.results[i];
        if (!u || !u.email) return;
        addPerson({ name: u.displayName || '', email: u.email, department: u.department || '', directoryObjectId: u.directoryObjectId,
            source: u.alreadyInApp ? 'EXISTING' : 'DIRECTORY' });
        $('#adp-search').val('').trigger('focus');
        hideResults();
        changed();
    }

    function addManual() {
        const $error = $('#adp-manual-error').prop('hidden', true);
        const emails = parseAddresses($('#adp-email').val());
        if (!emails.length) {
            $error.text('Enter an email address, or paste several.').prop('hidden', false);
            $('#adp-email').trigger('focus');
            return;
        }
        const name = emails.length === 1 ? $('#adp-name').val().trim() : '';
        const extra = { department: $('#adp-dept').val().trim(), teamId: $('#adp-team').val(), projectCode: $('#adp-project').val().trim() };
        const already = emails.filter(email => !addPerson(Object.assign({ name, email, source: 'MANUAL' }, extra)));
        if (already.length) $error.text(`Already in the list: ${already.join(', ')}`).prop('hidden', false);
        $('#adp-email, #adp-name').val('');
        $('#adp-email').trigger('focus');
        changed();
    }

    // ---- 3 · Access

    function envName(id) {
        const e = s.environments.find(x => x.environmentId === id);
        return e ? (e.displayName || e.name) : id;
    }

    function drawAccess() {
        $('#adp-access').html(s.access.length ? s.access.map((a, i) => {
            const name = envName(a.environmentId);
            const levels = ['USER', 'VIEWER', 'ADMIN'].map(l => Utils.html`<option value="${l}"${Utils.raw(a.level === l ? ' selected' : '')}>${AccessLevels.label(l)}</option>`).join('');
            const days = DURATIONS.map(([v, t]) => Utils.html`<option value="${v}"${Utils.raw(a.days === v ? ' selected' : '')}>${t}</option>`).join('');
            return Utils.html`<div class="adp-access-row" data-idx="${i}">
                <div class="adp-access-name"><span><b>${name}</b>${Utils.raw(a.groupNames.length ? Utils.html` <span>· ${a.groupNames.join(', ')}</span>` : '')}</span>
                    <button type="button" class="adp-link adp-danger" data-adp-remove-access="${i}" aria-label="Remove ${name}">Remove</button></div>
                <div class="adp-access-sets">
                    <select class="form-select form-select-sm" data-adp-access="level" aria-label="Level on ${name}">${Utils.raw(levels)}</select>
                    <select class="form-select form-select-sm" data-adp-access="days" aria-label="How long on ${name}">${Utils.raw(days)}</select>
                </div></div>`;
        }).join('') : '<p class="adp-note">No access yet. People can ask for it later.</p>');
    }

    async function openPicker() {
        const $picker = $('#adp-picker');
        const open = $picker.prop('hidden');
        $('#adp-add-env').attr('aria-expanded', String(open));
        if (!open) { $picker.prop('hidden', true).empty(); return; }
        const choices = s.environments.filter(e => !s.access.some(a => a.environmentId === e.environmentId));
        $picker.html(choices.length ? Utils.html`<label class="ap-field">Environment
                <select class="form-select form-select-sm" id="adp-pick-env"><option value="">Choose…</option>${Utils.raw(choices.map(e =>
                    Utils.html`<option value="${e.environmentId}">${e.displayName || e.name}</option>`).join(''))}</select></label>
            <div id="adp-pick-groups"></div>
            <div class="adp-picker-actions"><button type="button" class="btn btn-sm btn-primary" id="adp-pick-add" disabled>Add</button>
                <button type="button" class="btn btn-sm btn-secondary" id="adp-pick-cancel">Cancel</button></div>`
            : '<p class="adp-note">Every environment you can give access to is already in the list.</p>').prop('hidden', false);
        $('#adp-pick-env').trigger('focus');
    }

    async function pickEnvironment(environmentId) {
        $('#adp-pick-add').prop('disabled', !environmentId);
        const $groups = $('#adp-pick-groups').empty();
        if (!environmentId) return;
        if (!s.groups[environmentId]) {
            s.groups[environmentId] = await call('GET', Config.API.groups.list(environmentId)).catch(() => []) || [];
            if (!current()) return;
        }
        const groups = s.groups[environmentId].filter(g => g.name !== 'discovered');
        if (groups.length > 1) {
            $groups.html(Utils.html`<fieldset class="adp-groups"><legend>Only some groups (optional)</legend>${Utils.raw(groups.map(g =>
                Utils.html`<label class="re-check"><input type="checkbox" value="${g.groupId}" data-name="${g.displayName || g.name}"> ${g.displayName || g.name}</label>`).join(''))}</fieldset>`);
        }
    }

    function addAccessFromPicker() {
        const environmentId = $('#adp-pick-env').val();
        if (!environmentId) return;
        const checked = $('#adp-pick-groups input:checked').get();
        s.access.push({ environmentId, groupIds: checked.map(c => c.value), groupNames: checked.map(c => c.dataset.name), level: 'USER', days: '30' });
        $('#adp-picker').prop('hidden', true).empty();
        $('#adp-add-env').attr('aria-expanded', 'false').trigger('focus');
        drawAccess();
        changed();
    }

    // ---- confirm

    function changed() {
        s.result = null;
        s.problem = '';
        drawPeople();
        drawConfirm();
        clearTimeout(s.previewTimer);
        if (s.people.length) s.previewTimer = setTimeout(preview, 350);
    }

    async function preview() {
        if (!current() || !s.people.length) return;
        const seq = ++s.previewSeq;
        let result;
        try {
            result = await call('POST', Config.API.users.bulkOnboard(true), requestOf(s));
        } catch (error) {
            if (seq !== s.previewSeq || !current()) return;
            s.problem = serverMessage(error, 'Could not check the list. Try again.');
            drawConfirm();
            return;
        }
        if (seq !== s.previewSeq || !current()) return;
        s.result = result;
        drawPeople();
        drawConfirm();
    }

    /** U13-T09: one email per person while the onboarding email is on; otherwise none. */
    function emailNote() {
        return typeof Capabilities !== 'undefined' && Capabilities.has('onboardingEmail')
            ? 'Each person gets an email with what they can use and how to sign in.'
            : 'Access is ready; no email is sent.';
    }

    function drawConfirm() {
        const r = s.result;
        const count = r ? (r.newPeople || 0) + (r.existingPeople || 0) : 0;
        let html;
        if (!s.people.length) {
            html = '<b>Nobody to add yet.</b>';
        } else if (s.problem) {
            html = Utils.html`<b class="adp-problem">${s.problem}</b>`;
        } else if (!r) {
            html = '<span class="adp-note">Checking the list…</span>';
        } else {
            const skipped = (r.people || []).filter(p => p.outcome === 'SKIPPED');
            html = Utils.html`<b>${summaryText(r, s.role)}</b>${Utils.raw(skipped.length ? Utils.html`<span class="adp-skips">${skipped.length} skipped: ${
                skipped.map(p => `${p.email || p.displayName || 'someone'} (${String(p.reason || '').toLowerCase()})`).join(', ')}</span>` : '')}
                <span class="adp-note" id="adp-email-note">${emailNote()}</span>`;
        }
        $('#adp-summary').html(html);
        $('#adp-submit').prop('disabled', !r || !count || !!s.problem || s.sending)
            .text(`Add ${count || ''} ${count === 1 ? 'person' : 'people'}`.replace('  ', ' '));
    }

    async function submit() {
        if (s.sending || !s.result) return;
        s.sending = true;
        drawConfirm();
        try {
            const result = await call('POST', Config.API.users.bulkOnboard(false), requestOf(s));
            if (!current()) return;
            const added = (result.newPeople || 0) + (result.existingPeople || 0);
            s.people = [];
            Notifications.success(`Added ${added} ${added === 1 ? 'person' : 'people'}.`);
            window.location.hash = '#/user-management';
        } catch (error) {
            if (!current()) return;
            s.sending = false;
            s.problem = serverMessage(error, 'Nobody was added. Try again.');
            drawConfirm();
        }
    }

    // ---- events

    function bind() {
        $(document).off(NS)
            .on('input' + NS, '#adp-search', function() {
                const q = this.value.trim();
                clearTimeout(s.searchTimer);
                if (q.length < 2) { hideResults(); return; }
                s.searchTimer = setTimeout(() => {
                    call('GET', Config.API.directory.search(q)).then(list => {
                        if (current() && $('#adp-search').val().trim() === q) showResults(list);
                    }).catch(error => {
                        if (!current()) return;
                        $('#adp-results').html(Utils.html`<li class="adp-option-none" role="option" aria-disabled="true">${error && error.status === 409
                            ? 'Directory lookup is switched off. Add people by hand.' : 'The directory did not answer. Add people by hand.'}</li>`).prop('hidden', false);
                    });
                }, 250);
            })
            .on('keydown' + NS, '#adp-search, .adp-option', function(e) {
                const $options = $('#adp-results .adp-option');
                if (e.key === 'Escape') { hideResults(); $('#adp-search').trigger('focus'); return; }
                if (!$options.length) return;
                const i = $options.index(this);
                if (e.key === 'ArrowDown') { e.preventDefault(); $options.eq(Math.min(i + 1, $options.length - 1)).trigger('focus'); }
                else if (e.key === 'ArrowUp') { e.preventDefault(); if (i <= 0) $('#adp-search').trigger('focus'); else $options.eq(i - 1).trigger('focus'); }
                else if (e.key === 'Enter' && i >= 0) { e.preventDefault(); pickResult(Number(this.dataset.idx)); }
            })
            .on('click' + NS, '#add-people-view .adp-option', function() { pickResult(Number(this.dataset.idx)); })
            .on('submit' + NS, '#adp-manual', e => { e.preventDefault(); addManual(); })
            .on('change' + NS, '#adp-people [data-adp-field]', function() {
                const p = s.people.find(x => x.key === $(this).closest('tr').data('key').toString());
                if (p) { p[this.dataset.adpField] = this.value.trim(); changed(); }
            })
            .on('click' + NS, '#add-people-view [data-adp-remove]', function() {
                s.people = s.people.filter(p => p.key !== this.dataset.adpRemove);
                changed();
                $('#adp-email').trigger('focus');
            })
            .on('click' + NS, '#add-people-view [data-adp-role]', function() { selectRole(this.dataset.adpRole); })
            .on('keydown' + NS, '#add-people-view [data-adp-role]', function(e) {
                const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
                if (!step) return;
                e.preventDefault();
                const i = ROLES.findIndex(r => r[0] === this.dataset.adpRole);
                const next = ROLES[(i + step + ROLES.length) % ROLES.length][0];
                selectRole(next);
                $(`#add-people-view [data-adp-role="${next}"]`).trigger('focus');
            })
            .on('click' + NS, '#adp-add-env', openPicker)
            .on('change' + NS, '#adp-pick-env', function() { pickEnvironment(this.value); })
            .on('click' + NS, '#adp-pick-add', addAccessFromPicker)
            .on('click' + NS, '#adp-pick-cancel', () => { $('#adp-picker').prop('hidden', true).empty(); $('#adp-add-env').attr('aria-expanded', 'false').trigger('focus'); })
            .on('change' + NS, '#adp-access [data-adp-access]', function() {
                const a = s.access[Number($(this).closest('.adp-access-row').data('idx'))];
                if (a) { a[this.dataset.adpAccess] = this.value; changed(); }
            })
            .on('click' + NS, '#adp-access [data-adp-remove-access]', function() {
                s.access.splice(Number(this.dataset.adpRemoveAccess), 1);
                drawAccess();
                changed();
                $('#adp-add-env').trigger('focus');
            })
            .on('click' + NS, '#adp-cancel', function(e) {
                if (!s.people.length) return;
                e.preventDefault();
                Modals.confirm('Discard the list?', `${s.people.length} ${s.people.length === 1 ? 'person is' : 'people are'} in the list and will not be added.`,
                    () => { s.people = []; window.location.hash = '#/user-management'; }, { confirmText: 'Discard', confirmClass: 'btn-danger' });
            })
            .on('click' + NS, '#adp-submit', submit);
    }

    function selectRole(role) {
        s.role = role;
        $('#add-people-view [data-adp-role]').each(function() {
            const on = this.dataset.adpRole === role;
            this.setAttribute('aria-checked', String(on));
            this.setAttribute('tabindex', on ? '0' : '-1');
        });
        changed();
    }

    /** On the page frame (U18-T05) the people table and the right column scroll inside; Add people stays in view. */
    function watchColumns() {
        if (typeof Modals === 'undefined' || !Modals.watchScroll) return;
        const who = document.querySelector('#add-people-view .adp-who');
        const wrap = who && who.querySelector('.adp-table-wrap');
        if (wrap) Modals.watchScroll(wrap, who, 'People');
        const side = document.querySelector('#add-people-view .adp-side');
        if (side) Modals.watchScroll(side, side, 'Role and access');
    }

    return { load, model: { parseAddresses, summaryText, requestOf, roleLabel } };
})();

if (typeof window !== 'undefined') window.AddPeople = AddPeople;
