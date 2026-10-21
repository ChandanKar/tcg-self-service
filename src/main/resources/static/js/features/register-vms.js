/**
 * VM Self-Service Platform - Add VMs (U11-T04, RegisterVms.dc.html)
 * #/vm-registry/<environmentId>/add: the EC2 instances (or RDS databases) of an account and region
 * by name, several picked at once and added to one group after its current VMs. Instance ids never
 * reach the page for people who may not see them: each row carries an opaque reference (U01-T02).
 */
const RegisterVmsPage = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const NEW_GROUP = '__new';
    const ADDABLE = ['AVAILABLE', 'DISCOVERED_UNGROUPED'];
    const ENDED = ['STALE'];

    let s = null; // page state

    // ------------------------------------------------------------------ model

    function count(n, word) {
        return `${n} ${word}${n === 1 ? '' : 's'}`;
    }

    function ordinal(n) {
        const mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
        return `${n}${{ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th'}`;
    }

    /** "Web · starts 3rd", last first (new VMs usually join the last group), then "New group…". */
    function groupOptions(groups) {
        const ordered = (groups || []).filter(g => g.name !== 'discovered')
            .slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        return ordered.map((g, i) => ({ value: g.groupId, label: `${g.displayName || g.name} · starts ${ordinal(i + 1)}` }))
            .reverse()
            .concat([{ value: NEW_GROUP, label: 'New group…' }]);
    }

    /** The account's own regions when it has some, else every known region. */
    function regionsFor(accountRef, accounts, allRegions) {
        const account = (accounts || []).find(a => a.ref === accountRef);
        return account && account.regions && account.regions.length
            ? account.regions.map(value => ({ value, label: value }))
            : (allRegions || []).map(r => ({ value: r.value, label: `${r.value} (${r.label.replace(/^.*\((.*)\)$/, '$1')})` }));
    }

    function canAdd(c) {
        return ADDABLE.includes(c.status);
    }

    /** The rows to show: search by name or IP; ended instances only when asked for. */
    function visibleRows(candidates, search, showEnded) {
        const term = (search || '').trim().toLowerCase();
        return (candidates || []).filter(c => (showEnded || !ENDED.includes(c.status))
            && (!term || (c.name || '').toLowerCase().includes(term) || (c.privateIp || '').includes(term)));
    }

    function stateText(state) {
        const v = (state || '').replace(/[-_]/g, ' ');
        return v ? v.charAt(0).toUpperCase() + v.slice(1) : '';
    }

    /** The VM's name: its Name tag, else one made from its address, so several untagged ones differ. */
    function vmName(c) {
        if (c.name) return c.name;
        return c.privateIp ? `vm-${c.privateIp.replace(/\./g, '-')}` : 'unnamed-vm';
    }

    function ctaText(n) {
        return n ? `Add ${count(n, 'VM')}` : 'Pick VMs to add';
    }

    function hintText(n) {
        return n ? `${n} selected · they start after the group's current VMs` : 'Tick the VMs to add.';
    }

    /** The batch body (BatchRegisterVmsDTO). */
    function batchBody(group, newGroupName, picked) {
        const items = picked.map(c => ({ ref: c.ref, name: vmName(c) }));
        return group === NEW_GROUP ? { newGroupName: (newGroupName || '').trim(), items } : { groupId: group, items };
    }

    // ------------------------------------------------------------------ page

    /** Router loader: params.environmentId from the path; query.group preselects a group. */
    async function load(params, query) {
        const token = ContentRouter.token();
        const environmentId = params && params.environmentId;
        s = { token, environmentId, env: null, groups: [], accounts: [], kind: 'EC2', rds: false, candidates: [],
            picked: new Map(), page: 1, search: '', showEnded: false, loading: false, preselect: query && query.group };
        $('#content-area').html('<div class="content-view rv-page"><div class="vr-empty">Loading…</div></div>');
        try {
            const [env, groups, accounts, rds] = await Promise.all([
                ApiClient.get(Config.API.environments.get(environmentId)),
                ApiClient.get(Config.API.groups.list(environmentId)),
                typeof AwsAccountOptions !== 'undefined' ? AwsAccountOptions.load() : Promise.resolve([]),
                new Promise(resolve => ApiClient.get(Config.API.rds.status, { suppressGlobalError: true })
                    .done(r => resolve(!!(r && r.enabled))).fail(() => resolve(false)))
            ]);
            if (!ContentRouter.isCurrent(token)) return;
            s.env = env;
            s.groups = groups || [];
            s.accounts = accounts || [];
            s.rds = rds;
            render();
            fetchCandidates();
        } catch (error) {
            if (!ContentRouter.isCurrent(token)) return;
            $('#content-area').html(`<div class="content-view rv-page">${PageHeader.markup({ title: 'Add VMs',
                breadcrumb: [{ label: 'VM registry', href: '#/vm-registry' }, { label: 'Add VMs' }] })}
                <div class="vr-empty">This environment could not be loaded.</div></div>`);
        }
    }

    function envName() {
        return s.env.displayName || s.env.name;
    }

    function backHash() {
        return `#/vm-registry?env=${encodeURIComponent(s.environmentId)}`;
    }

    function render() {
        const account = s.env.awsAccountRef || (s.accounts.find(a => a.isDefault) || {}).ref || null;
        const multi = typeof AwsAccountOptions !== 'undefined' && AwsAccountOptions.isMulti(s.accounts);
        const groups = groupOptions(s.groups);
        const preselected = groups.some(g => g.value === s.preselect) ? s.preselect : groups[0].value;
        $('#content-area').html(`
            <div class="content-view rv-page" id="register-vms-view">
                ${PageHeader.markup({ title: `Add VMs to ${envName()}`,
                    breadcrumb: [{ label: 'VM registry', href: backHash() }, { label: envName() }],
                    summaryHtml: 'Pick instances from AWS. They join a group and start in its order.' })}
                <section class="rv-card" aria-label="Instances">
                    <div class="rv-tools">
                        <div class="rv-kind" role="group" aria-label="Kind"${s.rds ? '' : ' hidden'}>
                            <button type="button" data-rv-kind="EC2" aria-pressed="true">Servers (EC2)</button>
                            <button type="button" data-rv-kind="RDS" aria-pressed="false">Databases (RDS)</button>
                        </div>
                        <label class="rv-field" ${multi ? '' : 'hidden'}>AWS account
                            <select class="form-select" id="rv-account">${multi ? AwsAccountOptions.optionsHtml(s.accounts, account) : ''}</select></label>
                        <label class="rv-field">Region<select class="form-select" id="rv-region"></select></label>
                        <label class="rv-field rv-search">Search<input type="search" class="form-control" id="rv-search" placeholder="Name tag or IP" autocomplete="off"></label>
                    </div>
                    <div class="rv-table-wrap" id="rv-table-wrap"></div>
                    <div class="rv-below" id="rv-below"></div>
                    <div class="rv-foot">
                        <label class="rv-field">Add to group
                            <select class="form-select" id="rv-group">${groups.map(g => Utils.html`<option value="${g.value}"${Utils.raw(g.value === preselected ? ' selected' : '')}>${g.label}</option>`).join('')}</select></label>
                        <label class="rv-field" id="rv-new-group-row" hidden>Name the new group
                            <input type="text" class="form-control" id="rv-new-group" maxlength="100" placeholder="e.g. Reports"></label>
                        <span class="rv-hint" id="rv-hint" aria-live="polite"></span>
                        <a class="btn btn-outline-secondary" href="${Utils.escapeHtml(backHash())}">Cancel</a>
                        <button type="button" class="btn btn-primary" id="rv-add" disabled>Pick VMs to add</button>
                    </div>
                    <div class="rv-error" id="rv-error" role="alert"></div>
                </section>
            </div>`);
        fillRegions(account);
        toggleNewGroup();
        updateFoot();
        bind();
    }

    function fillRegions(accountRef) {
        const regions = regionsFor(accountRef, s.accounts, Config.AWS_REGIONS);
        const wanted = (s.env.regions || []).find(r => regions.some(x => x.value === r)) || regions[0]?.value;
        $('#rv-region').html(regions.map(r => Utils.html`<option value="${r.value}"${Utils.raw(r.value === wanted ? ' selected' : '')}>${r.label}</option>`).join(''));
    }

    function accountRef() {
        const $account = $('#rv-account');
        return $account.closest('label').prop('hidden') ? null : ($account.val() || null);
    }

    async function fetchCandidates() {
        const token = s.token;
        const region = $('#rv-region').val();
        const kind = s.kind;
        s.loading = true;
        s.picked.clear();
        s.page = 1;
        $('#rv-table-wrap').html(`<div class="vr-empty">Looking for ${kind === 'RDS' ? 'databases' : 'instances'} in ${Utils.escapeHtml(region)}…</div>`);
        $('#rv-below').empty();
        updateFoot();
        try {
            const list = await ApiClient.get(Config.API.vms.candidates(s.environmentId, { region, accountRef: accountRef(), source: kind }),
                { suppressGlobalError: true });
            if (!ContentRouter.isCurrent(token) || kind !== s.kind || region !== $('#rv-region').val()) return;
            s.candidates = list || [];
            s.loading = false;
            renderRows();
        } catch (error) {
            if (!ContentRouter.isCurrent(token)) return;
            s.loading = false;
            s.candidates = [];
            $('#rv-table-wrap').html(Utils.html`<div class="vr-empty rv-fail">Couldn't list ${kind === 'RDS' ? 'databases' : 'instances'} in ${region}.
                Check the AWS account in Settings &gt; AWS accounts, then try again.
                <button type="button" class="vr-link" id="rv-retry">Try again</button></div>`);
        }
    }

    function renderRows() {
        const rows = visibleRows(s.candidates, s.search, s.showEnded);
        const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
        s.page = Math.min(Math.max(1, s.page), pages);
        const items = Pagination.slice(rows, s.page, PAGE_SIZE);
        const what = s.kind === 'RDS' ? 'databases' : 'instances';
        if (!s.candidates.length) {
            $('#rv-table-wrap').html(`<div class="vr-empty">No ${what} in this region.</div>`);
        } else if (!rows.length) {
            $('#rv-table-wrap').html(Utils.html`<div class="vr-empty">No ${what} match "${s.search}".</div>`);
        } else {
            $('#rv-table-wrap').html(`<table class="rv-table" id="rv-table">
                    <thead><tr>
                        <th scope="col" class="rv-check"><span class="visually-hidden">Select</span></th>
                        <th scope="col">Name</th><th scope="col">Size</th><th scope="col">Zone</th><th scope="col">IP address</th>
                        <th scope="col">State</th><th scope="col">Note</th>
                    </tr></thead>
                    <tbody>${items.map(rowHtml).join('')}</tbody>
                </table>`);
        }
        const ended = s.candidates.filter(c => ENDED.includes(c.status)).length;
        $('#rv-below').html(`${rows.length > PAGE_SIZE ? Pagination.buildPager({ page: s.page, pageSize: PAGE_SIZE,
                totalItems: rows.length, action: 'rv-page', key: 'candidates' }) : ''}
            ${ended ? Utils.html`<label class="rv-ended"><input type="checkbox" id="rv-show-ended"${Utils.raw(s.showEnded ? ' checked' : '')}>
                Show ended instances (${ended})</label>` : ''}`);
        updateFoot();
    }

    function rowHtml(c) {
        const taken = !canAdd(c);
        const name = c.name || '(no name tag)';
        return Utils.html`<tr class="rv-row${Utils.raw(taken ? ' rv-taken' : '')}" data-ref="${c.ref}">
                <td class="rv-check"><input type="checkbox" class="rv-pick" data-ref="${c.ref}" aria-label="Select ${name}"
                    ${Utils.raw(taken ? 'disabled' : '')}${Utils.raw(s.picked.has(c.ref) ? ' checked' : '')}></td>
                <td class="rv-name">${name}</td>
                <td class="rv-mono">${c.instanceType || ''}</td>
                <td class="rv-mono">${c.zone || ''}</td>
                <td class="rv-mono">${c.privateIp || ''}</td>
                <td>${stateText(c.state)}</td>
                <td class="rv-note">${c.note || ''}</td>
            </tr>`;
    }

    function toggleNewGroup() {
        const isNew = $('#rv-group').val() === NEW_GROUP;
        $('#rv-new-group-row').prop('hidden', !isNew);
        if (isNew) $('#rv-new-group').trigger('focus');
    }

    function updateFoot() {
        const n = s.picked.size;
        $('#rv-hint').text(hintText(n));
        const needsName = $('#rv-group').val() === NEW_GROUP && !($('#rv-new-group').val() || '').trim();
        $('#rv-add').text(ctaText(n)).prop('disabled', !n || needsName || s.loading);
    }

    async function add() {
        const picked = [...s.picked.values()];
        if (!picked.length) return;
        const group = $('#rv-group').val();
        const groupLabel = group === NEW_GROUP ? ($('#rv-new-group').val() || '').trim()
            : ((s.groups.find(g => g.groupId === group) || {}).displayName || 'the group');
        $('#rv-error').text('');
        $('#rv-add').prop('disabled', true).text('Adding…');
        try {
            await ApiClient.post(Config.API.vms.batchRegister(s.environmentId), batchBody(group, $('#rv-new-group').val(), picked),
                { suppressGlobalError: true });
            Notifications.success(`Added ${count(picked.length, 'VM')} to ${groupLabel}.`);
            window.location.hash = backHash();
        } catch (xhr) {
            $('#rv-error').text((xhr && xhr.responseJSON && xhr.responseJSON.message) || "The VMs weren't added. Try again.");
            updateFoot();
        }
    }

    function bind() {
        const NS = '.registerVms';
        const $root = $('#register-vms-view');
        $root.off(NS)
            .on('click' + NS, '[data-rv-kind]', function() {
                const kind = this.dataset.rvKind;
                if (kind === s.kind) return;
                s.kind = kind;
                $root.find('[data-rv-kind]').each(function() { this.setAttribute('aria-pressed', String(this.dataset.rvKind === kind)); });
                fetchCandidates();
            })
            .on('change' + NS, '#rv-account', function() {
                fillRegions(this.value);
                fetchCandidates();
            })
            .on('change' + NS, '#rv-region', () => fetchCandidates())
            .on('click' + NS, '#rv-retry', () => fetchCandidates())
            .on('input' + NS, '#rv-search', function() {
                s.search = this.value;
                s.page = 1;
                renderRows();
            })
            .on('change' + NS, '.rv-pick', function() {
                const c = s.candidates.find(x => x.ref === this.dataset.ref);
                if (!c || !canAdd(c)) return;
                if (this.checked) s.picked.set(c.ref, c); else s.picked.delete(c.ref);
                updateFoot();
            })
            .on('click' + NS, '[data-action="rv-page"]', function() {
                s.page = Number(this.dataset.page) || 1;
                renderRows();
            })
            .on('change' + NS, '#rv-show-ended', function() {
                s.showEnded = this.checked;
                renderRows();
            })
            .on('change' + NS, '#rv-group', () => { toggleNewGroup(); updateFoot(); })
            .on('input' + NS, '#rv-new-group', () => updateFoot())
            .on('click' + NS, '#rv-add', () => add());
    }

    return {
        load,
        model: { ordinal, groupOptions, regionsFor, canAdd, visibleRows, stateText, vmName, ctaText, hintText, batchBody, NEW_GROUP }
    };
})();

if (typeof window !== 'undefined') window.RegisterVmsPage = RegisterVmsPage;
