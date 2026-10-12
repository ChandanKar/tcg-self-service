/**
 * Settings page (E29-T04), #/settings, admins only: every runtime setting by group. Switches
 * save at once (with Undo for a few seconds), numbers are checked against their range before
 * Save, Reset returns to the configured default, and restart-only settings are shown read-only.
 * Switches that change AWS resources or hold starts ask first.
 */
const Settings = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const UNDO_MS = 8000;
    /** Switches whose effect reaches AWS or users' starts: confirmed before they change. */
    const RISKY = {
        'cost.tagging.enabled': 'Tag reconciliation writes cost-allocation tags to your AWS resources.',
        'automation.rules.enabled': 'Automation rules start and stop VMs on their schedules.',
        'approvals.enabled': 'Starts that match an approval policy will wait for an approver.',
        'quotas.enabled': 'Starts that would exceed a team quota will warn, wait for approval or be refused.'
    };
    let pageToken = null;
    let settings = [];

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
            $('#content-area').html('<div class="alert alert-danger m-3" role="alert">Access denied. Admin only.</div>');
            return;
        }
        $('#content-area').html(`
            <div class="content-view settings-page" id="settings-view">
                <div class="content-header">
                    <h1><i class="fas fa-sliders-h me-2" aria-hidden="true"></i>Settings</h1>
                    <p class="text-muted mb-0">Changes apply within 30 seconds on every server; no restart needed</p>
                </div>
                <div id="settings-groups"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading settings…</div></div>
            </div>`);
        const token = pageToken;
        call('GET', Config.API.settings.list).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            settings = list || [];
            draw();
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#settings-groups').html(Utils.html`<div class="cost-panel-error" role="alert"><span>${serverMessage(e, "Couldn't load the settings.")}</span></div>`);
        });
    }

    function when(ts) {
        return ts ? new Date(ts).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
    }

    function sourceBadge(s) {
        if (!s.editable) return '<span class="badge text-bg-light border">Restart required</span>';
        return s.source === 'OVERRIDE'
            ? Utils.html`<span class="badge text-bg-primary" title="Default: ${s.defaultValue}">Changed</span>
                <span class="small text-muted settings-changed">${s.updatedByName || ''}${s.updatedAt ? ' · ' + when(s.updatedAt) : ''}</span>`
            : '<span class="badge text-bg-light border">Default</span>';
    }

    function control(s) {
        const id = 'set-' + s.key.replace(/[^a-z0-9]/gi, '-');
        if (!s.editable) {
            return Utils.html`<code class="settings-readonly">${s.value || '(not set)'}</code>`;
        }
        if (s.type === 'BOOLEAN') {
            return Utils.html`<div class="form-check form-switch m-0">
                <input class="form-check-input" type="checkbox" role="switch" id="${id}" data-key="${s.key}" data-action-change="settings-toggle"
                       ${s.value === 'true' ? 'checked' : ''} aria-label="${s.label}">
            </div>`;
        }
        const step = s.type === 'INT' ? '1' : 'any';
        return Utils.html`<div class="d-flex gap-2 align-items-center">
            <input class="form-control form-control-sm settings-number" type="number" id="${id}" data-key="${s.key}" value="${s.value}"
                   min="${s.min === null ? '' : s.min}" max="${s.max === null ? '' : s.max}" step="${step}" aria-label="${s.label}">
            <button type="button" class="btn btn-sm btn-primary" data-action="settings-save" data-key="${s.key}">Save</button>
        </div>`;
    }

    function row(s) {
        return Utils.html`<div class="settings-row" data-row="${s.key}">
            <div class="settings-text">
                <div class="fw-semibold">${s.label}</div>
                <div class="small text-muted">${s.description}${s.editable ? '' : ' View only: edit .env and restart to change it.'}</div>
                <div class="small text-muted"><code>${s.key}</code></div>
            </div>
            <div class="settings-state">${Utils.raw(sourceBadge(s))}</div>
            <div class="settings-control">${Utils.raw(control(s))}
                ${Utils.raw(s.editable && s.source === 'OVERRIDE' ? Utils.html`<button type="button" class="btn btn-sm btn-link p-0 settings-reset" data-action="settings-reset" data-key="${s.key}">Reset to default</button>` : '')}
                <div class="small settings-status" role="status" aria-live="polite"></div>
            </div>
        </div>`;
    }

    function draw() {
        const groups = [];
        settings.forEach(s => {
            let g = groups.find(x => x.name === s.group);
            if (!g) groups.push(g = { name: s.group, items: [] });
            g.items.push(s);
        });
        $('#settings-groups').html(groups.map(g => Utils.html`
            <section class="card mb-3 settings-group" aria-label="${g.name}">
                <div class="card-body">
                    <h2 class="h6 mb-3">${g.name}</h2>
                    ${Utils.raw(g.items.map(row).join(''))}
                </div>
            </section>`).join(''));
    }

    function find(key) {
        return settings.find(s => s.key === key);
    }

    function redrawRow(updated, status) {
        const i = settings.findIndex(s => s.key === updated.key);
        if (i >= 0) settings[i] = updated;
        const $row = $(`[data-row="${CSS.escape(updated.key)}"]`);
        $row.replaceWith(row(updated));
        if (status) $(`[data-row="${CSS.escape(updated.key)}"] .settings-status`).html(status);
    }

    function save(key, value, previous) {
        return call('PUT', Config.API.settings.item(key), { value: String(value) }).then(updated => {
            const undo = previous === undefined ? '' : Utils.html` <button type="button" class="btn btn-link btn-sm p-0" data-action="settings-undo"
                data-key="${key}" data-value="${previous}">Undo</button>`;
            redrawRow(updated, `<span class="text-success">Saved</span>${undo}`);
            if (undo) {
                setTimeout(() => $(`[data-row="${CSS.escape(key)}"] [data-action="settings-undo"]`).remove(), UNDO_MS);
            }
        }).catch(e => {
            const $status = $(`[data-row="${CSS.escape(key)}"] .settings-status`);
            $status.html(Utils.html`<span class="text-danger">${serverMessage(e, 'Could not save.')}</span>`);
            throw e;
        });
    }

    function toggle(el) {
        const key = el.dataset.key;
        const s = find(key);
        const next = el.checked;
        const apply = () => save(key, next, String(!next)).catch(() => { el.checked = !next; });
        if (RISKY[key] && next) {
            el.checked = !next; // only after the confirmation
            Modals.confirm('Turn on ' + s.label, RISKY[key] + ' Turn it on?', () => { el.checked = next; apply(); },
                { confirmText: 'Turn on', confirmClass: 'btn-warning' });
            return;
        }
        apply();
    }

    function saveNumber(btn) {
        const key = btn.dataset.key;
        const s = find(key);
        const $input = $(`[data-row="${CSS.escape(key)}"] .settings-number`);
        const raw = String($input.val()).trim();
        const n = Number(raw);
        const $status = $(`[data-row="${CSS.escape(key)}"] .settings-status`);
        if (raw === '' || Number.isNaN(n) || (s.type === 'INT' && !Number.isInteger(n))
            || (s.min !== null && n < Number(s.min)) || (s.max !== null && n > Number(s.max))) {
            $status.html(Utils.html`<span class="text-danger">Enter ${s.type === 'INT' ? 'a whole number' : 'a number'} from ${s.min} to ${s.max}.</span>`);
            $input.attr('aria-invalid', 'true').trigger('focus');
            return;
        }
        save(key, raw, s.value).catch(() => {});
    }

    function reset(el) {
        const key = el.dataset.key;
        call('DELETE', Config.API.settings.item(key))
            .then(updated => redrawRow(updated, '<span class="text-success">Back to the default</span>'))
            .catch(e => Notifications.showError(serverMessage(e, 'Could not reset the setting.')));
    }

    function undo(el) {
        save(el.dataset.key, el.dataset.value).catch(() => {});
    }

    return { load, toggle, saveNumber, reset, undo };
})();

window.Settings = Settings;

Actions.registerAll({
    'settings-save': el => Settings.saveNumber(el),
    'settings-reset': el => Settings.reset(el),
    'settings-undo': el => Settings.undo(el)
});
Actions.register('settings-toggle', el => Settings.toggle(el), 'change');
