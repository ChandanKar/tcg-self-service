/**
 * Settings page (E29-T04, U14-T07 Settings.dc.html), #/settings, admins only: a menu of the eight
 * sections with their counts (?section= in the address) and the chosen section's settings. Each row
 * has its name, description, key and state ("On by default", "Changed", "Set in .env"); switches are
 * named by their setting. Changes wait in a bar ("2 changes not saved · Discard · Save changes") and
 * are saved together (PUT /settings): all or none, each problem under its row. Switches that act on
 * AWS or hold starts are confirmed before the save. Reload or close with unsaved changes asks first.
 */
const Settings = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    /** The sections, their short address names and the sentence under each title. */
    const SECTIONS = [
        ['notif', 'Notifications', 'Which emails the platform sends. People choose their own in-app alerts in My account.'],
        ['access', 'Access and security', 'Sign-in and who may do what.'],
        ['env', 'Environments and leases', 'How environments run, stop and are made.'],
        ['auto', 'Automation', 'Schedules, approvals and what counts as idle.'],
        ['cost', 'Cost', 'What is collected about spend, and how rightsizing judges a VM.'],
        ['int', 'Integrations', 'Messages, tokens and lookups that connect the platform to other systems.'],
        ['aws', 'AWS', 'How the platform keeps up with AWS.'],
        ['ret', 'Data retention', 'How long history is kept before it is purged.']
    ];
    /** Switches whose effect reaches AWS or users' starts: confirmed before they are turned on. */
    const RISKY = {
        'cost.tagging.enabled': 'Tag reconciliation writes cost-allocation tags to your AWS resources.',
        'automation.rules.enabled': 'Automation rules start and stop VMs on their schedules.',
        'approvals.enabled': 'Starts that match an approval policy will wait for an approver.',
        'quotas.enabled': 'Starts that would exceed a team quota will warn, wait for approval or be refused.',
        'rightsizing.safe-resize.enabled': 'Admins can stop running VMs, change their instance type and start them again from Cost Management.',
        'spot.eks.conversion.enabled': 'Environment admins can create Spot node groups in EKS and scale their on-demand node groups to 0.',
        'spot.fallback.enabled': 'On-demand node groups start automatically when their Spot twin is short of capacity.',
        'provisioning.destroy.enabled': 'Provisioned environments and everything in them are deleted in AWS when their lifetime ends.',
        'rds.keep-stopped.enabled': 'RDS databases that AWS starts after seven days stopped are stopped again.'
    };
    /** Switches confirmed before they are turned off. */
    const RISKY_OFF = {
        'auth.password-login.enabled': 'People who sign in with a username and password (not Microsoft) cannot sign in until it is on again.'
    };

    let s = null; // {token, settings, section, pending: {key: value}, errors: {key: text}, saving}

    // ---- pure model (node tests: src/test/js/settings-page.test.mjs) ----

    function sectionOf(group) {
        const found = SECTIONS.find(([, name]) => name === group);
        return found ? found[0] : null;
    }

    /** "On by default", "Off by default", "Default 5", "Changed" (not saved), "Set by …" (saved), "Set in .env". */
    function stateOf(setting, pending) {
        if (!setting.editable) return { text: 'Set in .env', kind: 'env' };
        if (pending !== undefined) return { text: 'Changed', kind: 'changed' };
        if (setting.source === 'OVERRIDE') {
            return { text: `Set${setting.updatedByName ? ` by ${setting.updatedByName}` : ''}`, kind: 'set' };
        }
        if (setting.type === 'BOOLEAN') return { text: setting.value === 'true' ? 'On by default' : 'Off by default', kind: 'default' };
        return { text: `Default ${setting.value}`, kind: 'default' };
    }

    /** A number's problem in words, or null. */
    function numberProblem(setting, raw) {
        const text = String(raw).trim();
        const n = Number(text);
        const bad = text === '' || Number.isNaN(n) || (setting.type === 'INT' && !Number.isInteger(n))
            || (setting.min !== null && setting.min !== undefined && n < Number(setting.min))
            || (setting.max !== null && setting.max !== undefined && n > Number(setting.max));
        return bad ? `Enter ${setting.type === 'INT' ? 'a whole number' : 'a number'} from ${setting.min} to ${setting.max}.` : null;
    }

    /** "1 change not saved", "2 changes not saved". */
    function pendingText(n) {
        return `${n} change${n === 1 ? '' : 's'} not saved`;
    }

    /** The server's "Nothing was saved: a must be …; b is read-only…" split onto the rows it names. */
    function errorsByKey(message, keys) {
        const out = {};
        String(message || '').replace(/^Nothing was saved:\s*/, '').split('; ').forEach(part => {
            const key = keys.filter(k => part.startsWith(k)).sort((a, b) => b.length - a.length)[0];
            if (key) out[key] = part;
        });
        return out;
    }

    // ---- page ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function guardUnload(e) {
        if (s && Object.keys(s.pending).length) {
            e.preventDefault();
            e.returnValue = '';
        }
    }

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3" role="alert">Access denied. Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        s = { token: ContentRouter.token(), settings: [], section: SECTIONS.some(([k]) => k === q.section) ? q.section : 'notif',
            pending: {}, errors: {}, saving: false };
        $('#content-area').html(`
            <div class="content-view settings-page" id="settings-view">
                ${PageHeader.markup({ title: 'Settings',
                    summaryHtml: 'Platform switches. Changes apply within 30 seconds; no restart needed.',
                    helpPage: typeof HelpLink !== 'undefined' ? 'settings' : null, helpLabel: 'Settings' })}
                <div class="st-layout">
                    <nav class="st-nav" id="st-nav" aria-label="Settings sections"></nav>
                    <section class="st-panel" id="st-panel" aria-labelledby="st-title"><p class="st-note">Loading settings…</p></section>
                </div>
            </div>`);
        window.addEventListener('beforeunload', guardUnload);
        ContentRouter.onLeave(() => window.removeEventListener('beforeunload', guardUnload));
        $('#settings-view').off('.st')
            .on('click.st', '[data-st-section]', function() { pick(this.dataset.stSection); })
            .on('change.st', '[data-st-key]', function() { changed(this); })
            .on('input.st', 'input[type=number][data-st-key]', function() { changed(this); })
            .on('click.st', '#st-discard', discard)
            .on('click.st', '#st-save', saveAll)
            .on('click.st', '[data-st-reset]', function() { reset(this.dataset.stReset); });
        const token = s.token;
        call('GET', Config.API.settings.list).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            s.settings = list || [];
            draw();
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#st-panel').html(Utils.html`<p class="st-note" role="alert">${serverMessage(e, "Couldn't load the settings.")}</p>`);
        });
    }

    function inSection(key) {
        return s.settings.filter(x => sectionOf(x.group) === key);
    }

    function pick(section) {
        if (section === s.section) return;
        s.section = section;
        try {
            history.replaceState(null, '', `#/settings?section=${section}`);
        } catch (e) { /* the address keeps the old section */ }
        draw();
        $('#st-title').trigger('focus');
    }

    function draw() {
        $('#st-nav').html(SECTIONS.map(([key, name]) => {
            const count = inSection(key).length;
            const unsaved = inSection(key).some(x => s.pending[x.key] !== undefined);
            return Utils.html`<button type="button" class="st-nav-item" data-st-section="${key}" aria-current="${key === s.section ? 'page' : 'false'}">
                <span>${name}</span>${Utils.raw(unsaved ? '<span class="st-dot" title="Not saved" aria-label="has changes not saved"></span>' : '')}<span class="st-count">${count}</span></button>`;
        }).join(''));
        const [, title, intro] = SECTIONS.find(([key]) => key === s.section);
        const rows = inSection(s.section);
        $('#st-panel').html(`<div class="st-head"><h2 id="st-title" tabindex="-1">${Utils.escapeHtml(title)}</h2><p>${Utils.escapeHtml(intro)}</p></div>
            ${rows.length ? rows.map(rowHtml).join('') : '<p class="st-note st-pad">Nothing to set here.</p>'}
            <div class="st-bar" id="st-bar" role="region" aria-label="Changes not saved" hidden>
                <span class="st-bar-text" id="st-bar-text"></span>
                <button type="button" class="st-link" id="st-discard">Discard</button>
                <button type="button" class="btn btn-primary btn-sm" id="st-save">Save changes</button></div>`);
        drawBar();
    }

    function rowHtml(x) {
        const id = 'set-' + x.key.replace(/[^a-z0-9]/gi, '-');
        const pending = s.pending[x.key];
        const value = pending !== undefined ? pending : x.value;
        const state = stateOf(x, pending);
        let control;
        if (!x.editable) {
            control = Utils.html`<code class="st-value">${x.value || '(not set)'}</code>`;
        } else if (x.type === 'BOOLEAN') {
            control = Utils.html`<span class="form-check form-switch m-0"><input class="form-check-input st-switch" type="checkbox" role="switch" id="${id}"
                data-st-key="${x.key}" aria-describedby="${id}-desc"${Utils.raw(value === 'true' ? ' checked' : '')}></span>`;
        } else {
            control = Utils.html`<input class="form-control form-control-sm st-number" type="number" id="${id}" data-st-key="${x.key}" value="${value}"
                min="${x.min === null ? '' : x.min}" max="${x.max === null ? '' : x.max}" step="${x.type === 'INT' ? '1' : 'any'}"
                aria-describedby="${id}-desc"${Utils.raw(s.errors[x.key] ? ' aria-invalid="true"' : '')}>`;
        }
        const range = x.editable && x.type !== 'BOOLEAN' && x.min !== null ? ` From ${x.min} to ${x.max}.` : '';
        return Utils.html`<div class="st-row" data-row="${x.key}">
            <div class="st-text">
                ${Utils.raw(x.editable ? Utils.html`<label class="st-name" for="${id}">${x.label}</label>` : Utils.html`<span class="st-name">${x.label}</span>`)}
                <span class="st-desc" id="${id}-desc">${x.description}${range}</span>
                <span class="st-key">${x.key}</span>
                ${Utils.raw(s.errors[x.key] ? Utils.html`<span class="st-error" role="alert">${s.errors[x.key]}</span>` : '')}
            </div>
            <div class="st-side">
                <span class="st-state st-state-${state.kind}"${Utils.raw(x.updatedAt && state.kind === 'set' ? Utils.html` title="${new Date(x.updatedAt).toLocaleString()}"` : '')}>${state.text}</span>
                ${Utils.raw(x.editable && x.source === 'OVERRIDE' && pending === undefined
                    ? Utils.html`<button type="button" class="st-link" data-st-reset="${x.key}" aria-label="Reset ${x.label} to its default">Reset</button>` : '')}
                ${Utils.raw(control)}
            </div>
        </div>`;
    }

    /** Updates the bar in place: rebuilding it would drop a click that starts as a number field loses focus. */
    function drawBar() {
        const n = Object.keys(s.pending).length;
        $('#st-bar').prop('hidden', !n);
        $('#st-bar-text').text(n ? pendingText(n) : '');
        $('#st-save').prop('disabled', s.saving);
        $('#st-nav [data-st-section]').each(function() {
            const unsaved = inSection(this.dataset.stSection).some(x => s.pending[x.key] !== undefined);
            $(this).find('.st-dot').remove();
            if (unsaved) $(this).find('.st-count').before('<span class="st-dot" title="Not saved" aria-label="has changes not saved"></span>');
        });
    }

    function find(key) {
        return s.settings.find(x => x.key === key);
    }

    function changed(el) {
        const key = el.dataset.stKey;
        const x = find(key);
        if (!x) return;
        const value = x.type === 'BOOLEAN' ? String(el.checked) : String(el.value).trim();
        if (value === x.value) delete s.pending[key];
        else s.pending[key] = value;
        delete s.errors[key];
        const $row = $(`[data-row="${CSS.escape(key)}"]`);
        const state = stateOf(x, s.pending[key]);
        $row.find('.st-state').attr('class', `st-state st-state-${state.kind}`).text(state.text);
        $row.find('.st-error').remove();
        $row.find('[data-st-reset]').toggle(s.pending[key] === undefined);
        $(el).removeAttr('aria-invalid');
        drawBar();
    }

    function discard() {
        s.pending = {};
        s.errors = {};
        draw();
    }

    async function saveAll() {
        const keys = Object.keys(s.pending);
        if (!keys.length || s.saving) return;
        s.errors = {};
        keys.forEach(key => {
            const x = find(key);
            if (x && x.type !== 'BOOLEAN') {
                const problem = numberProblem(x, s.pending[key]);
                if (problem) s.errors[key] = problem;
            }
        });
        if (Object.keys(s.errors).length) {
            showErrors();
            return;
        }
        const warnings = keys.map(key => (s.pending[key] === 'true' && RISKY[key]) || (s.pending[key] === 'false' && RISKY_OFF[key]))
            .filter(Boolean);
        if (warnings.length) {
            Modals.confirm('Save these changes?', warnings.join(' ') + ' Save anyway?', () => send(keys),
                { confirmText: 'Save changes', confirmClass: 'btn-warning' });
            return;
        }
        send(keys);
    }

    function send(keys) {
        s.saving = true;
        drawBar();
        call('PUT', Config.API.settings.batch, { changes: keys.map(key => ({ key, value: s.pending[key] })) }).then(updated => {
            s.saving = false;
            (updated || []).forEach(u => {
                const i = s.settings.findIndex(x => x.key === u.key);
                if (i >= 0) s.settings[i] = u;
            });
            s.pending = {};
            Notifications.success(`Saved ${keys.length} change${keys.length === 1 ? '' : 's'}. They apply within 30 seconds.`);
            draw();
        }).catch(e => {
            s.saving = false;
            const message = serverMessage(e, 'Nothing was saved.');
            s.errors = errorsByKey(message, keys);
            if (!Object.keys(s.errors).length) Notifications.showError(message);
            showErrors();
        });
    }

    /** Errors under their rows; the first section with one is shown. */
    function showErrors() {
        const first = Object.keys(s.errors)[0];
        const x = first && find(first);
        if (x && sectionOf(x.group) !== s.section) s.section = sectionOf(x.group);
        draw();
        const el = first && document.querySelector(`[data-row="${CSS.escape(first)}"] [data-st-key]`);
        if (el) el.focus();
    }

    function reset(key) {
        const x = find(key);
        if (!x) return;
        call('DELETE', Config.API.settings.item(key)).then(updated => {
            const i = s.settings.findIndex(y => y.key === key);
            if (i >= 0) s.settings[i] = updated;
            Notifications.success(`${x.label} is back to its default.`);
            draw();
        }).catch(e => Notifications.showError(serverMessage(e, 'Could not reset the setting.')));
    }

    return { load, model: { sectionOf, stateOf, numberProblem, pendingText, errorsByKey } };
})();

if (typeof window !== 'undefined') window.Settings = Settings;
