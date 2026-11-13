/**
 * VM Self-Service Platform - Add people (U13-T05, U25-T01 addpeople-mock Main.dc.html), #/user-management/add
 * Add colleagues before their first sign-in and give them access in the same step:
 *   1 · Who     the Microsoft directory (when lookup is on) and a box for typed or pasted addresses;
 *               every address becomes a chip with its status (new, already a user, not an email
 *               address, a mistyped domain with a one-click fix); how they sign in; optional
 *               department, team and project code for everyone
 *   2 · Role    User / Environment admin / Administrator for everyone above
 *   3 · Access  optional environments (or some of their groups), each with a level and how long
 * The summary bar at the foot previews the whole step on the server (POST /users/bulk-onboard?dryRun=true):
 * "Adding 2 people as Environment admin, with access to ISE (Operator, 30 days)." and who is skipped;
 * "Add N people" applies it in one go. The result replaces the form: for people who sign in with a
 * password, each person's generated password, shown once, with Copy all.
 */
const AddPeople = (function() {
    'use strict';

    const NS = '.addPeople';
    const ROLES = [
        ['USER', 'User', 'Uses the environments they are given'],
        ['ENV_ADMIN', 'Environment admin', 'Also approves access and runs environments'],
        ['ADMIN', 'Administrator', 'Everything, for every environment']
    ];
    const DURATIONS = [['7', '7 days'], ['30', '30 days'], ['90', '90 days'], ['', 'No end']];
    const LEVELS = [['VIEWER', 'View only'], ['USER', 'Operator'], ['ADMIN', 'Admin']];
    const EMAIL = /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/;
    /** A domain within this many edits of a well-used one (at least MIN_PEOPLE people) looks mistyped. */
    const MAX_EDITS = 2;
    const MIN_PEOPLE = 3;
    const PASSWORD_NOTE = 'Each person gets their own generated password, shown once after adding. They choose their own at first sign-in.';
    const COLLEAGUE_NOTE = 'These colleagues can use Microsoft sign-in; use a password only for people outside the directory.';
    const CROSS = '<svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"></path></svg>';

    let s = null; // page state

    // ---- pure model (node tests: src/test/js/add-people-parse.test.mjs) ----

    function cleanAddress(text) {
        return String(text || '').replace(/^[<"']+|[>"']+$/g, '').trim().toLowerCase();
    }

    /**
     * What typed or pasted text holds, once each: [{email, name, valid}]. Split on commas, semicolons and
     * new lines; inside a piece, every word with an @ is an address, and "Name <a@x.com>" keeps the name.
     * A piece with no @ at all is one entry that is not an address (it shows as such).
     */
    function parseEntries(text) {
        const out = [];
        const seen = new Set();
        const push = (address, name) => {
            const email = cleanAddress(address);
            if (!email || seen.has(email)) return;
            seen.add(email);
            out.push({ email, name: String(name || '').replace(/["']/g, '').trim(), valid: EMAIL.test(email) });
        };
        String(text || '').split(/[,;\n\r]+/).forEach(segment => {
            const part = segment.trim();
            if (!part) return;
            const named = Array.from(part.matchAll(/([^<>]*)<([^<>]+)>/g));
            if (named.length) {
                named.forEach(m => push(m[2], m[1]));
                return;
            }
            const withAt = part.split(/[\t ]+/).filter(t => t.includes('@'));
            if (withAt.length) withAt.forEach(t => push(t));
            else push(part);
        });
        return out;
    }

    /** The addresses in pasted text: split on commas, semicolons, spaces and new lines; "Name <a@x.com>" works too. */
    function parseAddresses(text) {
        return parseEntries(text).filter(e => e.valid).map(e => e.email);
    }

    /** Levenshtein distance. */
    function editDistance(a, b) {
        const x = String(a);
        const y = String(b);
        let prev = Array.from({ length: y.length + 1 }, (_, j) => j);
        for (let i = 1; i <= x.length; i++) {
            const row = [i];
            for (let j = 1; j <= y.length; j++) {
                row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (x[i - 1] === y[j - 1] ? 0 : 1));
            }
            prev = row;
        }
        return prev[y.length];
    }

    /**
     * The domain an address probably meant, or '': its own domain is not one people use, and it is
     * within two edits of a domain at least three active people use (GET /users/email-domains).
     * Without the domain list (the call failed) nothing looks mistyped.
     */
    function mistypedDomain(email, domains) {
        if (!Array.isArray(domains) || !domains.length) return '';
        const domain = String(email || '').split('@')[1] || '';
        if (!domain) return '';
        const known = domains.map(d => ({ domain: String(d && d.domain || '').toLowerCase(), count: Number(d && d.count) || 0 }));
        if (known.some(d => d.domain === domain)) return '';
        let best = '';
        let bestEdits = MAX_EDITS + 1;
        let bestCount = 0;
        known.forEach(d => {
            if (!d.domain || d.count < MIN_PEOPLE) return;
            const edits = editDistance(d.domain, domain);
            if (edits > 0 && edits <= MAX_EDITS && (edits < bestEdits || (edits === bestEdits && d.count > bestCount))) {
                best = d.domain;
                bestEdits = edits;
                bestCount = d.count;
            }
        });
        return best;
    }

    /** True when the address is in a domain other active people use (they can use Microsoft sign-in). */
    function colleagueDomain(email, domains) {
        const domain = String(email || '').split('@')[1] || '';
        return !!domain && Array.isArray(domains) && domains.some(d => d && String(d.domain || '').toLowerCase() === domain);
    }

    /**
     * A chip's status: {kind: new | existing | skipped | invalid | typo, note, fixTo}.
     * person: {email, valid, kept, directoryObjectId, alreadyInApp}; ctx: {domains, outcome (the
     * preview's people[] entry for this address), giving (access or a team goes to people already in the app)}.
     */
    function chipStatus(person, ctx) {
        const c = ctx || {};
        if (!person.valid) return { kind: 'invalid', note: 'not an email address' };
        if (!person.kept && !person.directoryObjectId) {
            const fixTo = mistypedDomain(person.email, c.domains);
            if (fixTo) return { kind: 'typo', note: 'domain looks mistyped', fixTo };
        }
        const outcome = c.outcome ? c.outcome.outcome : (person.alreadyInApp ? 'EXISTING' : '');
        if (outcome === 'EXISTING') return { kind: 'existing', note: c.giving ? 'already a user, access and team only' : 'already a user, skipped' };
        if (outcome === 'SKIPPED') {
            const reason = String(c.outcome.reason || '').replace(/\.$/, '');
            return { kind: 'skipped', note: reason ? `skipped: ${reason.charAt(0).toLowerCase()}${reason.slice(1)}` : 'skipped' };
        }
        return { kind: 'new', note: '' };
    }

    /** Addresses that need fixing are not sent. */
    function sendable(kind) {
        return kind !== 'invalid' && kind !== 'typo';
    }

    function roleLabel(role) {
        return (ROLES.find(r => r[0] === role) || ROLES[0])[1];
    }

    function levelLabel(level) {
        return (LEVELS.find(l => l[0] === level) || LEVELS[1])[1];
    }

    function daysLabel(days) {
        return (DURATIONS.find(d => d[0] === String(days || '')) || DURATIONS[1])[1];
    }

    function listText(items) {
        if (items.length < 2) return items.join('');
        return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
    }

    function grantName(grant, envName) {
        const name = envName(grant.environmentId);
        return grant.groupNames && grant.groupNames.length ? `${name}: ${grant.groupNames.join(', ')}` : name;
    }

    /** "ISE · Operator · 30 days" on an access chip. */
    function grantText(grant, envName) {
        return `${grantName(grant, envName)} · ${levelLabel(grant.level)} · ${daysLabel(grant.days)}`;
    }

    /**
     * The summary bar's sentence: "Adding 2 people as Environment admin, with access to ISE (Operator, 30 days)
     * and pay-qa (View only, 90 days)." / "…who can use every environment." / "…with no access yet."
     * count: new people; existing: people already in the app; giving: they get access or a team.
     */
    function summarySentence({ count, existing, giving, role, access, envName }) {
        const n = Number(count) || 0;
        if (!n) {
            const e = Number(existing) || 0;
            if (!e) return 'Nobody to add yet.';
            return `Nobody new to add. ${e} already ${e === 1 ? 'a user' : 'users'}: ${giving ? 'access and team only' : 'skipped'}.`;
        }
        const people = n === 1 ? '1 person' : `${n} people`;
        let tail;
        if (role === 'ADMIN') {
            tail = 'who can use every environment';
        } else if (access && access.length) {
            tail = `with access to ${listText(access.map(a => `${grantName(a, envName)} (${levelLabel(a.level)}, ${daysLabel(a.days).toLowerCase()})`))}`;
        } else {
            tail = 'with no access yet';
        }
        return `Adding ${people} as ${roleLabel(role)}, ${tail}.`;
    }

    /**
     * The line under the sentence: "1 already a user (skipped) · 1 address needs fixing (not added) ·
     * Passwords are shown once after adding."
     */
    function noteLine({ existing, giving, skipped, fixing, signIn, emailOn }) {
        const parts = [];
        const e = Number(existing) || 0;
        if (e) parts.push(`${e} already ${e === 1 ? 'a user' : 'users'} (${giving ? 'access and team only' : 'skipped'})`);
        const k = (skipped || []).length;
        if (k) parts.push(`${k} skipped (${skipped.map(p => `${p.email || p.displayName || 'someone'}: ${String(p.reason || '').replace(/\.$/, '').toLowerCase()}`).join(', ')})`);
        const f = Number(fixing) || 0;
        if (f) parts.push(f === 1 ? '1 address needs fixing (not added)' : `${f} addresses need fixing (not added)`);
        if (signIn === 'PASSWORD') parts.push('Passwords are shown once after adding.');
        else parts.push(emailOn ? 'Each person gets an email with what they can use and how to sign in.' : 'They sign in with Microsoft; no email is sent.');
        return parts.join(' · ');
    }

    /**
     * One person as sent: their own department, team and project code (person.own) win over the
     * details for everyone; an empty own value means "the value for everyone". A directory person's
     * department from the directory comes last.
     */
    function personOf(p, details) {
        const d = details || {};
        const own = p.own || {};
        return {
            directoryObjectId: p.directoryObjectId || null,
            email: p.email,
            displayName: p.name || null,
            department: own.department || d.department || p.directoryDepartment || null,
            projectCode: own.projectCode || d.projectCode || null,
            teamId: own.teamId || d.teamId || null
        };
    }

    /** True when the person has a department, team or project code of their own. */
    function hasOwnDetails(p) {
        const own = (p && p.own) || {};
        return !!(own.department || own.teamId || own.projectCode);
    }

    /** What is wrong with a name, department or project code (the server's own messages), or ''. */
    function detailsProblem({ name, department, projectCode }) {
        if (String(name || '').length > 255) return 'Name can be at most 255 characters.';
        if (String(department || '').length > 100) return 'Department can be at most 100 characters.';
        const code = String(projectCode || '');
        if (code.length > 50) return 'Project code can be at most 50 characters.';
        if (code && !/^[A-Za-z0-9._-]+$/.test(code)) return 'Project code can only use letters, digits, dot, dash and underscore.';
        return '';
    }

    /**
     * The request body for /users/bulk-onboard. state: {people (only those to send), role, access,
     * details: {department, teamId, projectCode} for everyone, signIn: MICROSOFT | PASSWORD}.
     * With a password each person gets their own, generated on the server and returned once.
     */
    function requestOf(state) {
        const d = state.details || {};
        return {
            people: state.people.map(p => {
                const person = personOf(p, d);
                if (state.signIn === 'PASSWORD') person.generatePassword = true;
                return person;
            }),
            role: state.role,
            access: state.role === 'ADMIN' ? [] : (state.access || []).map(a => ({
                environmentId: a.environmentId,
                scopeType: a.groupIds && a.groupIds.length ? 'GROUP' : 'ENVIRONMENT',
                groupIds: a.groupIds || [],
                accessLevel: a.level,
                durationDays: a.days ? Number(a.days) : null
            })),
            emailPeople: false
        };
    }

    /** The environments whose name holds the query (all of them for an empty one), by name. */
    function matchEnvironments(environments, query, exclude) {
        const q = String(query || '').trim().toLowerCase();
        return (environments || []).filter(e => !(exclude || []).includes(e.environmentId)
            && (!q || String(e.displayName || e.name || '').toLowerCase().includes(q) || String(e.name || '').toLowerCase().includes(q)));
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

    function has(capability) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(capability);
    }

    /** Passwords on screen that were not copied yet. */
    function passwordsAtRisk() {
        return !!(s && s.done && s.done.passwords.length && !s.done.copied);
    }

    function guardUnload(e) {
        if (s && ((!s.done && s.people.length) || passwordsAtRisk())) {
            e.preventDefault();
            e.returnValue = '';
        }
    }

    /** A link in the app while passwords are on screen: ask first (they can't be shown again). */
    function guardLinks(e) {
        if (!passwordsAtRisk()) return;
        const link = e.target instanceof Element ? e.target.closest('a[href^="#"]') : null;
        if (!link || link.getAttribute('href') === '#') return;
        e.preventDefault();
        e.stopPropagation();
        confirmLeave(() => { window.location.hash = link.getAttribute('href'); });
    }

    function confirmLeave(go) {
        if (!passwordsAtRisk()) { go(); return; }
        Modals.confirm('Leave without copying?', "The passwords can't be shown again.", () => {
            if (s && s.done) s.done.copied = true;
            go();
        }, { confirmText: 'Leave', confirmClass: 'btn-danger' });
    }

    function freshState(token) {
        return { token, people: [], role: 'USER', signIn: 'MICROSOFT', details: { department: '', teamId: '', projectCode: '' },
            detailsOpen: false, access: [], pick: null, pickGroups: null, level: 'USER', days: '30', envMatches: [], envActive: -1,
            teams: [], environments: [], groups: {}, domains: null, result: null, problem: '', previewSeq: 0, previewTimer: null,
            searchTimer: null, sending: false, results: [], nextKey: 1, done: null, envsFailed: null, editing: null, directory: has('directory'), passwordLogin: true };
    }

    async function load() {
        const token = ContentRouter.token();
        s = freshState(token);
        window.addEventListener('beforeunload', guardUnload);
        document.addEventListener('click', guardLinks, true);
        ContentRouter.onLeave(() => {
            window.removeEventListener('beforeunload', guardUnload);
            document.removeEventListener('click', guardLinks, true);
            $(document).off(NS);
            if (s) { clearTimeout(s.previewTimer); clearTimeout(s.searchTimer); }
        });
        Loading.showPage('Loading…');
        const [teams, envs, options, domains] = await Promise.all([
            call('GET', Config.API.teams.list()).catch(() => []),
            call('GET', Config.API.environments.list).catch(error => { s.envsFailed = error || {}; return []; }),
            call('GET', '/api/auth/options').catch(() => null),
            call('GET', Config.API.users.emailDomains).catch(() => null)
        ]);
        if (!current()) return;
        s.passwordLogin = !(options && options.passwordLoginEnabled === false);
        s.domains = Array.isArray(domains) ? domains : null;
        s.teams = (Array.isArray(teams) ? teams : []).filter(t => t.active !== false)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
        setEnvironments(envs);
        render();
        bind();
    }

    function setEnvironments(envs) {
        s.environments = (Array.isArray(envs) ? envs : []).filter(e => e.viewerCanManageAccess !== false && e.isActive !== false)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
    }

    /** Try again on the environments that didn't load; the rest of the form keeps what was entered. */
    async function reloadEnvironments() {
        s.envsFailed = null;
        const envs = await call('GET', Config.API.environments.list).catch(error => { s.envsFailed = error || {}; return []; });
        if (!current() || s.done) return;
        setEnvironments(envs);
        drawAccess();
    }

    function teamOptions(selected) {
        return ['<option value="">No team</option>'].concat(s.teams.map(t => Utils.html`<option value="${t.teamId}"${Utils.raw(t.teamId === selected ? ' selected' : '')}>${t.displayName || t.name}</option>`)).join('');
    }

    function render() {
        $('#content-area').html(`
            <div class="content-view page page-frame adp-page" id="add-people-view">
                ${PageHeader.markup({ title: 'Add people', breadcrumb: [{ label: 'Users', href: '#/user-management' }, { label: 'Add people' }],
                    summaryHtml: 'Add colleagues before their first sign-in and give them access in one go.' })}
                <div class="adp-body" id="adp-body"></div>
            </div>`);
        if (s.done) drawDone(); else drawForm();
    }

    function drawForm() {
        const roles = ROLES.map(([value, title, text]) => Utils.html`<button type="button" role="radio" class="adp-role" data-adp-role="${value}"
                aria-checked="${s.role === value ? 'true' : 'false'}" tabindex="${s.role === value ? '0' : '-1'}"><b>${title}</b><span>${text}</span></button>`).join('');
        const search = s.directory ? `<div class="adp-search">
                <label class="adp-label" for="adp-search">Find in the Microsoft directory</label>
                <input type="search" class="form-control" id="adp-search" autocomplete="off" placeholder="Name or email"
                       role="combobox" aria-expanded="false" aria-controls="adp-results" aria-autocomplete="list">
                <ul class="adp-results" id="adp-results" role="listbox" aria-label="People in the directory" hidden></ul>
            </div>` : '';
        const signIn = s.passwordLogin ? Utils.html`<fieldset class="adp-signin">
                <legend>Sign in with</legend>
                <label class="re-check"><input type="radio" name="adp-signin" value="MICROSOFT"${Utils.raw(s.signIn === 'MICROSOFT' ? ' checked' : '')}> Microsoft account</label>
                <label class="re-check"><input type="radio" name="adp-signin" value="PASSWORD"${Utils.raw(s.signIn === 'PASSWORD' ? ' checked' : '')}> Email and a password</label>
            </fieldset>
            <p class="adp-note adp-signin-note" id="adp-signin-note" hidden></p>` : '';
        const d = s.details;
        $('#adp-body').html(`
            <div class="adp-grid" id="adp-form">
                <section class="adp-card adp-who" aria-labelledby="adp-who-title">
                    <h2 id="adp-who-title"><span class="adp-step" aria-hidden="true">1</span>Who</h2>
                    ${search}
                    <label class="adp-label" for="adp-paste">Type or paste email addresses — one per line, or separated by commas</label>
                    <div class="adp-paste-row">
                        <textarea class="form-control" id="adp-paste" rows="2" autocomplete="off" spellcheck="false"
                                  placeholder="name@company.com, another@company.com"></textarea>
                        <button type="button" class="btn btn-outline-secondary" id="adp-paste-add">Add</button>
                    </div>
                    ${s.directory ? '' : '<p class="adp-note" id="adp-no-directory">Microsoft directory search is switched off on this server, so addresses are typed or pasted.</p>'}
                    <ul class="adp-chips" id="adp-chips" aria-label="People to add"></ul>
                    <div class="adp-person" id="adp-person" role="group" aria-labelledby="adp-person-title" hidden></div>
                    ${signIn}
                    <button type="button" class="adp-link adp-details-toggle" id="adp-details-toggle" aria-expanded="${s.detailsOpen ? 'true' : 'false'}"
                            aria-controls="adp-details"><span aria-hidden="true">${s.detailsOpen ? '▾' : '▸'}</span> Details for everyone (optional): department, team, project code</button>
                    <div class="adp-details" id="adp-details"${s.detailsOpen ? '' : ' hidden'}>
                        ${Utils.html`<label class="adp-field">Department<input type="text" class="form-control" id="adp-dept" maxlength="100" value="${d.department}"></label>`}
                        <label class="adp-field">Team<select class="form-select" id="adp-team">${teamOptions(d.teamId)}</select></label>
                        ${Utils.html`<label class="adp-field">Project code<input type="text" class="form-control" id="adp-project" maxlength="50" value="${d.projectCode}"></label>`}
                        <p class="adp-note adp-details-note">The same for everyone in the list, unless a person has their own (select their address to give them their own). People from the directory keep their directory department when this is empty.</p>
                        <div class="re-error adp-details-error" id="adp-details-error" role="alert" hidden></div>
                    </div>
                </section>
                <div class="adp-side">
                    <section class="adp-card adp-role-card" aria-labelledby="adp-role-title">
                        <h2 id="adp-role-title"><span class="adp-step" aria-hidden="true">2</span>Role for everyone above</h2>
                        <div class="adp-roles" role="radiogroup" aria-labelledby="adp-role-title">${roles}</div>
                    </section>
                    <section class="adp-card adp-access-card" aria-labelledby="adp-access-title">
                        <h2 id="adp-access-title"><span class="adp-step" aria-hidden="true">3</span>Access <span class="adp-optional">optional</span></h2>
                        <div class="adp-access-body" id="adp-access-body"></div>
                    </section>
                </div>
            </div>
            <div class="adp-bar" role="region" aria-label="Summary">
                <div class="adp-bar-text">
                    <div class="adp-summary" id="adp-summary" aria-live="polite"></div>
                    <div class="adp-note" id="adp-summary-note"></div>
                </div>
                <div class="adp-bar-actions">
                    <a class="btn btn-secondary" href="#/user-management" id="adp-cancel">Cancel</a>
                    <button type="button" class="btn btn-primary" id="adp-submit" disabled>Add people</button>
                </div>
            </div>`);
        drawChips();
        drawAccess();
        drawBar();
        watchColumns();
    }

    // ---- 1 · Who

    function outcomeOf(person) {
        if (!s.result || !s.result.people) return null;
        return s.result.people.find(r => r.email && r.email.toLowerCase() === person.email.toLowerCase()) || null;
    }

    /** People already in the app get the access and the team (as before), so they are not plain skipped then. */
    function giving() {
        return (s.role !== 'ADMIN' && s.access.length > 0) || !!s.details.teamId;
    }

    function statusOf(person) {
        return chipStatus(person, { domains: s.domains, outcome: outcomeOf(person), giving: giving() });
    }

    function toSend() {
        return s.people.filter(p => sendable(statusOf(p).kind));
    }

    /** The chip control that has focus, as a selector, so a redraw (a preview coming back) keeps it. */
    function focusedChipControl() {
        const list = document.getElementById('adp-chips');
        const active = document.activeElement;
        if (!list || !active || !list.contains(active)) return '';
        const attr = ['data-adp-edit', 'data-adp-remove', 'data-adp-fix', 'data-adp-keep'].find(a => active.hasAttribute(a));
        return attr ? `[${attr}="${active.getAttribute(attr)}"]` : '';
    }

    function drawChips() {
        const keep = focusedChipControl();
        $('#adp-chips').html(s.people.map(p => {
            const st = statusOf(p);
            const fix = st.fixTo ? Utils.html`<button type="button" class="adp-fix" data-adp-fix="${p.key}" data-to="${st.fixTo}">Use ${st.fixTo}</button> <button type="button" class="adp-fix" data-adp-keep="${p.key}" aria-label="Keep ${p.email} as typed">Keep</button>` : '';
            const own = hasOwnDetails(p) ? ' <span class="adp-chip-note">· own details</span>' : '';
            const label = Utils.html`${Utils.raw(p.name ? Utils.html`<b>${p.name}</b> ` : '')}<span class="adp-chip-email">${p.email}</span>`;
            const who = p.valid ? Utils.html`<button type="button" class="adp-chip-edit" data-adp-edit="${p.key}" aria-expanded="${s.editing === p.key ? 'true' : 'false'}"
                    aria-controls="adp-person"><span class="visually-hidden">Edit </span>${Utils.raw(label)}</button>` : label;
            return Utils.html`<li class="adp-chip adp-chip-${st.kind}" data-key="${p.key}">
                ${Utils.raw(who)}${Utils.raw(own)}${Utils.raw(st.note ? Utils.html` <span class="adp-chip-note">· ${st.note}</span>` : '')}${Utils.raw(fix ? ` ${fix}` : '')}
                <button type="button" class="adp-x" data-adp-remove="${p.key}" aria-label="Remove ${p.email}">${Utils.raw(CROSS)}</button></li>`;
        }).join('')).prop('hidden', !s.people.length);
        if (keep) $('#adp-chips').find(keep).trigger('focus');
        drawSignInNote();
    }

    function drawSignInNote() {
        const $note = $('#adp-signin-note');
        if (!$note.length) return;
        const password = s.signIn === 'PASSWORD';
        const colleagues = password && toSend().some(p => p.directoryObjectId || colleagueDomain(p.email, s.domains));
        $note.text(password ? `${PASSWORD_NOTE}${colleagues ? ` ${COLLEAGUE_NOTE}` : ''}` : '').prop('hidden', !password);
    }

    /** Adds people not in the list yet; returns how many were new to the list. */
    function addPeople(list) {
        let added = 0;
        list.forEach(p => {
            if (s.people.some(x => x.email === p.email)) return;
            s.people.push(Object.assign({ key: String(s.nextKey++), name: '', directoryDepartment: '', own: { department: '', teamId: '', projectCode: '' }, kept: false }, p));
            added++;
        });
        if (added) changed();
        return added;
    }

    /** The whole box becomes chips (Add, leaving the box, a paste). */
    function takeDraft() {
        const $paste = $('#adp-paste');
        const text = $paste.val();
        if (!String(text || '').trim()) return;
        $paste.val('');
        addPeople(parseEntries(text));
    }

    /** While typing: what is complete (up to the last comma, semicolon or new line, or an address and a space) becomes chips. */
    function takeTyped(el) {
        const value = el.value;
        const cut = Math.max(value.lastIndexOf(','), value.lastIndexOf(';'), value.lastIndexOf('\n'));
        let done = cut >= 0 ? value.slice(0, cut) : '';
        let rest = cut >= 0 ? value.slice(cut + 1) : value;
        if (/\s$/.test(rest) && !rest.includes('<') && EMAIL.test(cleanAddress(rest))) {
            done += `,${rest}`;
            rest = '';
        }
        if (!done.trim()) return;
        el.value = rest.replace(/^\s+/, '');
        addPeople(parseEntries(done));
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
        addPeople([{ name: u.displayName || '', email: String(u.email).toLowerCase(), valid: true, directoryDepartment: u.department || '',
            directoryObjectId: u.directoryObjectId, alreadyInApp: !!u.alreadyInApp }]);
        $('#adp-search').val('').trigger('focus');
        hideResults();
    }

    function fixDomain(key, domain) {
        const p = s.people.find(x => x.key === key);
        if (!p) return;
        const email = `${p.email.split('@')[0]}@${domain}`;
        if (s.people.some(x => x.email === email && x.key !== key)) s.people = s.people.filter(x => x.key !== key);
        else p.email = email;
        changed();
    }

    /** One person's own name, department, team and project code, in a panel under the chips. */
    function openPerson(key) {
        const p = s.people.find(x => x.key === key);
        if (!p) return;
        s.editing = key;
        const d = s.details;
        const everyone = value => (value ? `For everyone: ${value}` : 'Same as for everyone');
        const teamName = id => { const t = s.teams.find(x => x.teamId === id); return t ? (t.displayName || t.name) : ''; };
        const deptHint = d.department ? everyone(d.department) : (p.directoryDepartment ? `From the directory: ${p.directoryDepartment}` : everyone(''));
        const teams = s.teams.map(t => Utils.html`<option value="${t.teamId}"${Utils.raw(t.teamId === p.own.teamId ? ' selected' : '')}>${t.displayName || t.name}</option>`).join('');
        $('#adp-person').html(Utils.html`<h3 id="adp-person-title">Details for ${p.email}</h3>
                <div class="adp-person-grid">
                    <label class="adp-field">Name<input type="text" class="form-control" id="adp-person-name" maxlength="255" value="${p.name}" autocomplete="off"></label>
                    <label class="adp-field">Department<input type="text" class="form-control" id="adp-person-dept" maxlength="100" value="${p.own.department}" placeholder="${deptHint}"></label>
                    <label class="adp-field">Team<select class="form-select" id="adp-person-team"><option value="">${d.teamId ? everyone(teamName(d.teamId)) : everyone('')}</option>${Utils.raw(teams)}</select></label>
                    <label class="adp-field">Project code<input type="text" class="form-control" id="adp-person-project" maxlength="50" value="${p.own.projectCode}" placeholder="${everyone(d.projectCode)}"></label>
                </div>
                <p class="adp-note">Empty fields use the details for everyone.</p>
                <div class="re-error" id="adp-person-error" role="alert" hidden></div>
                <div class="adp-person-actions">
                    <button type="button" class="btn btn-sm btn-primary" id="adp-person-save">Done</button>
                    <button type="button" class="btn btn-sm btn-secondary" id="adp-person-cancel">Cancel</button>
                </div>`).prop('hidden', false);
        drawChips();
        $('#adp-person-name').trigger('focus');
    }

    /** Closes the panel; focus goes back to the person's chip. */
    function closePerson() {
        const key = s.editing;
        s.editing = null;
        $('#adp-person').prop('hidden', true).empty();
        drawChips();
        if (key) $(`#adp-chips [data-adp-edit="${key}"]`).trigger('focus');
    }

    function savePerson() {
        const p = s.people.find(x => x.key === s.editing);
        if (!p) { closePerson(); return; }
        const value = id => String($(id).val() || '').trim();
        const next = { name: value('#adp-person-name'), department: value('#adp-person-dept'), teamId: $('#adp-person-team').val() || '',
            projectCode: value('#adp-person-project') };
        const problem = detailsProblem(next);
        if (problem) {
            $('#adp-person-error').text(problem).prop('hidden', false);
            return;
        }
        p.name = next.name;
        p.own = { department: next.department, teamId: next.teamId, projectCode: next.projectCode };
        changed();
        closePerson();
    }

    // ---- 3 · Access

    function envName(id) {
        const e = s.environments.find(x => x.environmentId === id);
        return e ? (e.displayName || e.name) : id;
    }

    function envMeta(e) {
        const vms = Number(e.vmCount) || 0;
        if (!vms) return 'no VMs';
        const running = Number(e.runningVmCount) || 0;
        return `${vms} ${vms === 1 ? 'VM' : 'VMs'}${running ? ` · ${running} running` : ''}`;
    }

    function drawAccess() {
        const $body = $('#adp-access-body');
        if (s.role === 'ADMIN') {
            $body.html('<p class="adp-note adp-admin-note" id="adp-admin-note">Administrators can use every environment, so there is nothing to give here.</p>');
            return;
        }
        if (s.envsFailed) {
            $body.html(UiStates.failed({ what: 'the environments', xhr: s.envsFailed, onRetry: reloadEnvironments }));
            return;
        }
        const levels = LEVELS.map(([v, t]) => Utils.html`<option value="${v}"${Utils.raw(s.level === v ? ' selected' : '')}>${typeof AccessLevels !== 'undefined' ? AccessLevels.label(v) : t}</option>`).join('');
        const days = DURATIONS.map(([v, t]) => Utils.html`<option value="${v}"${Utils.raw(s.days === v ? ' selected' : '')}>${t}</option>`).join('');
        const count = s.environments.length;
        $body.html(Utils.html`<div class="adp-grant-row">
                <div class="adp-env-field">
                    <label class="adp-label" for="adp-env">Environment</label>
                    <input type="text" class="form-control" id="adp-env" autocomplete="off" role="combobox" aria-expanded="false"
                           aria-controls="adp-env-list" aria-autocomplete="list" placeholder="Search ${count} ${count === 1 ? 'environment' : 'environments'}"
                           value="${s.pick ? envName(s.pick) : ''}">
                    <ul class="adp-results adp-env-list" id="adp-env-list" role="listbox" aria-label="Environments" hidden></ul>
                </div>
                <div class="adp-grant-field"><label class="adp-label" for="adp-level">Level</label>
                    <select class="form-select" id="adp-level">${Utils.raw(levels)}</select></div>
                <div class="adp-grant-field"><label class="adp-label" for="adp-days">For</label>
                    <select class="form-select" id="adp-days">${Utils.raw(days)}</select></div>
                <button type="button" class="btn btn-outline-secondary" id="adp-grant-add"${Utils.raw(s.pick ? '' : ' disabled')}>Add</button>
            </div>
            <div class="adp-groups-slot" id="adp-groups-slot"></div>
            <ul class="adp-chips" id="adp-grants" aria-label="Access to give"></ul>
            <p class="adp-note" id="adp-no-access">No access yet — people can ask for it later.</p>
            <div class="adp-warn" id="adp-envadmin-warn" role="status" hidden>
                <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5 15 14H1z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"></path><path d="M8 6v4M8 11.6v.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"></path></svg>
                <span>Environment admins manage only the environments you give them. Add at least one with the Admin level.</span>
            </div>`);
        drawGroupsSlot();
        drawGrants();
    }

    function drawGrants() {
        $('#adp-grants').html(s.access.map((a, i) => Utils.html`<li class="adp-chip adp-chip-grant">
                <b>${grantName(a, envName)}</b> <span>·${levelLabel(a.level)} · ${daysLabel(a.days)}</span>
                <button type="button" class="adp-x" data-adp-remove-access="${i}" aria-label="Remove access to ${grantName(a, envName)}">${Utils.raw(CROSS)}</button></li>`).join(''))
            .prop('hidden', !s.access.length);
        $('#adp-no-access').prop('hidden', s.access.length > 0);
        $('#adp-envadmin-warn').prop('hidden', !(s.role === 'ENV_ADMIN' && !s.access.some(a => a.level === 'ADMIN')));
    }

    /** "Some groups…" once an environment with more than one group is chosen; the groups load on opening. */
    function drawGroupsSlot() {
        const $slot = $('#adp-groups-slot');
        const env = s.pick && s.environments.find(e => e.environmentId === s.pick);
        if (!env || (Number(env.groupCount) || 0) < 2) { $slot.empty(); return; }
        if (!s.pickGroups) {
            $slot.html('<button type="button" class="adp-link" id="adp-groups-toggle" aria-expanded="false" aria-controls="adp-pick-groups">Some groups…</button>');
            return;
        }
        const groups = s.pickGroups.filter(g => g.name !== 'discovered');
        $slot.html(Utils.html`<fieldset class="adp-groups" id="adp-pick-groups"><legend>Only some groups (optional)</legend>${Utils.raw(groups.map(g =>
            Utils.html`<label class="re-check"><input type="checkbox" value="${g.groupId}" data-name="${g.displayName || g.name}"> ${g.displayName || g.name}</label>`).join('')
            || '<p class="adp-note">This environment has no groups to choose from.</p>')}</fieldset>`);
    }

    async function openGroups() {
        const id = s.pick;
        if (!id) return;
        if (!s.groups[id]) {
            s.groups[id] = await call('GET', Config.API.groups.list(id)).catch(() => []) || [];
            if (!current() || s.pick !== id) return;
        }
        s.pickGroups = s.groups[id];
        drawGroupsSlot();
        $('#adp-pick-groups input').first().trigger('focus');
    }

    function showEnvList() {
        const $input = $('#adp-env');
        if (!$input.length) return;
        s.envMatches = matchEnvironments(s.environments, s.pick ? '' : $input.val(), s.access.map(a => a.environmentId)).slice(0, 100);
        if (s.envActive >= s.envMatches.length) s.envActive = s.envMatches.length - 1;
        $('#adp-env-list').html(s.envMatches.length ? s.envMatches.map((e, i) => Utils.html`<li role="option" class="adp-option" id="adp-env-opt-${i}" data-idx="${i}"
                aria-selected="${i === s.envActive ? 'true' : 'false'}"><b>${e.displayName || e.name}</b> <span>${envMeta(e)}</span></li>`).join('')
            : '<li class="adp-option-none" role="option" aria-disabled="true">No environment matches</li>').prop('hidden', false);
        $input.attr('aria-expanded', 'true');
        if (s.envActive >= 0) {
            $input.attr('aria-activedescendant', `adp-env-opt-${s.envActive}`);
            const el = document.getElementById(`adp-env-opt-${s.envActive}`);
            if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
        } else {
            $input.removeAttr('aria-activedescendant');
        }
    }

    function hideEnvList() {
        s.envActive = -1;
        $('#adp-env-list').prop('hidden', true).empty();
        $('#adp-env').attr('aria-expanded', 'false').removeAttr('aria-activedescendant');
    }

    function pickEnvironment(i) {
        const e = s.envMatches[i];
        if (!e) return;
        s.pick = e.environmentId;
        s.pickGroups = null;
        $('#adp-env').val(e.displayName || e.name);
        hideEnvList();
        $('#adp-grant-add').prop('disabled', false);
        drawGroupsSlot();
    }

    function addGrant() {
        if (!s.pick) return;
        const checked = $('#adp-pick-groups input:checked').get();
        s.access.push({ environmentId: s.pick, groupIds: checked.map(c => c.value), groupNames: checked.map(c => c.dataset.name),
            level: s.level, days: s.days });
        s.pick = null;
        s.pickGroups = null;
        $('#adp-env').val('');
        $('#adp-grant-add').prop('disabled', true);
        drawGroupsSlot();
        drawGrants();
        changed();
        $('#adp-env').trigger('focus');
    }

    // ---- summary bar

    function changed() {
        s.result = null;
        s.problem = '';
        drawChips();
        drawBar();
        clearTimeout(s.previewTimer);
        if (toSend().length) s.previewTimer = setTimeout(preview, 350);
    }

    function request() {
        return requestOf({ people: toSend(), role: s.role, access: s.access, details: s.details, signIn: s.signIn });
    }

    async function preview() {
        if (!current() || s.done || !toSend().length) return;
        const seq = ++s.previewSeq;
        let result;
        try {
            result = await call('POST', Config.API.users.bulkOnboard(true), request());
        } catch (error) {
            if (seq !== s.previewSeq || !current()) return;
            s.problem = serverMessage(error, 'Could not check the list. Try again.');
            drawBar();
            return;
        }
        if (seq !== s.previewSeq || !current()) return;
        s.result = result;
        drawChips();
        drawBar();
    }

    function drawBar() {
        const r = s.result;
        const sending = toSend();
        const fixing = s.people.length - sending.length;
        const newCount = r ? (r.newPeople || 0) : 0;
        const existing = r ? (r.existingPeople || 0) : 0;
        const give = giving();
        let summary;
        if (!sending.length) summary = 'Nobody to add yet.';
        else if (s.problem) summary = '';
        else if (!r) summary = 'Checking the list…';
        else summary = summarySentence({ count: newCount, existing, giving: give, role: s.role, access: s.access, envName });
        const note = !s.people.length ? '' : noteLine({ existing, giving: give, skipped: r ? (r.people || []).filter(p => p.outcome === 'SKIPPED') : [],
            fixing, signIn: s.signIn, emailOn: has('onboardingEmail') });
        $('#adp-summary').html(s.problem ? Utils.html`<span class="adp-problem">${s.problem}</span>` : Utils.html`${summary}`);
        $('#adp-summary-note').text(note);
        const updateOnly = !newCount && existing && give;
        const label = newCount ? `Add ${newCount} ${newCount === 1 ? 'person' : 'people'}`
            : updateOnly ? `Update ${existing} ${existing === 1 ? 'person' : 'people'}` : 'Add people';
        $('#adp-submit').prop('disabled', !r || (!newCount && !updateOnly) || !!s.problem || s.sending || !sending.length).text(label);
    }

    async function submit() {
        if (s.sending || !s.result) return;
        s.sending = true;
        drawBar();
        const signIn = s.signIn;
        const give = giving();
        try {
            const result = await call('POST', Config.API.users.bulkOnboard(false), request());
            if (!current()) return;
            s.sending = false;
            s.done = {
                result, signIn, giving: give, copied: false,
                passwords: (result.people || []).filter(p => p.initialPassword).map(p => ({ email: p.email, password: p.initialPassword }))
            };
            s.people = [];
            s.result = null;
            drawDone();
        } catch (error) {
            if (!current()) return;
            s.sending = false;
            s.problem = serverMessage(error, 'Nobody was added. Try again.');
            drawBar();
        }
    }

    // ---- the result, once

    function drawDone() {
        const d = s.done;
        const r = d.result || {};
        const n = r.newPeople || 0;
        const existing = r.existingPeople || 0;
        const skipped = r.skippedPeople || 0;
        const title = n ? `${n === 1 ? '1 person' : `${n} people`} added`
            : existing && d.giving ? `${existing === 1 ? '1 person' : `${existing} people`} updated` : 'Nobody was added';
        const more = [];
        if (existing) more.push(`${existing} already ${existing === 1 ? 'a user' : 'users'} (${d.giving ? 'access and team only' : 'skipped'})`);
        if (skipped) more.push(`${skipped} skipped`);
        const passwords = d.passwords.length ? Utils.html`<p>Their username is their email. Give each person their password:</p>
                <table class="adp-pass-table" id="adp-passwords"><thead><tr><th scope="col">Email (username)</th><th scope="col">Password</th></tr></thead>
                <tbody>${Utils.raw(d.passwords.map(p => Utils.html`<tr><td>${p.email}</td><td class="adp-mono">${p.password}</td></tr>`).join(''))}</tbody></table>
                <p class="adp-once">Shown once. Pass each password on; they choose their own at first sign-in.</p>
                <p class="adp-note" id="adp-copy-note" role="status"></p>` : '';
        const microsoft = n && d.signIn !== 'PASSWORD' ? '<p>They are marked unverified until they first sign in with Microsoft.</p>' : '';
        $('#adp-body').html(Utils.html`<section class="adp-card adp-done" aria-labelledby="adp-done-title">
                <h2 id="adp-done-title" tabindex="-1">${title}</h2>
                ${Utils.raw(more.length ? Utils.html`<p class="adp-note">${more.join(' · ')}</p>` : '')}
                ${Utils.raw(passwords)}${Utils.raw(microsoft)}
                <div class="adp-done-actions">
                    ${Utils.raw(d.passwords.length ? '<button type="button" class="btn btn-outline-secondary" id="adp-copy">Copy all</button>' : '')}
                    <button type="button" class="btn btn-secondary" id="adp-more">Add more people</button>
                    <a class="btn btn-primary" href="#/user-management" id="adp-back">Back to Users</a>
                </div>
            </section>`);
        $('#adp-done-title').trigger('focus');
    }

    function copyPasswords() {
        const d = s.done;
        if (!d) return;
        const text = d.passwords.map(p => `${p.email}\t${p.password}`).join('\n');
        const done = () => { d.copied = true; $('#adp-copy-note').text('Copied.'); };
        const fail = () => {
            const table = document.getElementById('adp-passwords');
            const selection = window.getSelection && window.getSelection();
            if (table && selection && document.createRange) {
                const range = document.createRange();
                range.selectNodeContents(table);
                selection.removeAllRanges();
                selection.addRange(range);
            }
            $('#adp-copy-note').text("Couldn't copy. The table is selected: press Ctrl+C to copy it.");
        };
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fail);
        else fail();
    }

    function startOver() {
        s.done = null;
        s.editing = null;
        s.people = [];
        s.access = [];
        s.pick = null;
        s.pickGroups = null;
        s.result = null;
        s.problem = '';
        drawForm();
        $('#adp-paste').trigger('focus');
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
                            ? 'Directory lookup is switched off. Type or paste the addresses.' : 'The directory did not answer. Type or paste the addresses.'}</li>`).prop('hidden', false);
                    });
                }, 250);
            })
            .on('keydown' + NS, '#adp-search, #adp-results .adp-option', function(e) {
                const $options = $('#adp-results .adp-option');
                if (e.key === 'Escape') { hideResults(); $('#adp-search').trigger('focus'); return; }
                if (!$options.length) return;
                const i = $options.index(this);
                if (e.key === 'ArrowDown') { e.preventDefault(); $options.eq(Math.min(i + 1, $options.length - 1)).trigger('focus'); }
                else if (e.key === 'ArrowUp') { e.preventDefault(); if (i <= 0) $('#adp-search').trigger('focus'); else $options.eq(i - 1).trigger('focus'); }
                else if (e.key === 'Enter' && i >= 0) { e.preventDefault(); pickResult(Number(this.dataset.idx)); }
            })
            .on('click' + NS, '#adp-results .adp-option', function() { pickResult(Number(this.dataset.idx)); })
            .on('input' + NS, '#adp-paste', function(e) {
                const native = e.originalEvent;
                if (native && native.inputType === 'insertFromPaste') takeDraft();
                else takeTyped(this);
            })
            .on('blur' + NS, '#adp-paste', takeDraft)
            .on('click' + NS, '#adp-paste-add', () => { takeDraft(); $('#adp-paste').trigger('focus'); })
            .on('click' + NS, '#adp-chips [data-adp-remove]', function() {
                if (s.editing === this.dataset.adpRemove) { s.editing = null; $('#adp-person').prop('hidden', true).empty(); }
                s.people = s.people.filter(p => p.key !== this.dataset.adpRemove);
                changed();
                $('#adp-paste').trigger('focus');
            })
            .on('click' + NS, '#adp-chips [data-adp-fix]', function() { fixDomain(this.dataset.adpFix, this.dataset.to); })
            .on('click' + NS, '#adp-chips [data-adp-keep]', function() {
                const p = s.people.find(x => x.key === this.dataset.adpKeep);
                if (p) { p.kept = true; changed(); }
            })
            .on('change' + NS, 'input[name="adp-signin"]', function() {
                if (!this.checked) return;
                s.signIn = this.value;
                changed();
            })
            .on('click' + NS, '#adp-details-toggle', function() {
                s.detailsOpen = !s.detailsOpen;
                this.setAttribute('aria-expanded', String(s.detailsOpen));
                $(this).find('span').first().text(s.detailsOpen ? '▾' : '▸');
                $('#adp-details').prop('hidden', !s.detailsOpen);
            })
            .on('input' + NS + ' change' + NS, '#adp-dept, #adp-team, #adp-project', function() {
                const field = { 'adp-dept': 'department', 'adp-team': 'teamId', 'adp-project': 'projectCode' }[this.id];
                const value = String(this.value || '').trim();
                const problem = detailsProblem({ [field]: value });
                $('#adp-details-error').text(problem).prop('hidden', !problem);
                if (problem) return;
                s.details[field] = value;
                changed();
            })
            .on('click' + NS, '#adp-chips [data-adp-edit]', function() {
                if (s.editing === this.dataset.adpEdit) closePerson();
                else openPerson(this.dataset.adpEdit);
            })
            .on('click' + NS, '#adp-person-save', savePerson)
            .on('click' + NS, '#adp-person-cancel', closePerson)
            .on('keydown' + NS, '#adp-person', e => {
                if (e.key === 'Escape') { e.preventDefault(); closePerson(); }
                else if (e.key === 'Enter' && e.target.tagName === 'INPUT') { e.preventDefault(); savePerson(); }
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
            .on('input' + NS, '#adp-env', function() {
                if (s.pick) {
                    s.pick = null;
                    s.pickGroups = null;
                    $('#adp-grant-add').prop('disabled', true);
                    drawGroupsSlot();
                }
                s.envActive = -1;
                showEnvList();
            })
            .on('click' + NS, '#adp-env', function() { if (!s.pick && $('#adp-env-list').prop('hidden')) showEnvList(); })
            .on('keydown' + NS, '#adp-env', function(e) {
                const open = !$('#adp-env-list').prop('hidden');
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                    e.preventDefault();
                    if (!open) { s.envActive = -1; showEnvList(); }
                    if (!s.envMatches.length) return;
                    s.envActive = e.key === 'ArrowDown' ? Math.min(s.envActive + 1, s.envMatches.length - 1) : Math.max(s.envActive - 1, 0);
                    showEnvList();
                } else if (e.key === 'Enter') {
                    e.preventDefault();
                    const i = s.envActive >= 0 ? s.envActive : (s.envMatches.length === 1 ? 0 : -1);
                    if (open && i >= 0) pickEnvironment(i);
                    else if (s.pick) addGrant();
                } else if (e.key === 'Escape' && open) {
                    e.preventDefault();
                    hideEnvList();
                }
            })
            .on('mousedown' + NS, '#adp-env-list .adp-option', e => e.preventDefault())
            .on('click' + NS, '#adp-env-list .adp-option', function() { pickEnvironment(Number(this.dataset.idx)); })
            .on('click' + NS, e => {
                if (!$(e.target).closest('.adp-env-field').length) hideEnvList();
            })
            .on('change' + NS, '#adp-level', function() { s.level = this.value; })
            .on('change' + NS, '#adp-days', function() { s.days = this.value; })
            .on('click' + NS, '#adp-groups-toggle', openGroups)
            .on('click' + NS, '#adp-grant-add', addGrant)
            .on('click' + NS, '#adp-grants [data-adp-remove-access]', function() {
                s.access.splice(Number(this.dataset.adpRemoveAccess), 1);
                drawGrants();
                changed();
                $('#adp-env').trigger('focus');
            })
            .on('click' + NS, '#adp-cancel', function(e) {
                if (!s.people.length) return;
                e.preventDefault();
                Modals.confirm('Discard the list?', `${s.people.length} ${s.people.length === 1 ? 'person is' : 'people are'} in the list and will not be added.`,
                    () => { s.people = []; window.location.hash = '#/user-management'; }, { confirmText: 'Discard', confirmClass: 'btn-danger' });
            })
            .on('click' + NS, '#adp-submit', submit)
            .on('click' + NS, '#adp-copy', copyPasswords)
            .on('click' + NS, '#adp-more', () => confirmLeave(startOver));
    }

    function selectRole(role) {
        s.role = role;
        $('#add-people-view [data-adp-role]').each(function() {
            const on = this.dataset.adpRole === role;
            this.setAttribute('aria-checked', String(on));
            this.setAttribute('tabindex', on ? '0' : '-1');
        });
        drawAccess();
        changed();
    }

    /** On the page frame the two columns scroll inside; the summary bar stays in view. */
    function watchColumns() {
        if (typeof Modals === 'undefined' || !Modals.watchScroll) return;
        const who = document.querySelector('#add-people-view .adp-who');
        if (who) Modals.watchScroll(who, who, 'Who');
        const access = document.querySelector('#add-people-view .adp-access-card');
        if (access) Modals.watchScroll(access, access, 'Access');
    }

    return {
        load,
        model: { parseAddresses, parseEntries, editDistance, mistypedDomain, colleagueDomain, chipStatus, sendable, summarySentence,
            noteLine, requestOf, personOf, hasOwnDetails, detailsProblem, roleLabel, grantText, matchEnvironments }
    };
})();

if (typeof window !== 'undefined') window.AddPeople = AddPeople;
