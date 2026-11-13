/**
 * VM Self-Service Platform - Environment settings (U11-T06, EnvSettings.dc.html)
 * #/environments/<id>/settings[/<section>]: one page per environment with a section list on the
 * left. Details (names, description, team, AWS account, production, the cost tags on its VMs) are
 * edited here with Save changes / Discard; Deactivate asks for the short name. Idle auto-stop, Budget
 * and Who has access open their own pages. Groups and start order, Running and stopping (leases,
 * with the idle and schedule lines), Spot and Notifications (U11-T07) are sections too: the panels
 * that were on the environment page live here now.
 */
const EnvSettings = (function() {
    'use strict';

    const SECTIONS = [
        { key: 'details', label: 'Details' },
        { key: 'groups', label: 'Groups and start order' },
        { key: 'running', label: 'Running and stopping' },
        { key: 'spot', label: 'Spot', feature: 'spot', eksOnly: true },
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

    function hasFeature(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    function ordinal(n) {
        const mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
        return `${n}${{ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th'}`;
    }

    /** The groups in start order, without Auto-Discovered. */
    function startOrder(groups) {
        return (groups || []).filter(g => g.name !== 'discovered')
            .slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
    }

    /** "Starts first" / "Waits for Database" / "Waits for Database, Application". */
    function waitsText(group, groups) {
        const names = (group.dependsOnGroupIds || []).map(id => (groups || []).find(g => g.groupId === id))
            .filter(Boolean).map(g => g.displayName || g.name);
        return names.length ? `Waits for ${names.join(', ')}` : 'Starts first';
    }

    /** The order after moving one group up (-1) or down (+1), or null at an end. */
    function moved(ids, groupId, step) {
        const i = ids.indexOf(groupId);
        const j = i + step;
        if (i < 0 || j < 0 || j >= ids.length) return null;
        const next = ids.slice();
        next[i] = ids[j];
        next[j] = groupId;
        return next;
    }

    /** "Schedule: Office hours, Mon–Fri 08:00–20:00" (and how many more), or "Schedule: none". */
    function scheduleText(rules) {
        const on = (rules || []).filter(r => r.enabled);
        if (!on.length) return 'Schedule: none';
        const first = on[0];
        const more = on.length > 1 ? ` (and ${on.length - 1} more)` : '';
        return `Schedule: ${first.name}${first.scheduleSummary ? `, ${first.scheduleSummary}` : ''}${more}`;
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
            region: storedRegion(env),
            production: !!env.production
        };
    }

    /** The region stored on the environment (its metadata), '' when none (U19-T04). */
    function storedRegion(env) {
        try {
            const meta = JSON.parse(env.metadata || '{}') || {};
            return typeof meta.region === 'string' ? meta.region.trim() : '';
        } catch (e) {
            return '';
        }
    }

    /** A region code's problem in words, or null: blank is fine (the VMs' region is used). */
    function regionProblem(value) {
        const v = String(value || '').trim();
        return !v || /^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$/.test(v) ? null : `${v} is not an AWS region code. Use a code such as eu-west-1.`;
    }

    /** The PUT body with only what changed (UpdateEnvironmentDTO), or null; production goes separately. */
    function changes(loaded, now) {
        const body = {};
        if (now.displayName.trim() !== loaded.displayName) body.displayName = now.displayName.trim();
        if (now.description.trim() !== loaded.description) body.description = now.description.trim();
        if (now.teamId !== loaded.teamId) body.teamId = now.teamId; // '' clears the team
        if (now.awsAccountRef && now.awsAccountRef !== loaded.awsAccountRef) body.awsAccountRef = now.awsAccountRef;
        // A metadata patch: other keys stay; null removes the stored region (U19-T04).
        if (now.region.trim() !== loaded.region) body.metadata = JSON.stringify({ region: now.region.trim() || null });
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
        const eks = (s.env.serviceType || 'EC2') === 'EKS';
        const nav = SECTIONS.filter(x => (!x.feature || hasFeature(x.feature)) && (!x.eksOnly || eks)).map(x => {
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
            groups: () => '<div id="es-groups"><div class="vr-empty">Loading groups…</div></div>',
            running: runningShellHtml,
            spot: () => '',
            notifications: notificationsHtml,
            deactivate: deactivateHtml
        }[s.section]();
        $('#es-panel').html(html).toggleClass('es-panel-wide', s.section === 'spot');
        if (s.section === 'details') updateSaveBar();
        if (s.section === 'groups') loadGroups();
        if (s.section === 'running') loadRunning();
        if (s.section === 'spot' && typeof SpotPage !== 'undefined') SpotPage.render(s.env, $('#es-panel')); // U11-T08
        if (s.section === 'notifications') {
            if (hasFeature('chat') && typeof ChatChannels !== 'undefined') ChatChannels.renderEnvPanel(s.env);
            if (hasFeature('webhooks') && typeof WebhooksPanel !== 'undefined') WebhooksPanel.renderEnvPanel(s.env);
        }
    }

    // ---- Groups and start order

    function isEks() {
        return (s.env.serviceType || 'EC2') === 'EKS';
    }

    async function loadGroups() {
        const token = s.token;
        try {
            const groups = await ApiClient.get(Config.API.groups.list(s.env.environmentId), { suppressGlobalError: true });
            if (!ContentRouter.isCurrent(token) || s.section !== 'groups') return;
            s.groups = groups || [];
            drawGroups();
        } catch (error) {
            $('#es-groups').html('<div class="vr-empty">Could not load the groups.</div>');
        }
    }

    function drawGroups(error) {
        const list = startOrder(s.groups);
        const eks = isEks();
        const rows = list.map((g, i) => {
            const name = g.displayName || g.name;
            return Utils.html`<div class="es-group" data-group-id="${g.groupId}">
                    <span class="es-group-move">
                        <button type="button" class="es-icon" data-es-move="-1" aria-label="Move ${name} up"${Utils.raw(i === 0 ? ' disabled' : '')}>▲</button>
                        <button type="button" class="es-icon" data-es-move="1" aria-label="Move ${name} down"${Utils.raw(i === list.length - 1 ? ' disabled' : '')}>▼</button>
                    </span>
                    <span class="es-group-name"><b>${ordinal(i + 1)} · ${name}</b><span>${g.vmCount || 0} ${eks ? 'node' : 'VM'}${(g.vmCount || 0) === 1 ? '' : 's'}</span></span>
                    <span class="es-group-waits">${waitsText(g, s.groups)}</span>
                    <span class="es-group-actions">
                        <button type="button" class="vr-act" data-es-group-edit aria-label="Edit ${name}">Edit</button>
                        ${Utils.raw(eks ? '' : Utils.html`<button type="button" class="vr-act vr-danger" data-es-group-delete aria-label="Delete ${name}"
                            ${Utils.raw(g.vmCount > 0 ? 'disabled title="Move or remove its VMs first"' : '')}>Delete</button>`)}
                    </span>
                </div>`;
        }).join('');
        $('#es-groups').html(Utils.html`
            <div class="es-section-head"><h2>Groups and start order</h2>
                ${Utils.raw(eks ? '' : '<button type="button" class="vr-link" id="es-group-add">Add a group</button>')}</div>
            <p class="es-text">Groups start top to bottom and stop bottom to top. A group can also wait for specific groups.</p>
            <div class="acd-error" id="es-groups-error" role="alert">${error || ''}</div>
            ${Utils.raw(rows || '<div class="es-text">No groups yet.</div>')}`);
    }

    async function moveGroup(groupId, step) {
        const ids = startOrder(s.groups).map(g => g.groupId);
        const next = moved(ids, groupId, step);
        if (!next) return;
        $('#es-groups [data-es-move]').prop('disabled', true);
        try {
            s.groups = await ApiClient.put(Config.API.groups.reorder(s.env.environmentId), { groupIds: next }, { suppressGlobalError: true });
            drawGroups();
            const $row = $(`#es-groups .es-group[data-group-id="${groupId}"] [data-es-move="${step}"]`);
            ($row.prop('disabled') ? $(`#es-groups .es-group[data-group-id="${groupId}"] [data-es-move]`).not(':disabled') : $row).first().trigger('focus');
        } catch (xhr) {
            drawGroups((xhr && xhr.responseJSON && xhr.responseJSON.message) || "The order wasn't saved. Try again.");
        }
    }

    function groupDialogEnv() {
        return { environmentId: s.env.environmentId, environmentName: s.env.displayName || s.env.name };
    }

    function deleteGroup(groupId) {
        const g = (s.groups || []).find(x => x.groupId === groupId);
        if (!g || g.vmCount > 0) return;
        Modals.confirm('Delete group', `Delete "${g.displayName || g.name}"? This cannot be undone.`, () => {
            ApiClient.delete(Config.API.groups.delete(s.env.environmentId, groupId), { suppressGlobalError: true })
                .done(() => { Notifications.success('Group deleted.'); loadGroups(); })
                .fail(xhr => drawGroups((xhr.responseJSON && xhr.responseJSON.message) || "The group wasn't deleted."));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    // ---- Running and stopping

    function runningShellHtml() {
        const leases = s.env.leaseEnabled === null || s.env.leaseEnabled === undefined
            ? '<p class="es-text" id="es-leases-off">Leases are switched off for the platform: environments run until someone stops them.</p>'
            : '<div id="es-lease-form"><div class="vr-empty">Loading…</div></div>';
        return `<h2>Leases</h2>${leases}
            <div class="es-lines" id="es-running-lines"></div>`;
    }

    async function loadRunning() {
        const token = s.token;
        const envId = s.env.environmentId;
        const leaseOn = s.env.leaseEnabled !== null && s.env.leaseEnabled !== undefined;
        const quiet = { suppressGlobalError: true };
        const [policy, idle, rules] = await Promise.all([
            leaseOn ? ApiClient.get(Config.API.leases.policy(envId), quiet).catch(() => null) : Promise.resolve(null),
            hasFeature('idleStop') ? ApiClient.get(Config.API.idleStop.status(envId), quiet).catch(() => null) : Promise.resolve(null),
            ApiClient.get(Config.API.automationRules.list(envId), quiet).catch(() => null)
        ]);
        if (!ContentRouter.isCurrent(token) || s.section !== 'running') return;
        if (leaseOn) {
            $('#es-lease-form').html(policy && typeof Leases !== 'undefined'
                ? `${Leases.policyFormHtml(policy)}<div class="es-save"><button type="button" class="btn btn-primary" id="es-lease-save">Save lease settings</button></div>`
                : '<p class="es-text">The lease settings could not be loaded.</p>');
        }
        const lines = [];
        if (idle && idle.featureEnabled && typeof IdleStop !== 'undefined') {
            const rule = (idle.rules || []).find(r => r.scopeType === 'ENVIRONMENT') || (idle.rules || [])[0] || null;
            lines.push(Utils.html`<div class="es-line"><span>Idle auto-stop: <b>${IdleStop.model.modeText(idle, rule)}</b></span>
                <a href="#/environments/${s.env.environmentId}">Idle auto-stop</a></div>`);
        }
        if (Array.isArray(rules)) {
            lines.push(Utils.html`<div class="es-line"><span>${scheduleText(rules)}</span><a href="#/automation-rules">Automation rules</a></div>`);
        }
        $('#es-running-lines').html(lines.join(''));
    }

    async function saveLease() {
        const body = Leases.readPolicyForm();
        if (!body) return;
        const $btn = $('#es-lease-save').prop('disabled', true);
        try {
            await Leases.savePolicy(s.env.environmentId, body);
        } catch (e) {
            // the form shows why
        }
        $btn.prop('disabled', false);
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
                <label class="es-field">AWS region<input type="text" class="form-control es-mono" id="es-region" value="${d.region}"
                    placeholder="${env.region || 'eu-west-1'}" spellcheck="false" autocomplete="off" aria-describedby="es-region-help">
                    <span class="es-field-help" id="es-region-help">${regionHelp(env, d.region)}</span></label>
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

    /** What the region field means for this environment (U19-T04). */
    function regionHelp(env, stored) {
        if (stored) return 'AWS calls for this environment go to this region.';
        if (env.region) return `Not set: its VMs are in ${env.region}, so that is used.`;
        if (env.regionMissing) return "Not known: the cluster isn't synced until you set its region.";
        return 'Not set: its VMs are found in every region the AWS account has enabled.';
    }

    function notificationsHtml() {
        const chat = hasFeature('chat') ? '<div id="env-chat-panel" class="es-embedded"></div>' : '';
        const webhooks = hasFeature('webhooks')
            ? '<div id="env-webhook-panel" class="es-embedded"></div>'
            : `<div class="es-subhead">Webhooks</div>
               <p class="es-text" id="es-webhooks-off">Webhooks are switched off for the platform. An administrator can switch them on in the server configuration (needs a restart).</p>`;
        return `<div class="es-section-head"><h2>Notifications</h2>
                <button type="button" class="vr-link" id="es-notify">Email everyone with access…</button></div>
            ${chat}${webhooks}`;
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
            region: $('#es-region').length ? String($('#es-region').val() || '') : s.loaded.region,
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
        const badRegion = regionProblem(now.region);
        if (badRegion) {
            $('#es-save-note').text(badRegion);
            $('#es-region').attr('aria-invalid', 'true').trigger('focus');
            return;
        }
        $('#es-region').removeAttr('aria-invalid');
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
            const id = s.env.environmentId;
            const name = envName();
            await ApiClient.delete(Config.API.environments.delete(id), { suppressGlobalError: true });
            // U20-T06: an administrator can take it back at once (reactivating is theirs).
            const undo = typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin() ? { action: { label: 'Undo', onClick: () => {
                ApiClient.post(Config.API.environments.reactivate(id), undefined, { suppressGlobalError: true })
                    .done(() => {
                        Notifications.success(`${name} is active again.`);
                        if (typeof ContentRouter !== 'undefined' && ContentRouter.reload) ContentRouter.reload();
                    })
                    .fail(() => Notifications.error(`${name} wasn't reactivated. Bring it back from VM registry › Deactivated.`));
            } } } : {};
            Notifications.success(`${name} deactivated.`, undefined, undo);
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
            .on('click' + NS, '[data-es-move]', function() {
                moveGroup($(this).closest('.es-group').data('group-id'), Number(this.dataset.esMove));
            })
            .on('click' + NS, '[data-es-group-edit]', function() {
                const g = (s.groups || []).find(x => x.groupId === $(this).closest('.es-group').data('group-id'));
                if (g) RegistryDialogs.editGroup(groupDialogEnv(), g, s.groups, loadGroups);
            })
            .on('click' + NS, '[data-es-group-delete]', function() {
                deleteGroup($(this).closest('.es-group').data('group-id'));
            })
            .on('click' + NS, '#es-group-add', () => RegistryDialogs.editGroup(groupDialogEnv(), null, s.groups || [], loadGroups))
            .on('click' + NS, '#es-lease-save', () => saveLease())
            .on('input' + NS, '#es-confirm', function() {
                $('#es-deactivate').prop('disabled', this.value.trim() !== s.env.name);
            })
            .on('click' + NS, '#es-deactivate', () => deactivate());
    }

    return { load, model: { sectionKey, teamLabel, detailsOf, changes, isDirty, SECTIONS, ordinal, startOrder, waitsText, moved, scheduleText,
        storedRegion, regionProblem, regionHelp } };
})();

if (typeof window !== 'undefined') window.EnvSettings = EnvSettings;
