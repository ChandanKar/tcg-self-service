/**
 * AWS Accounts page (E28-T09, U14-T03 AwsAccounts.dc.html), #/aws-accounts, admins only: each
 * account with its ID in groups of four ("4183 2297 0561"), its access in words ("Platform
 * credentials", "Assumes PlatformOperator role"), regions, environments and the last check (a dot
 * and "OK · today 06:00" or what went wrong, AWS's message in the tooltip). Check access updates the
 * row in place; Edit holds Deactivate (never for the default account). Import from AWS Organizations
 * while discovery is on; otherwise a line says how to switch it on.
 */
const AwsAccounts = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const REGION = /^[a-z]{2}(-[a-z]+)+-\d$/;
    const ARN = /^arn:aws(-[a-z]+)?:iam::\d{12}:role\/[\w+=,.@/-]+$/;
    let pageToken = null;
    let accounts = [];

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        // The import button only shows while Organizations discovery is on (off by default); the
        // candidates are fetched when it is used, not on every visit (U02-T03).
        const orgs = typeof Capabilities !== 'undefined' && Capabilities.has('awsOrganizations');
        $('#content-area').html(`
            <div class="content-view aws-accounts-page" id="aws-accounts-view">
                ${PageHeader.markup({ title: 'AWS accounts',
                    summaryHtml: 'The AWS accounts the platform can reach, and the role it uses in each.',
                    actionsHtml: `${orgs ? '<button type="button" class="btn btn-secondary" id="aws-import-btn" data-action="aws-account-import">Find accounts in AWS Organizations</button>' : ''}
                        <button type="button" class="btn btn-primary" data-action="aws-account-new">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        Add an account</button>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'aws-accounts' : null, helpLabel: 'AWS accounts' })}
                <div id="aws-account-list"><div class="aa-note" role="status">Loading accounts…</div></div>
                ${orgs ? '' : '<p class="aa-note" id="aws-orgs-off">Finding accounts automatically from AWS Organizations is off. It is switched on in the server settings (AWS_ORGANIZATIONS_ENABLED).</p>'}
            </div>`);
        refresh();
    }

    function refresh() {
        const token = pageToken;
        call('GET', Config.API.awsAccounts.list).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            accounts = list || [];
            draw();
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#aws-account-list').html(Utils.html`<div class="cost-panel-error" role="alert"><span>${serverMessage(e, "Couldn't load the accounts.")}</span></div>`);
        });
    }

    // ---- pure model (node tests: src/test/js/aws-accounts.test.mjs) ----

    /** "4183 2297 0561" for 418322970561. */
    function accountId(number) {
        return number ? String(number).replace(/\D/g, '').replace(/(\d{4})(?=\d)/g, '$1 ') : '—';
    }

    /** "Platform credentials", or "Assumes PlatformOperator role" from the role ARN. */
    function access(a) {
        if (!a.roleArn) return 'Platform credentials';
        const name = String(a.roleArn).split(':role/')[1] || '';
        return `Assumes ${name.split('/').pop() || 'a'} role`;
    }

    /** "today 06:00", "yesterday 18:30", "18 Oct 06:00". */
    function when(ts, now = new Date()) {
        const d = new Date(ts);
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const days = Math.round((new Date(now.getFullYear(), now.getMonth(), now.getDate())
            - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
        if (days === 0) return `today ${time}`;
        if (days === 1) return `yesterday ${time}`;
        return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
    }

    /** The Last check column: {ok, text, title}; ok is null when it was never checked. */
    function lastCheck(a, now = new Date()) {
        if (a.lastError) return { ok: false, text: a.lastErrorSummary || 'Access check failed', title: a.lastError };
        if (a.lastVerifiedAt) return { ok: true, text: `OK · ${when(a.lastVerifiedAt, now)}`, title: '' };
        return { ok: null, text: 'Not checked yet', title: '' };
    }

    function rowHtml(a) {
        const check = lastCheck(a);
        const dot = check.ok === null ? 'none' : check.ok ? 'ok' : 'bad';
        return Utils.html`<tr data-ref="${a.awsAccountRef}"${Utils.raw(a.isActive ? '' : ' class="aa-inactive"')}>
            <td data-label="Account" class="aa-name">${a.displayName}${Utils.raw(a.isDefault ? ' <span class="aa-tag">Default</span>' : '')}${Utils.raw(a.isActive ? '' : ' <span class="aa-tag aa-tag-off">Deactivated</span>')}</td>
            <td data-label="Account ID" class="aa-mono aa-id">${accountId(a.accountNumber)}</td>
            <td data-label="Access" title="${a.roleArn || ''}">${access(a)}</td>
            <td data-label="Regions" class="aa-mono">${Utils.raw(a.regions.length
                ? a.regions.map(r => Utils.html`<span class="aa-nowrap">${r}</span>`).join(', ') : 'Default regions')}</td>
            <td data-label="Environments" class="aa-mono">${a.environmentCount}</td>
            <td data-label="Last check"><span class="aa-check" title="${check.title}"><span class="aa-dot aa-dot-${dot}" aria-hidden="true"></span>${check.text}</span></td>
            <td data-label="" class="aa-actions">
                <button type="button" class="aa-link" data-action="aws-account-verify" data-ref="${a.awsAccountRef}" aria-label="Check access to ${a.displayName}">Check access</button>
                <button type="button" class="aa-link" data-action="aws-account-edit" data-ref="${a.awsAccountRef}" aria-label="Edit ${a.displayName}">Edit</button>
            </td></tr>`;
    }

    function draw() {
        $('#aws-account-list').html(accounts.length ? `<section class="aa-card"><div class="aa-scroll">
            <table class="aa-table" id="aws-account-table"><thead><tr><th scope="col">Account</th><th scope="col">Account ID</th>
            <th scope="col">Access</th><th scope="col">Regions</th><th scope="col">Environments</th><th scope="col">Last check</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
            <tbody>${accounts.map(rowHtml).join('')}</tbody></table></div></section>`
            : '<div class="aa-note">No accounts yet. Add one with Add an account.</div>');
    }

    function regionsOf(text) {
        return text.split(',').map(s => s.trim()).filter(Boolean);
    }

    function openForm(ref) {
        const a = ref ? accounts.find(x => x.awsAccountRef === ref) : null;
        Modals.show({
            id: 'awsAccountModal',
            title: a ? `Edit ${a.displayName}` : 'Add AWS account',
            body: Utils.html`
                <div class="mb-2"><label class="form-label" for="acct-name">Display name</label>
                    <input class="form-control" id="acct-name" maxlength="255" value="${a ? a.displayName : ''}" required></div>
                <div class="mb-2"><label class="form-label" for="acct-arn">Role ARN</label>
                    <input class="form-control" id="acct-arn" placeholder="arn:aws:iam::123456789012:role/VmControlAccess" value="${a && a.roleArn ? a.roleArn : ''}" ${a && a.isDefault ? '' : 'required'}>
                    ${Utils.raw(a && a.isDefault ? '<div class="form-text">Leave empty to use the platform\'s own credentials.</div>' : '')}</div>
                <div class="mb-2"><label class="form-label" for="acct-ext">External ID</label>
                    <input class="form-control" id="acct-ext" type="password" autocomplete="off" placeholder="${a && a.externalIdMasked ? a.externalIdMasked + ' (blank keeps it)' : 'Optional'}"></div>
                <div class="mb-2"><label class="form-label" for="acct-regions">Regions</label>
                    <input class="form-control" id="acct-regions" placeholder="eu-west-1, us-east-1" value="${a ? a.regions.join(', ') : ''}">
                    <div class="form-text">Comma-separated; empty means the platform's default regions.</div></div>
                <div class="form-check"><input class="form-check-input" type="checkbox" id="acct-billing" ${a && a.isBillingAccount ? 'checked' : ''}>
                    <label class="form-check-label" for="acct-billing">Billing account (reads Cost Explorer and AWS Organizations)</label></div>
                ${Utils.raw(a && !a.isDefault && a.isActive ? '<div class="aa-danger-zone"><button type="button" class="aa-link aa-danger" id="acct-deactivate">Deactivate account</button><span class="aa-note">It is no longer scanned and its environments cannot start.</span></div>' : '')}
                <div class="text-danger small mt-2" id="acct-error" role="alert"></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: a ? 'Save' : 'Add account', class: 'btn-primary', id: 'acct-save' }],
            onShow: () => $('#acct-name').trigger('focus')
        });
        $('#acct-deactivate').on('click', () => {
            Modals.hideWhenReady(document.getElementById('awsAccountModal'));
            deactivate(a.awsAccountRef);
        });
        $('#acct-save').on('click', function() {
            const roleArn = $('#acct-arn').val().trim();
            const regions = regionsOf($('#acct-regions').val());
            const errors = [];
            if (!$('#acct-name').val().trim()) errors.push('A display name is required.');
            if (roleArn && !ARN.test(roleArn)) errors.push('The role ARN must look like arn:aws:iam::123456789012:role/Name.');
            if (!roleArn && !(a && a.isDefault)) errors.push('A role ARN is required.');
            const badRegion = regions.find(r => !REGION.test(r));
            if (badRegion) errors.push(`"${badRegion}" is not an AWS region.`);
            if (errors.length) {
                $('#acct-error').text(errors.join(' '));
                return;
            }
            const body = {
                displayName: $('#acct-name').val().trim(), roleArn: roleArn || null,
                externalId: $('#acct-ext').val() || null, regions, isBillingAccount: $('#acct-billing').is(':checked')
            };
            const $btn = $(this).prop('disabled', true);
            (a ? call('PUT', Config.API.awsAccounts.item(a.awsAccountRef), body) : call('POST', Config.API.awsAccounts.list, body))
                .then(() => {
                    Modals.hideWhenReady(document.getElementById('awsAccountModal'));
                    Notifications.success(a ? 'Account saved.' : 'Account added. Verify it to check the role.');
                    refresh();
                })
                .catch(e => { $('#acct-error').text(serverMessage(e, 'Could not save the account.')); $btn.prop('disabled', false); });
        });
    }

    /** Check access: the row shows the result in place, nothing else reloads. */
    function verify(ref) {
        const $btn = $(`#aws-account-table tr[data-ref="${ref}"] [data-action="aws-account-verify"]`).prop('disabled', true).text('Checking…');
        call('POST', Config.API.awsAccounts.verify(ref)).then(a => {
            const i = accounts.findIndex(x => x.awsAccountRef === ref);
            if (i >= 0) accounts[i] = a;
            $(`#aws-account-table tr[data-ref="${ref}"]`).replaceWith(rowHtml(a));
            if (a.lastError) Notifications.showError(`${a.displayName}: ${a.lastErrorSummary || 'access check failed'}.`);
            else Notifications.success(`${a.displayName}: access OK.`);
        }).catch(e => {
            $btn.prop('disabled', false).text('Check access');
            Notifications.showError(serverMessage(e, 'Could not check the account.'));
        });
    }

    function deactivate(ref) {
        const a = accounts.find(x => x.awsAccountRef === ref);
        if (!a) return;
        Modals.confirm('Deactivate account', `Deactivate "${a.displayName}"? Its clients are dropped and it is no longer scanned.`, () => {
            call('DELETE', Config.API.awsAccounts.item(ref))
                .then(() => { Notifications.success('Account deactivated.'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not deactivate the account.')));
        }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
    }

    function openImport() {
        call('GET', Config.API.awsAccounts.candidates).then(candidates => {
            const fresh = (candidates || []).filter(c => !c.alreadyRegistered);
            const rows = (candidates || []).map((c, i) => Utils.html`<tr>
                <td><input class="form-check-input" type="checkbox" id="imp-${i}" data-index="${i}" ${c.alreadyRegistered ? 'disabled' : ''} aria-label="Import ${c.name}"></td>
                <td>${c.name}<div class="small text-muted">${c.accountNumber}${c.alreadyRegistered ? ' · registered' : ''}</div></td>
                <td><input class="form-control form-control-sm imp-arn" data-index="${i}" value="${c.proposedRoleArn}" ${c.alreadyRegistered ? 'disabled' : ''}
                    aria-label="Role ARN for ${c.name}"></td></tr>`).join('');
            Modals.show({
                id: 'awsImportModal', title: 'Find accounts in AWS Organizations', size: 'lg',
                body: (fresh.length === 0 ? '<p class="text-muted">Every active member account is registered already.</p>' : '') + `
                    <div class="table-responsive"><table class="table table-sm"><thead><tr><th></th><th>Account</th><th>Role ARN</th></tr></thead>
                    <tbody>${rows}</tbody></table></div>
                    <div class="row g-2"><div class="col-sm-6"><label class="form-label" for="imp-ext">External ID for all</label>
                        <input class="form-control" id="imp-ext" type="password" autocomplete="off" placeholder="Optional"></div>
                    <div class="col-sm-6"><label class="form-label" for="imp-regions">Regions for all</label>
                        <input class="form-control" id="imp-regions" placeholder="Default regions"></div></div>
                    <div class="small mt-2" id="imp-result" role="status"></div>`,
                buttons: [{ text: 'Close', class: 'btn-secondary', dismiss: true }, { text: 'Import selected', class: 'btn-primary', id: 'imp-save' }]
            });
            $('#imp-save').on('click', function() {
                const chosen = $('#awsImportModal input[type=checkbox]:checked').toArray().map(el => {
                    const c = candidates[Number(el.dataset.index)];
                    return { accountNumber: c.accountNumber, displayName: c.name,
                        roleArn: $(`.imp-arn[data-index="${el.dataset.index}"]`).val().trim(),
                        externalId: $('#imp-ext').val() || null, regions: regionsOf($('#imp-regions').val()) };
                });
                if (!chosen.length) { $('#imp-result').text('Choose at least one account.'); return; }
                const $btn = $(this).prop('disabled', true);
                call('POST', Config.API.awsAccounts.import, { accounts: chosen }).then(r => {
                    $('#imp-result').html(Utils.html`${r.created.length} imported${r.skipped.length ? ', ' + r.skipped.length + ' skipped: '
                        + r.skipped.map(s => s.accountNumber + ' (' + s.reason + ')').join(', ') : ''}.`);
                    refresh();
                }).catch(e => { $('#imp-result').text(serverMessage(e, 'Could not import.')); $btn.prop('disabled', false); });
            });
        }).catch(e => Notifications.showError(serverMessage(e, 'Could not read the organization.')));
    }

    return { load, openForm, verify, deactivate, openImport, model: { accountId, access, when, lastCheck } };
})();

if (typeof window !== 'undefined') window.AwsAccounts = AwsAccounts;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'aws-account-new': () => AwsAccounts.openForm(null),
    'aws-account-edit': el => AwsAccounts.openForm(el.dataset.ref),
    'aws-account-verify': el => AwsAccounts.verify(el.dataset.ref),
    'aws-account-deactivate': el => AwsAccounts.deactivate(el.dataset.ref),
    'aws-account-import': () => AwsAccounts.openImport()
});
