/**
 * VM Self-Service Platform - Found in AWS (U19-T03)
 * What discovery found in any account and enabled region that no environment holds: EC2 instances
 * and EKS clusters, for administrators to add to an environment or ignore. Opened from the VM
 * registry's header in a side panel: filters by region and kind, a search, ten a page, Refresh
 * (sweeps again; otherwise the server keeps the last sweep ten minutes), and the ignored ones to
 * bring back. Nothing joins an environment from here without an administrator's choice; a resource
 * tagged for an environment says so (it joins it at the next discovery). The rules are pure
 * functions (FoundInAws.model), tested in src/test/js/found-in-aws.test.mjs.
 */
const FoundInAws = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    let s = null;

    // ------------------------------------------------------------------ model

    /** The items that pass the filters: region, kind (EC2 / EKS) and a search over name, size, account and zone. */
    function filterItems(items, f = {}) {
        const q = String(f.search || '').trim().toLowerCase();
        return (items || []).filter(i => (!f.region || i.region === f.region)
            && (!f.kind || i.kind === f.kind)
            && (!q || [i.name, i.size, i.accountName, i.zone, i.taggedEnvironment].some(v => v && String(v).toLowerCase().includes(q))));
    }

    /** The regions present, sorted, with how many items each. */
    function regionsOf(items) {
        const counts = {};
        (items || []).forEach(i => { counts[i.region] = (counts[i.region] || 0) + 1; });
        return Object.keys(counts).sort().map(region => ({ region, count: counts[region] }));
    }

    /** "3 instances and 1 cluster need a decision", "Nothing waiting: everything found is in an environment or ignored." */
    function summaryText(items) {
        const list = items || [];
        const ec2 = list.filter(i => i.kind === 'EC2').length;
        const eks = list.filter(i => i.kind === 'EKS').length;
        if (!ec2 && !eks) return 'Nothing waiting: everything found is in an environment or ignored.';
        const parts = [];
        if (ec2) parts.push(`${ec2} instance${ec2 === 1 ? '' : 's'}`);
        if (eks) parts.push(`${eks} cluster${eks === 1 ? '' : 's'}`);
        return `${parts.join(' and ')} need${ec2 + eks === 1 ? 's' : ''} a decision.`;
    }

    /** What a row's name cell says: the Name tag, else a plain placeholder (never the instance id). */
    function nameOf(item) {
        return item.name || (item.kind === 'EKS' ? 'Cluster' : '(no name tag)');
    }

    // ------------------------------------------------------------------ panel

    function open() {
        s = { data: null, page: 1, region: '', kind: '', search: '', showIgnored: false, ignored: null, ignoredPage: 1 };
        Slideout.open('Found in AWS', '<div class="fia" id="fia"></div>', { variant: 'found-in-aws', width: 920 });
        bind();
        load(false);
    }

    async function load(refresh) {
        const $root = $('#fia');
        if (!$root.length) return;
        if (!s.data || refresh) UiStates.mountLoading($root[0], 'rows', { label: 'Looking in every account and region…' });
        try {
            s.data = await ApiClient.get(Config.API.discovery.found(refresh), { suppressGlobalError: true });
            s.page = 1;
            draw();
        } catch (xhr) {
            $('#fia').html(UiStates.failed({ what: 'What was found in AWS', xhr, onRetry: () => load(true) }));
        }
    }

    function draw() {
        const $root = $('#fia');
        if (!$root.length || !s.data) return;
        const items = s.data.items || [];
        const rows = filterItems(items, s);
        const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
        s.page = Math.min(Math.max(1, s.page), pages);
        const regions = regionsOf(items);
        const scanned = s.data.scannedAt ? new Date(s.data.scannedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
        const unreachable = s.data.unreachable || [];
        $root.html(Utils.html`
            <div class="fia-head">
                <p class="fia-summary">${summaryText(items)}</p>
                <span class="fia-when">${scanned ? `Looked at ${scanned}` : ''}</span>
                <button type="button" class="btn btn-outline-secondary btn-sm" data-fia="refresh">Look again</button>
            </div>
            ${Utils.raw(unreachable.length ? Utils.html`<p class="fia-unreachable" role="status">Couldn't read ${unreachable.join(', ')}; anything there is not listed.</p>` : '')}
            <div class="fia-filters">
                <label class="fia-field">Region<select class="form-select form-select-sm" id="fia-region">
                    <option value="">Every region</option>${Utils.raw(regions.map(r => Utils.html`<option value="${r.region}"${Utils.raw(r.region === s.region ? ' selected' : '')}>${r.region} (${r.count})</option>`).join(''))}
                </select></label>
                <label class="fia-field">Kind<select class="form-select form-select-sm" id="fia-kind">
                    <option value="">Instances and clusters</option>
                    <option value="EC2"${Utils.raw(s.kind === 'EC2' ? ' selected' : '')}>Instances</option>
                    <option value="EKS"${Utils.raw(s.kind === 'EKS' ? ' selected' : '')}>Clusters</option>
                </select></label>
                <label class="fia-field fia-search">Search<input type="search" class="form-control form-control-sm" id="fia-search" value="${s.search}" placeholder="Name, size, account or zone"></label>
            </div>
            <div id="fia-list">${Utils.raw(listHtml(rows))}</div>
            <div class="fia-foot">
                <span id="fia-pager">${Utils.raw(pagerHtml(rows))}</span>
                <button type="button" class="vr-link" data-fia="ignored" aria-expanded="${s.showIgnored ? 'true' : 'false'}">Ignored (${s.data.ignored || 0})</button>
            </div>
            <div id="fia-ignored"${Utils.raw(s.showIgnored ? '' : ' hidden')}></div>`);
        if (s.showIgnored) drawIgnored();
    }

    /** Always "1–10 of 23" while anything is listed, and it follows the filters and the search (U20-T06). */
    function pagerHtml(rows) {
        return rows.length ? Pagination.buildPager({ page: s.page, pageSize: PAGE_SIZE, totalItems: rows.length,
            action: 'fia-page', key: 'found' }) : '';
    }

    function listHtml(rows) {
        const all = (s.data.items || []).length;
        if (!all) return UiStates.empty({ title: 'Nothing waiting', text: 'Every instance and cluster found in AWS is in an environment or ignored.' });
        if (!rows.length) return UiStates.filtered({ title: 'Nothing matches these filters', onClear: () => { s.region = ''; s.kind = ''; s.search = ''; draw(); } });
        const items = Pagination.slice(rows, s.page, PAGE_SIZE);
        return `<table class="fia-table" data-cards="on">
            <thead><tr><th scope="col">Name</th><th scope="col">Kind</th><th scope="col">Account</th><th scope="col">Region and zone</th>
                <th scope="col">Size</th><th scope="col">State</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
            <tbody>${items.map(rowHtml).join('')}</tbody></table>`;
    }

    function rowHtml(i) {
        const key = i.kind === 'EC2' ? i.ref : `${i.accountRef}|${i.region}|${i.name}`;
        const add = i.kind === 'EC2'
            ? Utils.html`<button type="button" class="vr-link" data-fia="add" data-key="${key}" aria-label="Add ${nameOf(i)} to an environment">Add…</button>`
            : Utils.html`<button type="button" class="vr-link" data-fia="add-cluster" aria-label="Make an environment from ${nameOf(i)}">Make an environment</button>`;
        return Utils.html`<tr data-key="${key}">
            <td data-label="Name"><span class="fia-name">${nameOf(i)}</span>${Utils.raw(i.taggedEnvironment
                ? Utils.html`<span class="fia-tag">Tagged for ${i.taggedEnvironment}: joins it at the next discovery</span>` : '')}</td>
            <td data-label="Kind">${i.kind === 'EKS' ? 'EKS cluster' : 'EC2 instance'}</td>
            <td data-label="Account">${i.accountName || ''}</td>
            <td data-label="Region and zone" class="fia-mono">${i.zone || i.region}</td>
            <td data-label="Size" class="fia-mono">${i.size || ''}</td>
            <td data-label="State">${i.state || ''}</td>
            <td data-label="" class="fia-actions">${Utils.raw(add)}
                <button type="button" class="vr-link" data-fia="ignore" data-key="${key}" aria-label="Ignore ${nameOf(i)}">Ignore</button></td>
        </tr>`;
    }

    function itemByKey(key) {
        return (s.data.items || []).find(i => (i.kind === 'EC2' ? i.ref : `${i.accountRef}|${i.region}|${i.name}`) === key);
    }

    async function ignore(key) {
        const item = itemByKey(key);
        if (!item) return;
        const body = item.kind === 'EC2' ? { kind: 'EC2', ref: item.ref }
            : { kind: 'EKS', accountRef: item.accountRef, region: item.region, clusterName: item.name };
        try {
            await ApiClient.post(Config.API.discovery.ignored, body, { suppressGlobalError: true });
            s.data.items = s.data.items.filter(i => i !== item);
            s.data.ignored = (s.data.ignored || 0) + 1;
            s.ignored = null;
            draw();
            Notifications.success(`${nameOf(item)} is ignored`);
        } catch (xhr) {
            Notifications.error((xhr && xhr.responseJSON && xhr.responseJSON.message) || `${nameOf(item)} wasn't ignored. Try again.`);
        }
    }

    async function drawIgnored() {
        const $box = $('#fia-ignored');
        if (!s.ignored) {
            $box.html('<p class="fia-note">Loading…</p>');
            try {
                s.ignored = await ApiClient.get(Config.API.discovery.ignored, { suppressGlobalError: true });
            } catch (xhr) {
                $box.html(UiStates.failed({ what: 'The ignored list', xhr, onRetry: () => drawIgnored() }));
                return;
            }
        }
        // Ten a page, like the list above (U20-T06).
        const pages = Math.max(1, Math.ceil(s.ignored.length / PAGE_SIZE));
        s.ignoredPage = Math.min(Math.max(1, s.ignoredPage), pages);
        const shown = Pagination.slice(s.ignored, s.ignoredPage, PAGE_SIZE);
        $box.html(s.ignored.length ? `<ul class="fia-ignored">${shown.map(r => Utils.html`<li>
                <span>${r.name || (r.kind === 'EKS' ? 'Cluster' : '(no name tag)')} · ${r.kind === 'EKS' ? 'EKS cluster' : 'EC2 instance'} · ${r.region} · ${r.accountName || ''}</span>
                <button type="button" class="vr-link" data-fia="unignore" data-id="${r.id}">Bring back</button></li>`).join('')}</ul>
                ${s.ignored.length > PAGE_SIZE ? Pagination.buildPager({ page: s.ignoredPage, pageSize: PAGE_SIZE, totalItems: s.ignored.length,
                    action: 'fia-ignored-page', key: 'found-ignored' }) : ''}`
            : '<p class="fia-note">Nothing is ignored.</p>');
    }

    async function unignore(id) {
        try {
            await ApiClient.delete(Config.API.discovery.unignore(id), { suppressGlobalError: true });
            s.ignored = null;
            Notifications.success('It shows in Found in AWS again');
            load(true);
        } catch (xhr) {
            Notifications.error("It wasn't brought back. Try again.");
        }
    }

    /** Add an instance: pick the environment and its group (or a new one), name it, and it is added in one call. */
    async function openAdd(key) {
        const item = itemByKey(key);
        if (!item) return;
        let envs = [];
        try {
            envs = (await ApiClient.get(Config.API.environments.list, { suppressGlobalError: true })) || [];
        } catch (xhr) {
            Notifications.error("The environments didn't load. Try again.");
            return;
        }
        const options = envs.filter(e => e.isActive !== false && String(e.serviceType || 'EC2').toUpperCase() !== 'EKS')
            .sort((a, b) => String(a.displayName || a.name).localeCompare(String(b.displayName || b.name)))
            .map(e => Utils.html`<option value="${e.environmentId}"${Utils.raw(e.environmentId === item.taggedEnvironmentId ? ' selected' : '')}>${e.displayName || e.name}</option>`).join('');
        Modals.show({
            id: 'fiaAddModal',
            title: `Add ${nameOf(item)} to an environment`,
            body: Utils.html`<form id="fia-add-form" class="fia-add" novalidate>
                <label class="fia-field">Environment<select class="form-select" id="fia-env">${Utils.raw(options)}</select></label>
                <label class="fia-field">Group<select class="form-select" id="fia-group"></select></label>
                <label class="fia-field" id="fia-new-group-field" hidden>New group's name<input type="text" class="form-control" id="fia-new-group" maxlength="100"></label>
                <label class="fia-field">VM name<input type="text" class="form-control" id="fia-name" maxlength="255" value="${item.name || ''}"></label>
                <p class="fia-error" id="fia-add-error" role="alert"></p>
            </form>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Add to environment', class: 'btn-primary', id: 'fia-add-save' }],
            onShow: () => fillGroups()
        });
        $('#fia-env').off('change.fia').on('change.fia', fillGroups);
        $('#fia-group').off('change.fia').on('change.fia', () => $('#fia-new-group-field').prop('hidden', $('#fia-group').val() !== '__new'));
        $('#fia-add-save').off('click.fia').on('click.fia', () => saveAdd(item));
    }

    async function fillGroups() {
        const envId = $('#fia-env').val();
        const $group = $('#fia-group').html('<option value="">Loading…</option>');
        if (!envId) return;
        try {
            const groups = (await ApiClient.get(Config.API.groups.list(envId), { suppressGlobalError: true })) || [];
            $group.html(groups.map(g => Utils.html`<option value="${g.groupId}">${g.displayName || g.name}</option>`).join('')
                + '<option value="__new">A new group…</option>');
        } catch (xhr) {
            $group.html('<option value="__new">A new group…</option>');
        }
        $('#fia-new-group-field').prop('hidden', $group.val() !== '__new');
    }

    async function saveAdd(item) {
        const envId = $('#fia-env').val();
        const group = $('#fia-group').val();
        const name = String($('#fia-name').val() || '').trim();
        const newGroup = String($('#fia-new-group').val() || '').trim();
        const $err = $('#fia-add-error').text('');
        if (!envId) return $err.text('Pick an environment.');
        if (name.length < 2) return $err.text('The VM needs a name of at least 2 characters.');
        if (group === '__new' && !newGroup) return $err.text("Name the new group, or pick one.");
        const body = { items: [{ ref: item.ref, name }] };
        if (group === '__new') body.newGroupName = newGroup; else body.groupId = group;
        $('#fia-add-save').prop('disabled', true).text('Adding…');
        try {
            await ApiClient.post(Config.API.vms.batchRegister(envId), body, { suppressGlobalError: true });
            Modals.hide('fiaAddModal');
            s.data.items = s.data.items.filter(i => i !== item);
            draw();
            Notifications.success(`${name} added to ${$('#fia-env option:selected').text() || 'the environment'}`);
        } catch (xhr) {
            $('#fia-add-save').prop('disabled', false).text('Add to environment');
            $err.text((xhr && xhr.responseJSON && xhr.responseJSON.message) || "It wasn't added. Try again.");
        }
    }

    function bind() {
        $(document).off('.fia')
            .on('click.fia', '#fia [data-fia="refresh"]', () => load(true))
            .on('click.fia', '#fia [data-fia="ignore"]', function() { ignore(this.dataset.key); })
            .on('click.fia', '#fia [data-fia="add"]', function() { openAdd(this.dataset.key); })
            .on('click.fia', '#fia [data-fia="add-cluster"]', () => {
                Slideout.close();
                if (typeof VmRegistry !== 'undefined' && VmRegistry.openCreateEnvironmentModal) VmRegistry.openCreateEnvironmentModal();
            })
            .on('click.fia', '#fia [data-fia="ignored"]', () => { s.showIgnored = !s.showIgnored; draw(); })
            .on('click.fia', '#fia [data-fia="unignore"]', function() { unignore(this.dataset.id); })
            .on('click.fia', '#fia [data-action="fia-page"]', function() {
                if (this.disabled) return;
                s.page = Number(this.dataset.page) || 1;
                draw();
            })
            .on('click.fia', '#fia [data-action="fia-ignored-page"]', function() {
                if (this.disabled) return;
                s.ignoredPage = Number(this.dataset.page) || 1;
                drawIgnored();
            })
            .on('change.fia', '#fia-region', function() { s.region = this.value; s.page = 1; draw(); })
            .on('change.fia', '#fia-kind', function() { s.kind = this.value; s.page = 1; draw(); })
            .on('input.fia', '#fia-search', Utils.debounce ? Utils.debounce(function() { s.search = this.value; s.page = 1; redrawList(); }, 200)
                : function() { s.search = this.value; s.page = 1; redrawList(); });
    }

    /** Typing in the search redraws the list only, so the box keeps its focus. */
    function redrawList() {
        const rows = filterItems(s.data.items || [], s);
        $('#fia-list').html(listHtml(rows));
        $('#fia-pager').html(pagerHtml(rows));
    }

    return { open, model: { filterItems, regionsOf, summaryText, nameOf, PAGE_SIZE } };
})();

if (typeof window !== 'undefined') window.FoundInAws = FoundInAws;
