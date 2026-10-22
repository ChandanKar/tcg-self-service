/**
 * Grant access (U10-T06, GrantAccess.dc.html), #/access-management/grant[?environmentId=…]:
 * give one or more people access to one or more environments in one go.
 *   1 · People   chips from a search of app users (administrators also find people in the Entra
 *                directory, who are added to the app when picked), or a whole team as it is today
 *   2 · Environments  the ones you look after: whole, or only some groups
 *   3 · Access   View only / Operator / Admin, for 30 / 7 / 90 days, no end, or until a date
 *   4 · Let them know  a note (kept in the audit log) and "Email each person"
 * "What will happen" previews every person × environment as New / Extends / Upgrades / No change
 * (POST /access-grants/bulk/preview, 400 ms after the last change, the newest answer wins) and
 * "Grant access (N)" applies it (POST /access-grants/bulk).
 */
const GrantAccessPage = (function() {
    'use strict';

    const NS = '.grantAccess';
    const PREVIEW_DELAY = 400;
    const DAY_MS = 86400000;
    const DURATIONS = [['30', '30 days'], ['7', '7 days'], ['90', '90 days'], ['none', 'No end'], ['date', 'Until a date…']];
    const TAGS = { NEW: ['New', 'new'], EXTEND: ['Extends', 'ext'], UPGRADE: ['Upgrades', 'ext'], SKIP: ['No change', 'skip'] };

    // ---- pure model (node tests: src/test/js/grant-access.test.mjs) ----

    function shortDate(d) {
        return new Date(d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }

    /** Whole days from today to a picked date (YYYY-MM-DD), at least 1. */
    function daysUntil(isoDate, now) {
        const [y, m, d] = String(isoDate).split('-').map(Number);
        const n = now || new Date();
        const today = new Date(n.getFullYear(), n.getMonth(), n.getDate());
        return Math.round((new Date(y, m - 1, d) - today) / DAY_MS);
    }

    /** The request fields for "For": durationDays, clearExpiry, or an error to show. */
    function durationRequest(duration, dateValue, now) {
        if (duration === 'none') return { clearExpiry: true };
        if (duration === 'date') {
            if (!dateValue) return { error: 'Pick the date access ends.' };
            const days = daysUntil(dateValue, now);
            return days >= 1 ? { durationDays: days } : { error: 'Pick a date after today.' };
        }
        return { durationDays: Number(duration) };
    }

    function endsPhrase(row) {
        return row.resultExpiresAt ? `until ${shortDate(row.resultExpiresAt)}` : 'with no end date';
    }

    /** The detail line of one preview row. */
    function rowDetail(row) {
        const level = AccessLevels.label(row.resultLevel);
        const current = row.currentLevel ? AccessLevels.label(row.currentLevel) : '';
        switch (row.outcome) {
            case 'NEW': return `${level} on ${row.scopeType === 'GROUP' ? 'the ' + row.scopeName.replace(/^.*, /, '') : 'the whole environment'}, ${endsPhrase(row)}`;
            case 'EXTEND': return `Has ${current}${row.currentExpiresAt ? ' until ' + shortDate(row.currentExpiresAt) : ''}; becomes ${level} ${endsPhrase(row)}`;
            case 'UPGRADE': return `Has ${current}; becomes ${level} ${endsPhrase(row)}`;
            default: return row.reason || 'Nothing changes';
        }
    }

    function tagOf(outcome) {
        return TAGS[outcome] || TAGS.SKIP;
    }

    function summaryText(plan) {
        return `${plan.people} ${plan.people === 1 ? 'person' : 'people'} × ${plan.environments} environment${plan.environments === 1 ? '' : 's'}: `
            + `${plan.changes} change${plan.changes === 1 ? '' : 's'}, ${plan.skipped} skipped`;
    }

    function ctaText(plan) {
        return plan && plan.changes ? `Grant access (${plan.changes})` : 'Nothing to grant';
    }

    /** People who get something, for the toast. */
    function grantedPeople(plan) {
        return new Set((plan.rows || []).filter(r => r.outcome !== 'SKIP').map(r => r.userId)).size;
    }

    // ---- page ----

    let state = null;
    let previewSeq = 0;
    let previewTimer = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, { suppressGlobalError: true }).done(resolve).fail(reject);
        });
    }

    async function load(params) {
        const token = ContentRouter.token();
        ContentRouter.onLeave(() => { $(document).off(NS); clearTimeout(previewTimer); });
        state = { people: [], teamId: '', targets: new Map(), groups: {}, level: 'USER', duration: '30', dateValue: '',
            note: '', emailPeople: false, plan: null, problem: '', environments: [], teams: [], query: '', sending: false };
        Loading.showPage('Loading…');
        const [envs, teams] = await Promise.all([
            call('GET', Config.API.environments.listWithGroups).catch(() => []),
            call('GET', Config.API.teams.list()).catch(() => [])
        ]);
        if (!ContentRouter.isCurrent(token)) return;
        state.environments = (Array.isArray(envs) ? envs : []).filter(e => e.viewerCanManageAccess === true)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
        state.teams = (Array.isArray(teams) ? teams : []).filter(t => t.active !== false);
        const pre = params && params.environmentId;
        if (pre && state.environments.some(e => e.environmentId === pre)) state.targets.set(pre, { whole: true, groupIds: new Set() });
        // U13-T03: Users › Access opens this with the person already chosen.
        if (params && params.email) state.people.push({ email: params.email, name: params.name || params.email });
        render();
        bind();
        drawChips();
        drawEnvironments();
        schedulePreview();
    }

    function render() {
        const levels = AccessLevels.LEVELS.map(l => Utils.html`<button type="button" role="radio" class="ga-level"
                aria-checked="${state.level === l ? 'true' : 'false'}" data-ga-level="${l}"><b>${AccessLevels.label(l)}</b><span>${AccessLevels.description(l)}</span></button>`).join('');
        const durations = DURATIONS.map(([v, t]) => Utils.html`<option value="${v}" ${state.duration === v ? 'selected' : ''}>${t}</option>`).join('');
        const teams = ['<option value="">Choose a team</option>'].concat(state.teams.map(t => Utils.html`<option value="${t.teamId}">${t.displayName || t.name} (${t.memberCount} ${t.memberCount === 1 ? 'person' : 'people'})</option>`)).join('');
        $('#content-area').html(`
            <div class="content-view page page-frame ga-page" id="grant-access-view">
                ${PageHeader.markup({ title: 'Grant access', breadcrumb: [{ label: 'Access management', href: '#/access-management' }, { label: 'Grant access' }],
                    summaryHtml: 'Give one or more people access to one or more environments in one go.' })}
                <div class="ga-grid">
                    <div class="ga-col">
                        <section class="ga-card" aria-labelledby="ga-people-title">
                            <h2 id="ga-people-title">1 · People</h2>
                            <div class="ga-people" id="ga-people">
                                <div class="ga-chips" id="ga-chips"></div>
                                <label class="ga-people-search"><span class="visually-hidden">Add people by name or email</span>
                                    <input type="search" id="ga-person-search" placeholder="Add by name or email" autocomplete="off"
                                        role="combobox" aria-expanded="false" aria-controls="ga-person-menu" aria-autocomplete="list"></label>
                                <div class="ga-menu" id="ga-person-menu" role="listbox" hidden></div>
                            </div>
                            <label class="ga-label" for="ga-team">Or a whole team</label>
                            <select class="form-select" id="ga-team">${teams}</select>
                            <p class="ga-hint">A team adds its members as they are today; later joiners don't get this access.</p>
                        </section>
                        <section class="ga-card" aria-labelledby="ga-level-title">
                            <h2 id="ga-level-title">3 · Access</h2>
                            <div role="radiogroup" aria-label="Level" class="ga-levels">${levels}</div>
                            <label class="ga-label" for="ga-duration">For</label>
                            <div class="ga-for">
                                <select class="form-select" id="ga-duration" aria-label="How long">${durations}</select>
                                <input type="date" class="form-control" id="ga-date" aria-label="Access ends on" ${state.duration === 'date' ? '' : 'hidden'}>
                            </div>
                        </section>
                        <section class="ga-card" aria-labelledby="ga-note-title">
                            <h2 id="ga-note-title">4 · Let them know</h2>
                            <label class="ga-label" for="ga-note">Note <span class="ga-hint-inline">kept in the audit log</span></label>
                            <input type="text" class="form-control" id="ga-note" maxlength="500">
                            <label class="ga-check"><input type="checkbox" id="ga-email"> Email each person what they can now use</label>
                        </section>
                    </div>
                    <section class="ga-card ga-envs" aria-labelledby="ga-envs-title">
                        <div class="ga-envs-head">
                            <h2 id="ga-envs-title">2 · Environments</h2>
                            <label class="ga-env-search"><span class="visually-hidden">Search environments</span>
                                <input type="search" id="ga-env-search" placeholder="Search the environments you look after" autocomplete="off"></label>
                        </div>
                        <div class="ga-env-list" id="ga-env-list"></div>
                    </section>
                    <aside class="ga-card ga-preview" aria-labelledby="ga-preview-title">
                        <div class="ga-preview-head">
                            <h2 id="ga-preview-title">What will happen</h2>
                            <p id="ga-summary" aria-live="polite"></p>
                        </div>
                        <div class="ga-rows" id="ga-rows"></div>
                        <div class="ga-problem" id="ga-problem" role="alert"></div>
                        <div class="ga-foot">
                            <a href="#/access-management" class="ga-cancel">Cancel</a>
                            <button type="button" class="btn btn-primary" id="ga-grant" disabled>Nothing to grant</button>
                        </div>
                    </aside>
                </div>
            </div>`);
        drawChips();
        drawPreview();
        watchColumns();
    }

    /** On the page frame (U18-T05) each column scrolls inside; the preview's Grant button stays in view. */
    function watchColumns() {
        if (typeof Modals === 'undefined' || !Modals.watchScroll) return;
        const col = document.querySelector('#grant-access-view .ga-col');
        if (col) Modals.watchScroll(col, col, 'People, access and note');
        const envs = document.querySelector('#grant-access-view .ga-envs');
        if (envs) Modals.watchScroll(document.getElementById('ga-env-list'), envs, 'Environments');
        const preview = document.querySelector('#grant-access-view .ga-preview');
        if (preview) Modals.watchScroll(document.getElementById('ga-rows'), preview, 'What will happen');
    }

    function drawChips() {
        $('#ga-chips').html(state.people.map(p => Utils.html`<span class="ga-chip">${p.name}
            <button type="button" data-ga-remove="${p.email}" aria-label="Remove ${p.name}">×</button></span>`).join(''));
    }

    function envName(e) {
        return e.displayName || e.name;
    }

    /** The environment's power strip, as in the GrantAccess board's Environments list (U17-T10). */
    function stripHtml(e) {
        if (typeof PowerStrip === 'undefined' || !Array.isArray(e.groupStates) || !e.groupStates.length) return '';
        return `<span class="ga-env-strip">${PowerStrip.render(PowerStrip.fromGroupStates(e.groupStates))}</span>`;
    }

    function drawEnvironments() {
        const q = state.query.trim().toLowerCase();
        const list = state.environments.filter(e => !q || envName(e).toLowerCase().includes(q) || state.targets.has(e.environmentId));
        if (!state.environments.length) {
            $('#ga-env-list').html('<p class="ga-hint">You don\'t look after any environment, so there is nothing to grant.</p>');
            return;
        }
        $('#ga-env-list').html(list.length ? list.map(e => {
            const target = state.targets.get(e.environmentId);
            const groups = state.groups[e.environmentId];
            let detail = '';
            if (target) {
                detail = Utils.html`<div class="ga-part">
                    <label><input type="radio" name="ga-part-${e.environmentId}" value="whole" data-ga-part="${e.environmentId}" ${target.whole ? 'checked' : ''}> Whole environment</label>
                    <label><input type="radio" name="ga-part-${e.environmentId}" value="some" data-ga-part="${e.environmentId}" ${target.whole ? '' : 'checked'}> Only some groups</label>
                </div>`;
                if (!target.whole) {
                    detail += groups === undefined ? '<p class="ga-hint">Loading groups…</p>'
                        : !groups.length ? '<p class="ga-hint">This environment has no groups.</p>'
                            : Utils.html`<div class="ga-groups" role="group" aria-label="${envName(e)} groups">${Utils.raw(groups.map(g => Utils.html`
                                <label><input type="checkbox" data-ga-group="${g.groupId}" data-ga-env="${e.environmentId}" ${target.groupIds.has(g.groupId) ? 'checked' : ''}> ${g.displayName || g.name}</label>`).join(''))}</div>`;
                }
            }
            const part = !target ? '' : target.whole ? 'Whole environment' : target.groupIds.size ? 'Only some groups' : 'Choose groups';
            return Utils.html`<div class="ga-env${target ? ' is-on' : ''}">
                <label class="ga-env-row"><input type="checkbox" data-ga-env-pick="${e.environmentId}" ${target ? 'checked' : ''}>
                    <span><b>${envName(e)}</b>${Utils.raw(part ? Utils.html`<span class="ga-env-part">${part}</span>` : '')}</span>
                    ${Utils.raw(stripHtml(e))}</label>
                ${Utils.raw(detail)}
            </div>`;
        }).join('') : Utils.html`<p class="ga-hint">No environment matches "${state.query.trim()}".</p>`);
    }

    function drawPreview() {
        const plan = state.plan;
        $('#ga-problem').text(state.problem || '');
        if (!plan) {
            $('#ga-summary').text(state.people.length || state.teamId ? 'Choose at least one environment.' : 'Choose people and environments.');
            $('#ga-rows').html('');
        } else {
            $('#ga-summary').text(summaryText(plan));
            $('#ga-rows').html(plan.rows.map(r => {
                const [tag, tone] = tagOf(r.outcome);
                return Utils.html`<div class="ga-row" data-outcome="${r.outcome}">
                    <div class="ga-row-top"><span><b>${r.userName}</b> · ${r.scopeName}</span><span class="ga-tag" data-tone="${tone}">${tag}</span></div>
                    <div class="ga-row-detail">${rowDetail(r)}</div>
                </div>`;
            }).join(''));
        }
        const ready = plan && plan.changes > 0 && !state.problem && !state.sending;
        $('#ga-grant').prop('disabled', !ready).text(state.sending ? 'Granting…' : ctaText(plan));
    }

    /** The request, or null with the problem set when it is not complete. */
    function request() {
        state.problem = '';
        const targets = Array.from(state.targets.entries()).map(([environmentId, t]) => t.whole
            ? { environmentId, scopeType: 'ENVIRONMENT' }
            : { environmentId, scopeType: 'GROUP', groupIds: Array.from(t.groupIds) });
        if ((!state.people.length && !state.teamId) || !targets.length) return null;
        if (targets.some(t => t.scopeType === 'GROUP' && !t.groupIds.length)) {
            state.problem = 'Choose at least one group, or the whole environment.';
            return null;
        }
        const duration = durationRequest(state.duration, state.dateValue);
        if (duration.error) {
            state.problem = duration.error;
            return null;
        }
        return Object.assign({
            userEmails: state.people.map(p => p.email),
            teamIds: state.teamId ? [state.teamId] : [],
            targets,
            accessLevel: state.level,
            notes: state.note.trim() || null,
            emailPeople: state.emailPeople
        }, duration.durationDays ? { durationDays: duration.durationDays } : {}, duration.clearExpiry ? { clearExpiry: true } : {});
    }

    function schedulePreview() {
        clearTimeout(previewTimer);
        previewTimer = setTimeout(preview, PREVIEW_DELAY);
    }

    function preview() {
        const body = request();
        const seq = ++previewSeq;
        if (!body) {
            state.plan = null;
            drawPreview();
            return;
        }
        call('POST', Config.API.access.bulkPreview, body).then(plan => {
            if (seq !== previewSeq || !state) return;
            state.plan = plan;
            state.problem = '';
            drawPreview();
        }).catch(xhr => {
            if (seq !== previewSeq || !state) return;
            state.plan = null;
            state.problem = (xhr && xhr.responseJSON && xhr.responseJSON.message) || "The preview didn't load. Try again.";
            drawPreview();
        });
    }

    function grant() {
        const body = request();
        if (!body || !state.plan || !state.plan.changes) return;
        state.sending = true;
        drawPreview();
        call('POST', Config.API.access.bulkGrant, body).then(plan => {
            const n = grantedPeople(plan);
            Notifications.success(`Access granted to ${n} ${n === 1 ? 'person' : 'people'}.`);
            ContentRouter.navigate('access-management');
        }).catch(xhr => {
            state.sending = false;
            state.problem = (xhr && xhr.responseJSON && xhr.responseJSON.message) || 'Access was not granted. Try again.';
            drawPreview();
        });
    }

    // ---- people search ----

    async function searchPeople(q) {
        const users = await call('GET', Config.API.users.search(q)).catch(() => []);
        const taken = new Set(state.people.map(p => p.email.toLowerCase()));
        const items = (Array.isArray(users) ? users : []).filter(u => u.email && !taken.has(u.email.toLowerCase()))
            .slice(0, 8).map(u => ({ kind: 'app', email: u.email, name: u.displayName || u.email }));
        if (items.length || q.length < 3 || !Auth.isAdmin()) return items;
        // Administrators also find people in the Entra directory; picking one adds them to the app.
        const dir = await call('GET', Config.API.directory.search(q, 8)).catch(() => []);
        return (Array.isArray(dir) ? dir : []).filter(d => !d.alreadyInApp).slice(0, 8)
            .map(d => ({ kind: 'directory', directoryObjectId: d.directoryObjectId, email: d.email || d.userPrincipalName,
                name: d.displayName || d.email || d.userPrincipalName }));
    }

    let searchSeq = 0;
    async function onPersonInput() {
        const q = this.value.trim();
        const seq = ++searchSeq;
        const $menu = $('#ga-person-menu');
        if (q.length < 2) {
            $menu.prop('hidden', true).empty();
            $('#ga-person-search').attr('aria-expanded', 'false');
            return;
        }
        const items = await searchPeople(q);
        if (seq !== searchSeq) return;
        $menu.data('items', items).html(items.length ? items.map((it, i) => Utils.html`<button type="button" role="option" class="ga-option"
                data-ga-option="${i}"><span>${it.name}</span><span class="ga-option-email">${it.email}${it.kind === 'directory' ? ' · add to the app' : ''}</span></button>`).join('')
            : '<p class="ga-hint ga-menu-empty">Nobody matches.</p>').prop('hidden', false);
        $('#ga-person-search').attr('aria-expanded', 'true');
    }

    async function pickPerson(item) {
        let person = item;
        if (item.kind === 'directory') {
            try {
                const res = await call('POST', Config.API.users.list, { directoryObjectId: item.directoryObjectId });
                const user = res && res.user ? res.user : res;
                person = { email: user.email, name: user.displayName || user.email };
                Notifications.success(`${person.name} was added to the app.`);
            } catch (xhr) {
                Notifications.error((xhr && xhr.responseJSON && xhr.responseJSON.message) || `${item.name} could not be added to the app.`);
                return;
            }
        }
        if (!state.people.some(p => p.email.toLowerCase() === person.email.toLowerCase())) {
            state.people.push({ email: person.email, name: person.name });
        }
        $('#ga-person-search').val('').attr('aria-expanded', 'false').trigger('focus');
        $('#ga-person-menu').prop('hidden', true).empty();
        drawChips();
        schedulePreview();
    }

    function loadGroups(envId) {
        if (state.groups[envId] !== undefined) return;
        call('GET', Config.API.groups.list(envId)).then(groups => {
            if (!state) return;
            state.groups[envId] = (Array.isArray(groups) ? groups : []).sort((a, b) => (a.sequencePosition || 0) - (b.sequencePosition || 0));
            drawEnvironments();
        }).catch(() => {
            if (!state) return;
            state.groups[envId] = [];
            drawEnvironments();
        });
    }

    function bind() {
        $(document).off(NS)
            .on('input' + NS, '#ga-person-search', Utils.debounce(onPersonInput, 250))
            .on('keydown' + NS, '#ga-person-search', function(e) {
                if (e.key === 'Escape') { $('#ga-person-menu').prop('hidden', true); this.setAttribute('aria-expanded', 'false'); }
                if (e.key === 'ArrowDown') { e.preventDefault(); $('#ga-person-menu .ga-option').first().trigger('focus'); }
            })
            .on('keydown' + NS, '.ga-option', function(e) {
                if (e.key === 'ArrowDown') { e.preventDefault(); $(this).next('.ga-option').trigger('focus'); }
                if (e.key === 'ArrowUp') { e.preventDefault(); ($(this).prev('.ga-option').length ? $(this).prev('.ga-option') : $('#ga-person-search')).trigger('focus'); }
                if (e.key === 'Escape') { $('#ga-person-menu').prop('hidden', true); $('#ga-person-search').trigger('focus'); }
            })
            .on('click' + NS, '.ga-option', function() {
                const item = ($('#ga-person-menu').data('items') || [])[Number(this.getAttribute('data-ga-option'))];
                if (item) pickPerson(item);
            })
            .on('click' + NS, '[data-ga-remove]', function() {
                const email = this.getAttribute('data-ga-remove');
                state.people = state.people.filter(p => p.email !== email);
                drawChips();
                $('#ga-person-search').trigger('focus');
                schedulePreview();
            })
            .on('change' + NS, '#ga-team', function() { state.teamId = this.value; schedulePreview(); })
            .on('input' + NS, '#ga-env-search', function() { state.query = this.value; drawEnvironments(); })
            .on('change' + NS, '[data-ga-env-pick]', function() {
                const id = this.getAttribute('data-ga-env-pick');
                if (this.checked) state.targets.set(id, { whole: true, groupIds: new Set() });
                else state.targets.delete(id);
                drawEnvironments();
                schedulePreview();
            })
            .on('change' + NS, '[data-ga-part]', function() {
                const id = this.getAttribute('data-ga-part');
                const target = state.targets.get(id);
                if (!target) return;
                target.whole = this.value === 'whole';
                if (!target.whole) loadGroups(id);
                drawEnvironments();
                schedulePreview();
            })
            .on('change' + NS, '[data-ga-group]', function() {
                const target = state.targets.get(this.getAttribute('data-ga-env'));
                if (!target) return;
                if (this.checked) target.groupIds.add(this.getAttribute('data-ga-group'));
                else target.groupIds.delete(this.getAttribute('data-ga-group'));
                drawEnvironments();
                schedulePreview();
            })
            .on('click' + NS, '[data-ga-level]', function() {
                state.level = this.getAttribute('data-ga-level');
                $('[data-ga-level]').each(function() { this.setAttribute('aria-checked', String(this.getAttribute('data-ga-level') === state.level)); });
                schedulePreview();
            })
            .on('keydown' + NS, '[data-ga-level]', function(e) {
                if (!['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft'].includes(e.key)) return;
                e.preventDefault();
                const order = AccessLevels.LEVELS;
                const i = order.indexOf(state.level);
                const next = order[(i + (e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : order.length - 1)) % order.length];
                $(`[data-ga-level="${next}"]`).trigger('click').trigger('focus');
            })
            .on('change' + NS, '#ga-duration', function() {
                state.duration = this.value;
                $('#ga-date').prop('hidden', state.duration !== 'date');
                if (state.duration === 'date') $('#ga-date').trigger('focus');
                schedulePreview();
            })
            .on('change' + NS, '#ga-date', function() { state.dateValue = this.value; schedulePreview(); })
            .on('input' + NS, '#ga-note', function() { state.note = this.value; })
            .on('change' + NS, '#ga-email', function() { state.emailPeople = this.checked; })
            .on('click' + NS, '#ga-grant', grant)
            .on('click' + NS, function(e) {
                if (!$(e.target).closest('#ga-people').length) {
                    $('#ga-person-menu').prop('hidden', true);
                    $('#ga-person-search').attr('aria-expanded', 'false');
                }
            });
    }

    return { load, model: { durationRequest, daysUntil, rowDetail, tagOf, summaryText, ctaText, grantedPeople } };
})();

if (typeof window !== 'undefined') window.GrantAccessPage = GrantAccessPage;
