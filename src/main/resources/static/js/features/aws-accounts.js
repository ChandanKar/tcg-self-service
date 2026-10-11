/**
 * AWS Accounts page (E28-T09), #/aws-accounts, admins only: the account registry with each
 * account's verification state, add/edit (role ARN, external ID, regions, billing account),
 * verify, deactivate, and import from AWS Organizations when discovery is on.
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
        $('#content-area').html(`
            <div class="content-view aws-accounts-page" id="aws-accounts-view">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fab fa-aws me-2" aria-hidden="true"></i>AWS accounts</h1>
                        <p class="text-muted mb-0">Member accounts are reached by assuming their role from the platform's own identity</p>
                    </div>
                    <div class="d-flex gap-2">
                        <button type="button" class="btn btn-ghost" id="aws-import-btn" data-action="aws-account-import" hidden>
                            <i class="fas fa-sitemap" aria-hidden="true"></i> Import from AWS Organizations</button>
                        <button type="button" class="btn btn-primary" data-action="aws-account-new"><i class="fas fa-plus" aria-hidden="true"></i> Add account</button>
                    </div>
                </div>
                <div id="aws-account-list"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading accounts…</div></div>
            </div>`);
        refresh();
        // The import button only shows when Organizations discovery answers (it is off by default).
        const token = pageToken;
        call('GET', Config.API.awsAccounts.candidates).then(() => {
            if (ContentRouter.isCurrent(token)) $('#aws-import-btn').prop('hidden', false);
        }).catch(() => {});
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

    function relative(ts) {
        if (!ts) return 'never';
        const minutes = Math.round((Date.now() - new Date(ts).getTime()) / 60000);
        if (minutes < 60) return `${Math.max(minutes, 0)} min ago`;
        if (minutes < 1440) return `${Math.round(minutes / 60)} h ago`;
        return `${Math.round(minutes / 1440)} d ago`;
    }

    function verification(a) {
        if (a.lastError) {
            return Utils.html`<span class="badge text-bg-danger" title="${a.lastError}">Failed ${relative(a.lastVerifiedAt)}</span>`;
        }
        return a.lastVerifiedAt
            ? Utils.html`<span class="badge text-bg-success">Verified ${relative(a.lastVerifiedAt)}</span>`
            : '<span class="badge text-bg-light border">Not verified</span>';
    }

    function draw() {
        const rows = accounts.map(a => Utils.html`<tr data-ref="${a.awsAccountRef}">
            <td data-label="Name" class="fw-semibold">${a.displayName}${Utils.raw(a.isDefault ? ' <span class="badge text-bg-light border">Default</span>' : '')}</td>
            <td data-label="Account">${a.accountNumber || '—'}</td>
            <td data-label="Role ARN" class="text-truncate" style="max-width: 18rem" title="${a.roleArn || ''}">${a.roleArn || 'The platform\'s own credentials'}</td>
            <td data-label="Regions">${a.regions.length ? a.regions.join(', ') : 'Default regions'}</td>
            <td data-label="Environments">${a.environmentCount}</td>
            <td data-label="Billing">${Utils.raw(a.isBillingAccount ? '<i class="fas fa-check" aria-label="Billing account"></i>' : '')}</td>
            <td data-label="Status">${Utils.raw(a.isActive ? '<span class="badge text-bg-success">Active</span>' : '<span class="badge text-bg-secondary">Inactive</span>')}</td>
            <td data-label="Last verified">${Utils.raw(verification(a))}</td>
            <td data-label="" class="text-end text-nowrap">
                <button type="button" class="btn btn-sm btn-ghost" data-action="aws-account-verify" data-ref="${a.awsAccountRef}" aria-label="Verify ${a.displayName}"><i class="fas fa-plug" aria-hidden="true"></i></button>
                <button type="button" class="btn btn-sm btn-ghost" data-action="aws-account-edit" data-ref="${a.awsAccountRef}" aria-label="Edit ${a.displayName}"><i class="fas fa-pen" aria-hidden="true"></i></button>
                ${Utils.raw(a.isDefault || !a.isActive ? '' : Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="aws-account-deactivate" data-ref="${a.awsAccountRef}" aria-label="Deactivate ${a.displayName}"><i class="fas fa-ban" aria-hidden="true"></i></button>`)}
            </td></tr>`).join('');
        $('#aws-account-list').html(`<section class="card"><div class="card-body"><div class="table-responsive">
            <table class="table table-sm table-cards mb-0" id="aws-account-table"><thead><tr><th>Name</th><th>Account</th><th>Role ARN</th>
            <th>Regions</th><th>Environments</th><th>Billing</th><th>Status</th><th>Last verified</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
            <tbody>${rows}</tbody></table></div></div></section>`);
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
                <div class="text-danger small mt-2" id="acct-error" role="alert"></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: a ? 'Save' : 'Add account', class: 'btn-primary', id: 'acct-save' }],
            onShow: () => $('#acct-name').trigger('focus')
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

    function verify(ref) {
        call('POST', Config.API.awsAccounts.verify(ref)).then(a => {
            if (a.lastError) Notifications.showError(`Verification failed: ${a.lastError}`);
            else Notifications.success(`${a.displayName} verified.`);
            refresh();
        }).catch(e => Notifications.showError(serverMessage(e, 'Could not verify the account.')));
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
                id: 'awsImportModal', title: 'Import from AWS Organizations', size: 'lg',
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

    return { load, openForm, verify, deactivate, openImport };
})();

window.AwsAccounts = AwsAccounts;

Actions.registerAll({
    'aws-account-new': () => AwsAccounts.openForm(null),
    'aws-account-edit': el => AwsAccounts.openForm(el.dataset.ref),
    'aws-account-verify': el => AwsAccounts.verify(el.dataset.ref),
    'aws-account-deactivate': el => AwsAccounts.deactivate(el.dataset.ref),
    'aws-account-import': () => AwsAccounts.openImport()
});
