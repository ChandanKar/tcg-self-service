/**
 * VM Self-Service Platform - Registry dialogs (U11-T05, RegistryDialogs.dc.html)
 *
 *   RegistryDialogs.editGroup(env, group|null, groups, onDone)  edit a group, or add one when group is null
 *   RegistryDialogs.moveVm(env, vm, groups, onDone)            move a VM to another group of the environment
 *   RegistryDialogs.notifyStop(env)                            "Tell everyone it's being stopped"
 *
 * A group cannot wait for a group that (directly or through others) waits for it: such a choice is
 * disabled with "would make a loop" instead of failing on save.
 */
const RegistryDialogs = (function() {
    'use strict';

    const GROUP_ID = 'registryGroupModal';
    const MOVE_ID = 'registryMoveModal';
    const NOTIFY_ID = 'registryNotifyModal';
    const DISCOVERY = 'discovered';

    // ------------------------------------------------------------------ model

    function ordinal(n) {
        const mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
        return `${n}${{ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th'}`;
    }

    function people(n) {
        return `${n} ${n === 1 ? 'person' : 'people'}`;
    }

    /** The groups in start order, without Auto-Discovered (it always comes last). */
    function ordered(groups) {
        return (groups || []).filter(g => g.name !== DISCOVERY)
            .slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
    }

    /** groupId -> the groups it waits for. */
    function dependsOnMap(groups) {
        const map = {};
        (groups || []).forEach(g => { map[g.groupId] = (g.dependsOnGroupIds || g.dependencies || []).slice(); });
        return map;
    }

    /** Whether letting {@code groupId} wait for {@code candidateId} closes a loop. */
    function wouldMakeLoop(groupId, candidateId, dependsOn) {
        if (!groupId || groupId === candidateId) return !!groupId;
        const seen = new Set();
        const stack = [candidateId];
        while (stack.length) {
            const id = stack.pop();
            if (id === groupId) return true;
            if (seen.has(id)) continue;
            seen.add(id);
            (dependsOn[id] || []).forEach(next => stack.push(next));
        }
        return false;
    }

    /**
     * The start order after placing {@code groupId} at {@code place} (1-based), and the first
     * group that would start before one it waits for, as "Web waits for Application, so it cannot
     * start first" (the server's wording), or null.
     */
    function planOrder(groups, groupId, place, waitsFor) {
        const ids = ordered(groups).map(g => g.groupId).filter(id => id !== groupId);
        ids.splice(Math.max(0, Math.min(place - 1, ids.length)), 0, groupId);
        const byId = {};
        (groups || []).forEach(g => { byId[g.groupId] = g; });
        const deps = dependsOnMap(groups);
        deps[groupId] = waitsFor.slice();
        const at = {};
        ids.forEach((id, i) => { at[id] = i; });
        for (const id of ids) {
            for (const dep of deps[id] || []) {
                if (at[dep] !== undefined && at[dep] > at[id]) {
                    const name = id === groupId && !byId[id] ? 'This group' : label(byId[id]);
                    return { ids, problem: `${name} waits for ${label(byId[dep])}, so it cannot start first` };
                }
            }
        }
        return { ids, problem: null };
    }

    function label(group) {
        return group ? (group.displayName || group.name) : 'the group';
    }

    function errorText(xhr, fallback) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    // ------------------------------------------------------------------ Edit group

    function editGroup(env, group, groups, onDone) {
        const list = ordered(groups);
        const isNew = !group;
        const count = isNew ? list.length + 1 : list.length;
        const place = isNew ? count : Math.max(1, list.findIndex(g => g.groupId === group.groupId) + 1);
        const deps = dependsOnMap(groups);
        const waits = isNew ? [] : (group.dependsOnGroupIds || []);
        const others = list.filter(g => isNew || g.groupId !== group.groupId);
        const places = Array.from({ length: count }, (_, i) => Utils.html`<option value="${i + 1}"${Utils.raw(i + 1 === place ? ' selected' : '')}>${ordinal(i + 1)}</option>`).join('');
        const boxes = others.length ? others.map(g => {
            const loop = !isNew && wouldMakeLoop(group.groupId, g.groupId, deps);
            return Utils.html`<label class="rd-check${Utils.raw(loop ? ' rd-disabled' : '')}">
                    <input type="checkbox" name="rd-waits" value="${g.groupId}"${Utils.raw(waits.includes(g.groupId) && !loop ? ' checked' : '')}${Utils.raw(loop ? ' disabled' : '')}>
                    ${label(g)}${Utils.raw(loop ? ' <span class="rd-loop">· would make a loop</span>' : '')}</label>`;
        }).join('') : '<p class="rd-none">No other groups yet.</p>';

        Modals.show({
            id: GROUP_ID,
            title: isNew ? `Add a group to ${env.environmentName || env.displayName || env.name || 'this environment'}` : `Edit group: ${label(group)}`,
            body: Utils.html`
                <form id="rd-group-form" class="acd-form rd-form" novalidate>
                    <label class="acd-field" for="rd-group-name">Name</label>
                    <input type="text" class="form-control" id="rd-group-name" maxlength="100" required value="${isNew ? '' : label(group)}">
                    <label class="acd-field" for="rd-group-place">Start order</label>
                    <select class="form-select" id="rd-group-place">${Utils.raw(places)}</select>
                    <fieldset class="rd-waits"><legend>Waits for these groups to be running</legend>${Utils.raw(boxes)}</fieldset>
                    <label class="acd-field" for="rd-group-description">Description</label>
                    <input type="text" class="form-control" id="rd-group-description" maxlength="500" value="${isNew ? '' : (group.description || '')}">
                    <div class="acd-error" id="rd-group-error" role="alert"></div>
                </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-outline-secondary', dismiss: true },
                { text: isNew ? 'Add group' : 'Save group', class: 'btn-primary', id: 'rd-group-save' }
            ],
            onShow: () => $('#rd-group-name').trigger('focus')
        });
        const save = () => saveGroup(env, group, groups, place, onDone);
        $('#rd-group-save').on('click', save);
        $('#rd-group-form').on('submit', e => { e.preventDefault(); save(); });
    }

    async function saveGroup(env, group, groups, oldPlace, onDone) {
        const isNew = !group;
        const name = ($('#rd-group-name').val() || '').trim();
        const place = Number($('#rd-group-place').val()) || 1;
        const waitsFor = $('input[name="rd-waits"]:checked').map(function() { return this.value; }).get();
        const $error = $('#rd-group-error').text('');
        if (name.length < 2) {
            $error.text('Give the group a name of at least 2 characters.');
            return;
        }
        const plan = planOrder(groups, isNew ? '__new' : group.groupId, place, waitsFor);
        if (plan.problem) {
            $error.text(plan.problem);
            return;
        }
        const envId = env.environmentId;
        const $save = $('#rd-group-save').prop('disabled', true).text('Saving…');
        try {
            let groupId = group && group.groupId;
            if (isNew) {
                const last = ordered(groups).reduce((max, g) => Math.max(max, g.sequencePosition || 0), 0);
                const created = await ApiClient.post(Config.API.groups.create(envId), { name, displayName: name,
                    description: ($('#rd-group-description').val() || '').trim() || null, sequencePosition: last + 1,
                    dependsOnGroupIds: waitsFor }, { suppressGlobalError: true });
                groupId = created.groupId;
            } else {
                await ApiClient.put(Config.API.groups.update(envId, groupId), { name: group.name, displayName: name,
                    description: ($('#rd-group-description').val() || '').trim(), sequencePosition: group.sequencePosition,
                    dependsOnGroupIds: waitsFor, metadata: null }, { suppressGlobalError: true });
            }
            const moved = isNew ? place < plan.ids.length : place !== oldPlace;
            if (moved) {
                await ApiClient.put(Config.API.groups.reorder(envId),
                    { groupIds: plan.ids.map(id => id === '__new' ? groupId : id) }, { suppressGlobalError: true });
            }
            Modals.hideWhenReady(document.getElementById(GROUP_ID));
            Notifications.success(isNew ? `Group ${name} added.` : 'Group saved.');
            if (onDone) onDone();
        } catch (xhr) {
            $error.text(errorText(xhr, "The group wasn't saved. Try again."));
            $save.prop('disabled', false).text(isNew ? 'Add group' : 'Save group');
        }
    }

    // ------------------------------------------------------------------ Move VM

    function moveVm(env, vm, groups, onDone) {
        const list = ordered(groups);
        const targets = list.map((g, i) => ({ g, i })).filter(x => x.g.groupId !== vm.groupId);
        if (!targets.length) {
            Notifications.error('There is no other group to move this VM to. Add a group first.');
            return;
        }
        const from = (groups || []).find(g => g.groupId === vm.groupId);
        const name = vm.displayName || vm.name;
        Modals.show({
            id: MOVE_ID,
            title: `Move ${name}`,
            body: Utils.html`
                <p class="acd-sub">Now in ${label(from)}, start order ${vm.sequencePosition || 1}</p>
                <form id="rd-move-form" class="acd-form" novalidate>
                    <label class="acd-field" for="rd-move-target">Move to</label>
                    <select class="form-select" id="rd-move-target">${Utils.raw(targets.map(({ g, i }) =>
                        Utils.html`<option value="${g.groupId}">${label(g)} · starts ${ordinal(i + 1)}</option>`).join(''))}</select>
                    <p class="rd-note">It goes to the end of that group's order. Any VM it was set to wait for is cleared.</p>
                    <div class="acd-error" id="rd-move-error" role="alert"></div>
                </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Move VM', class: 'btn-primary', id: 'rd-move-save' }
            ],
            onShow: () => $('#rd-move-target').trigger('focus')
        });
        const save = async () => {
            const targetGroupId = $('#rd-move-target').val();
            const target = (groups || []).find(g => g.groupId === targetGroupId);
            $('#rd-move-error').text('');
            const $save = $('#rd-move-save').prop('disabled', true).text('Moving…');
            try {
                await ApiClient.put(Config.API.vms.move(env.environmentId, vm.vmId), { targetGroupId }, { suppressGlobalError: true });
                Modals.hideWhenReady(document.getElementById(MOVE_ID));
                Notifications.success(`Moved ${name} to ${label(target)}.`);
                if (onDone) onDone();
            } catch (xhr) {
                $('#rd-move-error').text(errorText(xhr, "The VM wasn't moved. Try again."));
                $save.prop('disabled', false).text('Move VM');
            }
        };
        $('#rd-move-save').on('click', save);
        $('#rd-move-form').on('submit', e => { e.preventDefault(); save(); });
    }

    // ------------------------------------------------------------------ Tell everyone it's being stopped

    function notifyStop(env) {
        const envId = env.environmentId;
        const envName = env.displayName || env.environmentName || env.name;
        Modals.show({
            id: NOTIFY_ID,
            title: "Tell everyone it's being stopped",
            body: Utils.html`
                <p class="acd-sub" id="rd-notify-audience">Counting the people who can use ${envName}…</p>
                <form id="rd-notify-form" class="acd-form" novalidate>
                    <label class="acd-field" for="rd-notify-reason">Reason <span class="acd-hint">optional, included in the message</span></label>
                    <textarea class="form-control" id="rd-notify-reason" rows="4" maxlength="500"></textarea>
                    <p class="rd-note">Each of them gets an email and a notification in the app. Sent even when other email notices are switched off.</p>
                    <div class="acd-error" id="rd-notify-error" role="alert"></div>
                </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-outline-secondary', dismiss: true },
                { text: 'Send', class: 'btn-primary', id: 'rd-notify-send', disabled: true }
            ],
            onShow: () => $('#rd-notify-reason').trigger('focus')
        });
        let audience = 0;
        ApiClient.get(Config.API.environments.notifyStopAudience(envId), { suppressGlobalError: true })
            .done(r => {
                audience = (r && r.count) || 0;
                if (audience) {
                    $('#rd-notify-audience').text(`${people(audience)} can use ${envName}`);
                    $('#rd-notify-send').prop('disabled', false).text(`Send to ${people(audience)}`);
                } else {
                    $('#rd-notify-audience').text('No one with access has an email address.');
                }
            })
            .fail(() => {
                $('#rd-notify-audience').text(`The people who can use ${envName}`);
                $('#rd-notify-send').prop('disabled', false).text('Send');
            });
        const send = () => {
            const reason = ($('#rd-notify-reason').val() || '').trim();
            $('#rd-notify-error').text('');
            const $send = $('#rd-notify-send').prop('disabled', true).text('Sending…');
            ApiClient.post(Config.API.environments.notifyStop(envId), reason ? { reason } : {}, { suppressGlobalError: true })
                .done(r => {
                    Modals.hideWhenReady(document.getElementById(NOTIFY_ID));
                    const n = (r && r.emailedCount) || 0;
                    Notifications.success(n ? `Sent to ${people(n)}.` : 'No one with an email address to send it to.');
                })
                .fail(xhr => {
                    $('#rd-notify-error').text(errorText(xhr, "The notice wasn't sent. Try again."));
                    $send.prop('disabled', false).text(audience ? `Send to ${people(audience)}` : 'Send');
                });
        };
        $('#rd-notify-send').on('click', send);
        $('#rd-notify-form').on('submit', e => { e.preventDefault(); send(); });
    }

    return {
        editGroup,
        moveVm,
        notifyStop,
        model: { ordinal, people, ordered, dependsOnMap, wouldMakeLoop, planOrder }
    };
})();

if (typeof window !== 'undefined') window.RegistryDialogs = RegistryDialogs;
