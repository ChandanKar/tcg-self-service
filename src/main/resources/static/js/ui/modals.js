/**
 * VM Self-Service Platform - Modal Dialogs
 * Reusable modal components for forms and confirmations
 */

const Modals = (function() {
    'use strict';

    /**
     * Show a modal dialog
     * @param {object} options - Modal options
     * @param {string} options.id - Modal ID
     * @param {string} options.title - Modal title
     * @param {string} options.body - Modal body HTML
     * @param {string} options.size - Modal size (sm, lg, xl)
     * @param {array} options.buttons - Array of button configs
     * @param {function} options.onShow - Callback when modal is shown
     * @param {function} options.onHide - Callback when modal is hidden
     */
    function show(options) {
        const {
            id = 'dynamicModal',
            title = 'Modal',
            body = '',
            size = '',
            buttons = [{ text: 'Close', class: 'btn-secondary', dismiss: true }],
            onShow = null,
            onHide = null
        } = options;

        // Remove existing modal if any
        $(`#${id}`).remove();

        // Build buttons HTML
        const buttonsHtml = buttons.map(btn => {
            const dismissAttr = btn.dismiss ? 'data-bs-dismiss="modal"' : '';
            const idAttr = btn.id ? `id="${btn.id}"` : '';
            const disabledAttr = btn.disabled ? 'disabled' : '';
            return `<button type="button" class="btn ${btn.class || 'btn-secondary'}" ${dismissAttr} ${idAttr} ${disabledAttr}>${btn.text}</button>`;
        }).join('');

        // Build modal HTML
        const sizeClass = size ? `modal-${size}` : '';
        const modalHtml = `
            <div class="modal fade" id="${id}" tabindex="-1" aria-labelledby="${id}Label" aria-hidden="true">
                <div class="modal-dialog ${sizeClass}">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="${id}Label">${title}</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            ${body}
                        </div>
                        <div class="modal-footer">
                            ${buttonsHtml}
                        </div>
                    </div>
                </div>
            </div>
        `;

        // Add to body
        $('body').append(modalHtml);

        // Get modal instance
        const modalEl = document.getElementById(id);
        const modal = new bootstrap.Modal(modalEl);

        // Bind callbacks
        if (onShow) {
            modalEl.addEventListener('shown.bs.modal', onShow);
        }
        if (onHide) {
            modalEl.addEventListener('hidden.bs.modal', onHide);
        }

        // Clean up on hide
        modalEl.addEventListener('hidden.bs.modal', function() {
            $(this).remove();
        });

        // Show modal
        modal.show();

        return modal;
    }

    /**
     * Show confirmation dialog
     * @param {string} title - Dialog title
     * @param {string} message - Confirmation message
     * @param {function} onConfirm - Callback when confirmed
     * @param {object} options - Additional options
     */
    function confirm(title, message, onConfirm, options = {}) {
        const {
            confirmText = 'Confirm',
            confirmClass = 'btn-primary',
            cancelText = 'Cancel'
        } = options;

        show({
            id: 'confirmModal',
            title: title,
            body: `<p>${message}</p>`,
            buttons: [
                { text: cancelText, class: 'btn-secondary', dismiss: true },
                { text: confirmText, class: confirmClass, id: 'confirmBtn' }
            ],
            onShow: function() {
                $('#confirmBtn').off('click').on('click', function() {
                    bootstrap.Modal.getInstance(document.getElementById('confirmModal')).hide();
                    if (onConfirm) onConfirm();
                });
            }
        });
    }

    /**
     * Show prompt dialog
     * @param {string} title - Dialog title
     * @param {string} label - Input label
     * @param {function} onSubmit - Callback with input value
     * @param {object} options - Additional options
     */
    function prompt(title, label, onSubmit, options = {}) {
        const {
            placeholder = '',
            defaultValue = '',
            required = false,
            inputType = 'text',
            submitText = 'Submit'
        } = options;

        const requiredAttr = required ? 'required' : '';

        show({
            id: 'promptModal',
            title: title,
            body: `
                <form id="promptForm">
                    <div class="mb-3">
                        <label class="form-label">${label}</label>
                        <input type="${inputType}" class="form-control" id="promptInput"
                               placeholder="${placeholder}" value="${defaultValue}" ${requiredAttr}>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: submitText, class: 'btn-primary', id: 'promptSubmitBtn' }
            ],
            onShow: function() {
                $('#promptInput').focus();

                $('#promptSubmitBtn').off('click').on('click', function() {
                    const value = $('#promptInput').val();
                    if (required && !value.trim()) {
                        $('#promptInput').addClass('is-invalid');
                        return;
                    }
                    bootstrap.Modal.getInstance(document.getElementById('promptModal')).hide();
                    if (onSubmit) onSubmit(value);
                });

                $('#promptForm').off('submit').on('submit', function(e) {
                    e.preventDefault();
                    $('#promptSubmitBtn').click();
                });
            }
        });
    }

    /**
     * Hide a modal by ID
     */
    function hide(id) {
        const modalEl = document.getElementById(id);
        if (modalEl) {
            const modal = bootstrap.Modal.getInstance(modalEl);
            if (modal) {
                modal.hide();
            }
        }
    }

    /**
     * Create Environment Modal
     */
    function showCreateEnvironment(onSuccess) {
        show({
            id: 'createEnvModal',
            title: 'Create New Environment',
            size: 'lg',
            body: `
                <form id="createEnvForm">
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Environment Name <span class="text-danger">*</span></label>
                            <input type="text" class="form-control" id="envName" required
                                   pattern="[a-z0-9-]+" placeholder="my-environment">
                            <div class="form-text">Lowercase letters, numbers, and hyphens only</div>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Display Name</label>
                            <input type="text" class="form-control" id="envDisplayName"
                                   placeholder="My Environment">
                        </div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Description</label>
                        <textarea class="form-control" id="envDescription" rows="2"
                                  placeholder="Brief description of this environment"></textarea>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Owner Team</label>
                            <input type="text" class="form-control" id="envOwnerTeam"
                                   placeholder="e.g., Platform Team">
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Default Cloud Provider</label>
                            <select class="form-select" id="envCloudProvider">
                                <option value="AWS">AWS</option>
                                <option value="AZURE">Azure</option>
                                <option value="GCP">Google Cloud</option>
                                <option value="OCI">Oracle Cloud</option>
                            </select>
                        </div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Create Environment', class: 'btn-primary', id: 'createEnvBtn' }
            ],
            onShow: function() {
                $('#envName').focus();

                $('#createEnvBtn').off('click').on('click', function() {
                    if (!$('#createEnvForm')[0].checkValidity()) {
                        $('#createEnvForm')[0].reportValidity();
                        return;
                    }

                    const envName = $('#envName').val().trim();
                    const data = {
                        name: envName,
                        displayName: $('#envDisplayName').val().trim() || envName,
                        description: $('#envDescription').val().trim() || null,
                        metadata: JSON.stringify({
                            ownerTeam: $('#envOwnerTeam').val().trim() || null,
                            defaultCloudProvider: $('#envCloudProvider').val() || null
                        })
                    };

                    // Disable button and show loading
                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Creating...');

                    ApiClient.post(Config.API.environments.create, data)
                        .done(function(env) {
                            hide('createEnvModal');
                            Notifications.success(`Environment "${env.name}" created successfully`);
                            if (onSuccess) onSuccess(env);
                        })
                        .fail(function(xhr) {
                            $('#createEnvBtn').prop('disabled', false).html('Create Environment');
                            const msg = xhr.responseJSON?.message || 'Failed to create environment';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    /**
     * Edit Environment Modal
     */
    function showEditEnvironment(env, onSuccess) {
        // Parse existing metadata
        let meta = {};
        try { meta = JSON.parse(env.metadata || '{}') || {}; } catch(e) {}

        show({
            id: 'editEnvModal',
            title: `Edit Environment: ${env.name}`,
            size: 'lg',
            body: `
                <form id="editEnvForm">
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Environment Name</label>
                            <input type="text" class="form-control" value="${Utils.escapeHtml(env.name)}" disabled>
                            <div class="form-text">Name cannot be changed</div>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Display Name</label>
                            <input type="text" class="form-control" id="editEnvDisplayName"
                                   value="${Utils.escapeHtml(env.displayName || '')}">
                        </div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Description</label>
                        <textarea class="form-control" id="editEnvDescription" rows="2">${Utils.escapeHtml(env.description || '')}</textarea>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Owner Team</label>
                            <input type="text" class="form-control" id="editEnvOwnerTeam"
                                   value="${Utils.escapeHtml(meta.ownerTeam || '')}">
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Default Cloud Provider</label>
                            <select class="form-select" id="editEnvCloudProvider">
                                <option value="AWS" ${meta.defaultCloudProvider === 'AWS' ? 'selected' : ''}>AWS</option>
                                <option value="AZURE" ${meta.defaultCloudProvider === 'AZURE' ? 'selected' : ''}>Azure</option>
                                <option value="GCP" ${meta.defaultCloudProvider === 'GCP' ? 'selected' : ''}>Google Cloud</option>
                                <option value="OCI" ${meta.defaultCloudProvider === 'OCI' ? 'selected' : ''}>Oracle Cloud</option>
                            </select>
                        </div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Save Changes', class: 'btn-primary', id: 'saveEnvBtn' }
            ],
            onShow: function() {
                $('#saveEnvBtn').off('click').on('click', function() {
                    const data = {
                        displayName: $('#editEnvDisplayName').val().trim() || env.displayName,
                        description: $('#editEnvDescription').val().trim() || null,
                        metadata: JSON.stringify({
                            ownerTeam: $('#editEnvOwnerTeam').val().trim() || null,
                            defaultCloudProvider: $('#editEnvCloudProvider').val() || null
                        })
                    };

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Saving...');

                    ApiClient.put(Config.API.environments.update(env.environmentId), data)
                        .done(function(updated) {
                            hide('editEnvModal');
                            Notifications.success('Environment updated successfully');
                            if (onSuccess) onSuccess(updated);
                        })
                        .fail(function(xhr) {
                            $('#saveEnvBtn').prop('disabled', false).html('Save Changes');
                            const msg = xhr.responseJSON?.message || 'Failed to update environment';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    /**
     * Create VM Group Modal
     */
    function showCreateGroup(envId, existingGroups, onSuccess) {
        const groupOptions = existingGroups.map(g =>
            `<option value="${g.group?.groupId || g.groupId}">${Utils.escapeHtml(g.group?.name || g.name)}</option>`
        ).join('');

        show({
            id: 'createGroupModal',
            title: 'Create VM Group',
            body: `
                <form id="createGroupForm">
                    <div class="mb-3">
                        <label class="form-label">Group Name <span class="text-danger">*</span></label>
                        <input type="text" class="form-control" id="groupName" required
                               pattern="[a-z0-9-]+" placeholder="e.g., database-tier">
                        <div class="form-text">Lowercase letters, numbers, and hyphens only</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Display Name</label>
                        <input type="text" class="form-control" id="groupDisplayName"
                               placeholder="e.g., Database Tier">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Description</label>
                        <textarea class="form-control" id="groupDescription" rows="2"></textarea>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Sequence Position <span class="text-danger">*</span></label>
                            <input type="number" class="form-control" id="groupSequence"
                                   min="1" value="${existingGroups.length + 1}" required>
                            <div class="form-text">Order in which to start/stop</div>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Depends On Groups</label>
                            <select class="form-select" id="groupDependsOn" multiple size="3">
                                ${groupOptions}
                            </select>
                            <div class="form-text">Hold Ctrl to select multiple</div>
                        </div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Create Group', class: 'btn-primary', id: 'createGroupBtn' }
            ],
            onShow: function() {
                $('#groupName').focus();

                $('#createGroupBtn').off('click').on('click', function() {
                    if (!$('#createGroupForm')[0].checkValidity()) {
                        $('#createGroupForm')[0].reportValidity();
                        return;
                    }

                    const dependsOnIds = $('#groupDependsOn').val() || [];

                    const data = {
                        name: $('#groupName').val().trim(),
                        displayName: $('#groupDisplayName').val().trim() || null,
                        description: $('#groupDescription').val().trim() || null,
                        sequencePosition: parseInt($('#groupSequence').val()),
                        dependsOnGroupIds: dependsOnIds
                    };

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Creating...');

                    ApiClient.post(Config.API.groups.create(envId), data)
                        .done(function(group) {
                            hide('createGroupModal');
                            Notifications.success(`Group "${group.name}" created`);
                            if (onSuccess) onSuccess(group);
                        })
                        .fail(function(xhr) {
                            $('#createGroupBtn').prop('disabled', false).html('Create Group');
                            const msg = xhr.responseJSON?.message || 'Failed to create group';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    /**
     * Register VM Modal
     */
    function showRegisterVm(envId, groups, onSuccess) {
        const groupOptions = groups.map(g =>
            `<option value="${g.group?.groupId || g.groupId}">${Utils.escapeHtml(g.group?.name || g.name)}</option>`
        ).join('');

        show({
            id: 'registerVmModal',
            title: 'Register VM',
            size: 'lg',
            body: `
                <form id="registerVmForm">
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">VM Name <span class="text-danger">*</span></label>
                            <input type="text" class="form-control" id="vmName" required
                                   placeholder="e.g., web-server-01">
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Display Name</label>
                            <input type="text" class="form-control" id="vmDisplayName"
                                   placeholder="e.g., Web Server 01">
                        </div>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Group <span class="text-danger">*</span></label>
                            <select class="form-select" id="vmGroupId" required>
                                <option value="">Select a group...</option>
                                ${groupOptions}
                            </select>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Cloud Provider <span class="text-danger">*</span></label>
                            <select class="form-select" id="vmProvider" required>
                                <option value="AWS">AWS</option>
                                <option value="AZURE">Azure</option>
                                <option value="GCP">Google Cloud</option>
                                <option value="OCI">Oracle Cloud</option>
                            </select>
                        </div>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Provider VM ID <span class="text-danger">*</span></label>
                            <input type="text" class="form-control" id="vmProviderId" required
                                   placeholder="e.g., i-0123456789abcdef0">
                            <div class="form-text">The instance ID from your cloud provider</div>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Region <span class="text-danger">*</span></label>
                            <input type="text" class="form-control" id="vmRegion" required
                                   placeholder="e.g., us-east-1">
                        </div>
                    </div>
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">VM Type</label>
                            <input type="text" class="form-control" id="vmType"
                                   placeholder="e.g., t3.medium">
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Sequence Position</label>
                            <input type="number" class="form-control" id="vmSequence" min="1" value="1">
                        </div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Register VM', class: 'btn-primary', id: 'registerVmBtn' }
            ],
            onShow: function() {
                $('#vmName').focus();

                $('#registerVmBtn').off('click').on('click', function() {
                    if (!$('#registerVmForm')[0].checkValidity()) {
                        $('#registerVmForm')[0].reportValidity();
                        return;
                    }

                    const data = {
                        name: $('#vmName').val().trim(),
                        displayName: $('#vmDisplayName').val().trim() || null,
                        groupId: $('#vmGroupId').val(),
                        provider: $('#vmProvider').val(),
                        providerVmId: $('#vmProviderId').val().trim(),
                        region: $('#vmRegion').val().trim(),
                        vmType: $('#vmType').val().trim() || null,
                        sequencePosition: parseInt($('#vmSequence').val()) || 1
                    };

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Registering...');

                    ApiClient.post(Config.API.vms.register(envId), data)
                        .done(function(vm) {
                            hide('registerVmModal');
                            Notifications.success(`VM "${vm.name}" registered`);
                            if (onSuccess) onSuccess(vm);
                        })
                        .fail(function(xhr) {
                            $('#registerVmBtn').prop('disabled', false).html('Register VM');
                            const msg = xhr.responseJSON?.message || 'Failed to register VM';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    return {
        show,
        hide,
        confirm,
        prompt,
        showCreateEnvironment,
        showEditEnvironment,
        showCreateGroup,
        showRegisterVm
    };
})();

