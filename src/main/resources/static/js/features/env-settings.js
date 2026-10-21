/**
 * VM Self-Service Platform - Environment settings (U11-T06, EnvSettings.dc.html)
 * #/environments/<id>/settings[/<section>]: one page per environment with a section list on the
 * left. Details (names, description, team, AWS account, production, the cost tags on its VMs) are
 * edited here with Save changes / Discard; Deactivate asks for the short name. Idle auto-stop, Budget
 * and Who has access open their own pages; U11-T07 fills Groups and start order, Running and
 * stopping, and Notifications.
 */
const EnvSettings = (function() {
    'use strict';

    const SECTIONS = [
        { key: 'details', label: 'Details' },
        { key: 'groups', label: 'Groups and start order' },
        { key: 'running', label: 'Running and stopping' },
        { key: 'idle', label: 'Idle auto-stop', link: id => `#/environments/${encodeURIComponent(id)}` },
        { key: 'budget', label: 'Budget', link: id => `#/budgets?scope=${encodeURIComponent(id)}` },
        { key: 'access', label: 'Who has access', link: id => `#/access-management/grant?environmentId=${encodeURIComponent(id)}` },
        { key: 'notifications', label: 'Notifications' },
        { key: 'deactivate', label: 'Deactivate' }
    ];

    let s = null; // page state

    // ------------------------------------------------------------------ model

    function sectionKey(key) {
        return SECTIONS.some(x => x.key === key && !x.link) ? key : 'details';
    }

    function teamLabel(team) {
        return team.costCenter ? `${team.displayName || team.name} · ${team.costCenter}` : (team.displayName || team.name);
    }

    /** What the Details form holds, comparable with what was loaded. */
    function detailsOf(env) {
        return {
            displayName: env.displayName || '',
            description: env.description || '',
            teamId: env.teamId || '',
            awsAccountRef: env.awsAccountRef || '',
            production: !!env.production
        };
    }

    /** The PUT body with only what changed (UpdateEnvironmentDTO), or null; production goes separately. */
    function changes(loaded, now) {
        const body = {};
        if (now.displayName.trim() !== loaded.displayName) body.displayName = now.displayName.trim();
        if (now.description.trim() !== loaded.description) body.description = now.description.trim();
        if (now.teamId !== loaded.teamId) body.teamId = now.teamId; // '' clears the team
        if (now.awsAccountRef && now.awsAccountRef !== loaded.awsAccountRef) body.awsAccountRef = now.awsAccountRef;
        return Object.keys(body).length ? body : null;
    }

    function isDirty(loaded, now) {
        return !!changes(loaded, now) || now.production !== loaded.production;
    }

    // ------------------------------------------------------------------ page

    /** Router loader: params.environmentId and params.section from the path. */
    async function load(params) {
        const token = ContentRouter.token();
        const environmentId = params && params.environmentId;
        s = { token, environmentId, section: sectionKey(params && params.section), env: null, teams: [], accounts: [],
            tags: null, loaded: null };
        ContentRouter.onLeave(() => { window.removeEventListener('beforeunload', guardUnload); });
        window.addEventListener('beforeunload', guardUnload);
        $('#content-area').html('<div class="content-view es-page"><div class="vr-empty">Loading…</div></div>');
        let env;
        try {
            env = await ApiClient.get(Config.API.environments.get(environmentId), { suppressGlobalError: true });
        } catch (error) {
            if (ContentRouter.isCurrent(token)) NoAccessPage.render('environment');
            return;
        }
        if (!ContentRouter.isCurrent(token)) return;
        if (env.viewerCanAdminister !== true) {
            NoAccessPage.render('environment-admin', { label: `${env.displayName || env.name} settings` });
            return;
        }
        s.env = env;
        s.loaded = detailsOf(env);
        const [teams, accounts, tags] = await Promise.all([
            ApiClient.get(Config.API.teams.list(false), { suppressGlobalError: true }).catch(() => []),
            typeof AwsAccountOptions !== 'undefined' ? AwsAccountOptions.load() : Promise.resolve([]),
            ApiClient.get(Config.API.environments.costTags(environmentId), { suppressGlobalError: true }).catch(() => null)
        ]);
        if (!ContentRouter.isCurrent(token)) return;
        s.teams = teams || [];
        s.accounts = accounts || [];
        s.tags = tags;
        render();
    }

    function envName() {
        return s.env.displayName || s.env.name;
    }

    function render() {
        const id = s.env.environmentId;
        const nav = SECTIONS.map(x => {
            const href = x.link ? x.link(id) : `#/environments/${encodeURIComponent(id)}/settings/${x.key}`;
            return Utils.html`<a class="es-nav-item${Utils.raw(x.key === 'deactivate' ? ' es-nav-danger' : '')}" href="${href}"
                ${Utils.raw(x.link ? '' : `data-es-section="${x.key}"`)}${Utils.raw(x.key === s.section ? ' aria-current="page"' : '')}>${x.label}</a>`;
        }).join('');
        $('#content-area').html(`
            <div class="content-view es-page" id="env-settings-view">
                ${PageHeader.markup({ title: `${envName()} settings`, breadcrumb: [
                    { label: 'VM registry', href: `#/vm-registry?env=${encodeURIComponent(id)}` },
                    { label: envName(), href: `#/environments/${encodeURIComponent(id)}` },
                    { label: 'Settings' }] })}
                <div class="es-grid">
                    <nav class="es-nav" aria-label="Settings sections">${nav}</nav>
                    <section class="es-panel" id="es-panel" aria-live="polite"></section>
                </div>
            </div>`);
        renderSection();
        bind();
    }

    function renderSection() {
        const html = {
            details: detailsHtml,
            groups: () => pointerHtml('Groups and start order', 'Groups start top to bottom and stop bottom to top. A group can also wait for specific groups.',
                `#/vm-registry?env=${encodeURIComponent(s.env.environmentId)}`, 'Edit them in the VM registry'),
            running: () => pointerHtml('Running and stopping', 'Leases, idle auto-stop and schedules decide when this environment runs.',
                `#/environments/${encodeURIComponent(s.env.environmentId)}`, 'See them on the environment page'),
            notifications: notificationsHtml,
            deactivate: deactivateHtml
        }[s.section]();
        $('#es-panel').html(html);
        if (s.section === 'details') updateSaveBar();
    }

    function pointerHtml(title, text, href, link) {
        return Utils.html`<h2>${title}</h2><p class="es-text">${text}</p><p><a href="${href}">${link}</a></p>`;
    }

    function detailsHtml() {
        const env = s.env;
        const d = s.loaded;
        const teams = [{ teamId: '', label: 'No team' }].concat(s.teams.map(t => ({ teamId: t.teamId, label: teamLabel(t) })));
        if (d.teamId && !teams.some(t => t.teamId === d.teamId)) teams.push({ teamId: d.teamId, label: env.teamName || 'Current team' });
        const multi = typeof AwsAccountOptions !== 'undefined' && AwsAccountOptions.isMulti(s.accounts);
        const admin = typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin();
        const tags = s.tags && s.tags.tags ? s.tags.tags : [];
        return Utils.html`
            <h2>Details</h2>
            <form id="es-details" class="es-form" novalidate>
                <label class="es-field">Display name<input type="text" class="form-control" id="es-display" maxlength="255" value="${d.displayName}"></label>
                <label class="es-field">Short name<input type="text" class="form-control" id="es-short" value="${env.name}" disabled></label>
                <label class="es-field es-wide">Description<input type="text" class="form-control" id="es-description" maxlength="1000" value="${d.description}"></label>
                <label class="es-field">Team (pays for it)<select class="form-select" id="es-team">${Utils.raw(teams.map(t =>
                    Utils.html`<option value="${t.teamId}"${Utils.raw(t.teamId === d.teamId ? ' selected' : '')}>${t.label}</option>`).join(''))}</select></label>
                <label class="es-field"${Utils.raw(multi ? '' : ' hidden')}>AWS account<select class="form-select" id="es-account">${Utils.raw(multi
                    ? AwsAccountOptions.optionsHtml(s.accounts, d.awsAccountRef) : '')}</select></label>
                <label class="es-switch es-wide">
                    <input type="checkbox" id="es-production"${Utils.raw(d.production ? ' checked' : '')}${Utils.raw(admin ? '' : ' disabled')}>
                    <span><b>Production</b><span>Never stopped by idle auto-stop, and marked as production wherever it is listed.${admin ? '' : ' Only an administrator can change this.'}</span></span>
                </label>
                <div class="es-tags es-wide">
                    <div class="es-tags-title">Tags on every VM <span>· used for cost allocation</span></div>
                    ${Utils.raw(tags.length ? tags.map(t => Utils.html`<div class="es-tag"><code>${t.key}</code><span>${t.value}</span></div>`).join('')
                        : '<div class="es-text">No tags to show.</div>')}
                    ${Utils.raw(s.tags && s.tags.enabled === false ? '<div class="es-text">Cost-allocation tagging is switched off, so these tags are not put on the VMs yet.</div>' : '')}
                </div>
            </form>
            <div class="es-save" id="es-save">
                <span class="es-save-note" id="es-save-note"></span>
                <button type="button" class="btn btn-outline-secondary" id="es-discard">Discard</button>
                <button type="button" class="btn btn-primary" id="es-save-btn">Save changes</button>
            </div>`;
    }

    function notificationsHtml() {
        return `<h2>Notifications</h2>
            <p class="es-text">Tell everyone who can use this environment that it is being stopped, with a reason if you like.</p>
            <p><button type="button" class="btn btn-outline-primary" id="es-notify">Email everyone with access…</button></p>`;
    }

    function deactivateHtml() {
        const env = s.env;
        if (env.isActive === false) {
            return Utils.html`<h2>Deactivate</h2><p class="es-text">${envName()} is deactivated. An administrator can bring it back from the VM registry.</p>`;
        }
        return Utils.html`
            <h2>Deactivate</h2>
            <p class="es-text">The environment disappears from lists and schedules and its lock is released. VMs keep running in AWS; stop them first if you want them off. An administrator can bring it back.</p>
            <label class="es-field es-confirm">Type <code>${env.name}</code> to confirm<input type="text" class="form-control" id="es-confirm" autocomplete="off"></label>
            <div class="acd-error" id="es-deactivate-error" role="alert"></div>
            <button type="button" class="btn btn-danger" id="es-deactivate" disabled>Deactivate ${envName()}</button>`;
    }

    // ------------------------------------------------------------------ behaviour

    function formNow() {
        return {
            displayName: $('#es-display').val() || '',
            description: $('#es-description').val() || '',
            teamId: $('#es-team').val() || '',
            awsAccountRef: $('#es-account').closest('label').prop('hidden') ? s.loaded.awsAccountRef : ($('#es-account').val() || ''),
            production: $('#es-production').prop('checked') === true
        };
    }

    function dirty() {
        return s && s.section === 'details' && $('#es-details').length > 0 && isDirty(s.loaded, formNow());
    }

    function guardUnload(e) {
        if (dirty()) {
            e.preventDefault();
            e.returnValue = '';
        }
    }

    function updateSaveBar() {
        const d = dirty();
        $('#es-save-btn, #es-discard').prop('disabled', !d);
        $('#es-save-note').text(d ? 'You have unsaved changes.' : '');
    }

    /** Switch section in place, asking first when Details has unsaved changes. */
    function goTo(section) {
        const next = () => {
            s.section = section;
            if (window.history && window.history.replaceState) {
                window.history.replaceState(null, '', `#/environments/${encodeURIComponent(s.env.environmentId)}/settings/${section}`);
            }
            $('#env-settings-view .es-nav-item').each(function() {
                if (this.dataset.esSection === section) this.setAttribute('aria-current', 'page'); else this.removeAttribute('aria-current');
            });
            renderSection();
        };
        if (dirty()) {
            Modals.confirm('Discard changes?', 'Your changes to the details have not been saved.', next,
                { confirmText: 'Discard changes', confirmClass: 'btn-danger' });
            return;
        }
        next();
    }

    async function save() {
        const now = formNow();
        if (now.displayName.trim().length < 2) {
            $('#es-save-note').text('The display name needs at least 2 characters.');
            return;
        }
        const body = changes(s.loaded, now);
        const productionChanged = now.production !== s.loaded.production;
        const id = s.env.environmentId;
        $('#es-save-btn').prop('disabled', true).text('Saving…');
        try {
            if (body) await ApiClient.put(Config.API.environments.update(id), body, { suppressGlobalError: true });
            if (productionChanged) {
                await ApiClient.request('PATCH', Config.API.environments.production(id), { production: now.production }, { suppressGlobalError: true });
            }
            const env = await ApiClient.get(Config.API.environments.get(id), { suppressGlobalError: true });
            s.env = env;
            s.loaded = detailsOf(env);
            s.tags = await ApiClient.get(Config.API.environments.costTags(id), { suppressGlobalError: true }).catch(() => s.tags);
            Notifications.success('Settings saved');
            render();
        } catch (xhr) {
            $('#es-save-note').text((xhr && xhr.responseJSON && xhr.responseJSON.message) || "The settings weren't saved. Try again.");
            $('#es-save-btn').prop('disabled', false).text('Save changes');
        }
    }

    async function deactivate() {
        $('#es-deactivate-error').text('');
        $('#es-deactivate').prop('disabled', true).text('Deactivating…');
        try {
            await ApiClient.delete(Config.API.environments.delete(s.env.environmentId), { suppressGlobalError: true });
            Notifications.success(`${envName()} deactivated.`);
            window.location.hash = '#/vm-registry';
        } catch (xhr) {
            $('#es-deactivate-error').text((xhr && xhr.responseJSON && xhr.responseJSON.message) || "It wasn't deactivated. Try again.");
            $('#es-deactivate').prop('disabled', false).text(`Deactivate ${envName()}`);
        }
    }

    function bind() {
        const NS = '.envSettings';
        $('#env-settings-view').off(NS)
            .on('click' + NS, '[data-es-section]', function(e) {
                e.preventDefault();
                goTo(this.dataset.esSection);
            })
            .on('input' + NS + ' change' + NS, '#es-details', () => updateSaveBar())
            .on('submit' + NS, '#es-details', e => { e.preventDefault(); if (dirty()) save(); })
            .on('click' + NS, '#es-save-btn', () => save())
            .on('click' + NS, '#es-discard', () => renderSection())
            .on('click' + NS, '#es-notify', () => RegistryDialogs.notifyStop(s.env))
            .on('input' + NS, '#es-confirm', function() {
                $('#es-deactivate').prop('disabled', this.value.trim() !== s.env.name);
            })
            .on('click' + NS, '#es-deactivate', () => deactivate());
    }

    return { load, model: { sectionKey, teamLabel, detailsOf, changes, isDirty, SECTIONS } };
})();

if (typeof window !== 'undefined') window.EnvSettings = EnvSettings;
