/**
 * Change access and Remove access (U10-T05, AccessChange.dc.html), opened from a grant's row on
 * Access management.
 *
 *   AccessChangeDialogs.openChange(grant, onDone)  "Change access for <name>": the level as three
 *       cards, Ends (keep the current end, 30 or 90 days from today, no end date), an optional note
 *       kept in the audit log; PATCH /access-grants/{id} sends only what changed. One's own grant
 *       cannot be changed (the server refuses it too).
 *   AccessChangeDialogs.openRemove(grant, onDone)  "Remove <first name>'s access?" with what
 *       happens; DELETE /access-grants/{id}.
 */
const AccessChangeDialogs = (function() {
    'use strict';

    const CHANGE_ID = 'accessChangeModal';
    const REMOVE_ID = 'accessRemoveModal';

    // ---- pure model (node tests: src/test/js/access-change.test.mjs) ----

    function nameOf(grant) {
        return grant.userDisplayName || grant.userEmail || 'This person';
    }

    function firstName(grant) {
        const name = grant.userDisplayName || '';
        return name.trim() ? name.trim().split(/\s+/)[0] : nameOf(grant);
    }

    /** "Payments Dev" or "Payments Dev, Application group". */
    function scopeText(grant) {
        const env = grant.environmentName || grant.environmentId || '';
        return grant.scopeType === 'GROUP' ? `${env}, ${grant.scopeName || grant.scopeId} group` : env;
    }

    /** "Payments Dev · Operator · ends 31 Oct" / "… · no end date". */
    function subtitle(grant, now) {
        const ends = grant.expiresAt ? `ends ${AccessLevels.endsText(grant.expiresAt, now).text.replace(/ · .*$/, '')}`
            : 'no end date';
        return `${scopeText(grant)} · ${AccessLevels.label(grant.accessLevel)} · ${ends}`;
    }

    /** The Ends choices: keep the current end (sends nothing), 30 / 90 days from today, no end date. */
    function endsOptions(grant, now) {
        const keep = grant.expiresAt
            ? `Keep ${AccessLevels.endsText(grant.expiresAt, now).text.replace(/ · .*$/, '')}` : 'Keep no end date';
        const options = [{ value: 'keep', label: keep }, { value: '30', label: '30 days from today' },
            { value: '90', label: '90 days from today' }];
        if (grant.expiresAt) options.push({ value: 'none', label: 'No end date' });
        return options;
    }

    /**
     * The PATCH body: the level always, the end only when it changes (days, or clearExpiry for no
     * end), the note only when it changed. Null when nothing changes.
     */
    function patchBody(grant, level, ends, note) {
        const body = { accessLevel: level };
        if (ends === '30' || ends === '90') body.durationDays = Number(ends);
        if (ends === 'none') body.clearExpiry = true;
        const trimmed = (note || '').trim();
        if (trimmed !== (grant.notes || '').trim()) body.notes = trimmed;
        const changed = level !== grant.accessLevel || body.durationDays || body.clearExpiry || body.notes !== undefined;
        return changed ? body : null;
    }

    // ---- dialogs ----

    function errorText(xhr, fallback) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    /** What each level adds, in the words of the AccessChange board (U17-T10). */
    const LEVEL_TEXT = {
        VIEWER: 'Sees the environment, its VMs, IPs and cost',
        USER: 'Also starts and stops VMs, takes locks',
        ADMIN: 'Also gives access, edits VMs, schedules and budgets'
    };

    function openChange(grant, onDone, options = {}) {
        const mine = !!options.mine;
        const levels = AccessLevels.LEVELS.map(level => Utils.html`
            <label class="acd-level">
                <input type="radio" name="acd-level" value="${level}" ${level === grant.accessLevel ? 'checked' : ''} ${mine ? 'disabled' : ''}>
                <span><b>${AccessLevels.label(level)}</b><span class="acd-level-desc">${LEVEL_TEXT[level] || AccessLevels.description(level)}</span></span>
            </label>`).join('');
        const ends = endsOptions(grant).map(o => Utils.html`<option value="${o.value}">${o.label}</option>`).join('');
        Modals.show({
            id: CHANGE_ID,
            title: `Change access for ${nameOf(grant)}`,
            body: Utils.html`
                <p class="acd-sub">${subtitle(grant)}</p>
                <form id="acd-form" class="acd-form" novalidate>
                    <fieldset class="acd-levels"><legend>Access</legend>${Utils.raw(levels)}</fieldset>
                    <label class="acd-field" for="acd-ends">Ends</label>
                    <select class="form-select" id="acd-ends" ${mine ? 'disabled' : ''}>${Utils.raw(ends)}</select>
                    <label class="acd-field" for="acd-note">Note <span class="acd-hint">optional, kept in the audit log</span></label>
                    <input type="text" class="form-control" id="acd-note" maxlength="500" value="${grant.notes || ''}" ${mine ? 'disabled' : ''}>
                    <p class="acd-self${mine ? ' acd-self-mine' : ''}" role="note">You can't change your own access; another admin has to.</p>
                    <div class="acd-error" id="acd-error" role="alert"></div>
                </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Save access', class: 'btn-primary', id: 'acd-save', disabled: mine }
            ]
        });
        $('#acd-save').on('click', () => saveChange(grant, onDone));
        $('#acd-form').on('submit', e => { e.preventDefault(); saveChange(grant, onDone); });
    }

    function saveChange(grant, onDone) {
        const level = $('input[name="acd-level"]:checked').val() || grant.accessLevel;
        const body = patchBody(grant, level, $('#acd-ends').val(), $('#acd-note').val());
        const modal = document.getElementById(CHANGE_ID);
        if (!body) {
            Modals.hideWhenReady(modal);
            return;
        }
        $('#acd-error').text('');
        $('#acd-save').prop('disabled', true).text('Saving…');
        ApiClient.patch(Config.API.access.updateGrant(grant.accessId), body, { suppressGlobalError: true })
            .done(() => {
                Modals.hideWhenReady(modal);
                Notifications.success('Access saved.');
                if (onDone) onDone();
            })
            .fail(xhr => {
                $('#acd-error').text(errorText(xhr, "The change wasn't saved. Try again."));
                $('#acd-save').prop('disabled', false).text('Save access');
            });
    }

    function openRemove(grant, onDone) {
        Modals.show({
            id: REMOVE_ID,
            title: `Remove ${firstName(grant)}'s access?`,
            body: Utils.html`<p class="acd-remove-text">${nameOf(grant)} can no longer see or use <b>${scopeText(grant)}</b>.
                The change is recorded in the audit log. To give access back, grant it again.</p>
                <div class="acd-error" id="acd-remove-error" role="alert"></div>`,
            buttons: [
                { text: 'Keep access', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Remove access', class: 'btn-danger', id: 'acd-remove' }
            ]
        });
        const modal = document.getElementById(REMOVE_ID);
        modal.setAttribute('role', 'alertdialog');
        $('#acd-remove').on('click', function() {
            $(this).prop('disabled', true).text('Removing…');
            ApiClient.delete(Config.API.access.revokeGrant(grant.accessId), { suppressGlobalError: true })
                .done(() => {
                    Modals.hideWhenReady(modal);
                    Notifications.success('Access removed.');
                    if (onDone) onDone();
                })
                .fail(xhr => {
                    $('#acd-remove-error').text(errorText(xhr, "The access wasn't removed. Try again."));
                    $(this).prop('disabled', false).text('Remove access');
                });
        });
    }

    return { openChange, openRemove, model: { nameOf, firstName, scopeText, subtitle, endsOptions, patchBody } };
})();

if (typeof window !== 'undefined') window.AccessChangeDialogs = AccessChangeDialogs;
