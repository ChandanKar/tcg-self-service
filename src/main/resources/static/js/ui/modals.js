/**
 * VM Self-Service Platform - Modal Dialogs
 * Reusable modal components for forms and confirmations
 */

const Modals = (function() {
    'use strict';

    /**
     * Trap focus within a modal for accessibility (TASK-007)
     * @param {HTMLElement} modal - The modal element
     * @returns {function} Cleanup function to remove event listener
     */
    function trapFocus(modal) {
        const focusableSelectors = [
            'button:not([disabled])',
            '[href]',
            'input:not([disabled])',
            'select:not([disabled])',
            'textarea:not([disabled])',
            '[tabindex]:not([tabindex="-1"])'
        ].join(', ');

        function handleKeydown(e) {
            const focusableElements = modal.querySelectorAll(focusableSelectors);
            const firstElement = focusableElements[0];
            const lastElement = focusableElements[focusableElements.length - 1];

            if (e.key === 'Tab') {
                if (e.shiftKey && document.activeElement === firstElement) {
                    e.preventDefault();
                    lastElement.focus();
                } else if (!e.shiftKey && document.activeElement === lastElement) {
                    e.preventDefault();
                    firstElement.focus();
                }
            }

            if (e.key === 'Escape') {
                const bsModal = bootstrap.Modal.getInstance(modal);
                if (bsModal) bsModal.hide();
            }
        }

        modal.addEventListener('keydown', handleKeydown);

        // Focus first element when modal is shown
        modal.addEventListener('shown.bs.modal', function() {
            const focusableElements = modal.querySelectorAll(focusableSelectors);
            if (focusableElements.length > 0) {
                focusableElements[0].focus();
            }
        }, { once: true });

        return () => modal.removeEventListener('keydown', handleKeydown);
    }

    /**
     * Initialize focus trapping for all modals on the page
     */
    function initFocusTraps() {
        document.querySelectorAll('.modal').forEach(modal => trapFocus(modal));
    }

    // Initialize focus traps when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initFocusTraps);
    } else {
        initFocusTraps();
    }

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
            // Use both events with once:true — whichever fires first wins
            modalEl.addEventListener('shown.bs.modal', onShow, { once: true });
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
     * Create Environment Modal — EC2/EKS toggle with EKS cluster picker.
     */
    function showCreateEnvironment(onSuccess) {
        show({
            id: 'createEnvModal',
            title: 'Create New Environment',
            size: 'lg',
            body: `
                <form id="createEnvForm" autocomplete="off">
                    <input type="hidden" id="cem-serviceType" value="EC2">

                    <!-- Service type toggle -->
                    <div class="d-flex gap-2 mb-4">
                        <button type="button" class="btn btn-primary flex-fill" id="cem-type-ec2">
                            <i class="fas fa-server me-1"></i> EC2 &mdash; Virtual Machines
                        </button>
                        <button type="button" class="btn btn-outline-secondary flex-fill" id="cem-type-eks">
                            <i class="fas fa-dharmachakra me-1"></i> EKS &mdash; Kubernetes
                        </button>
                    </div>

                    <!-- EC2 section -->
                    <div id="cem-ec2-section">
                        <div class="row">
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Environment Name <span class="text-danger">*</span></label>
                                <input type="text" class="form-control" id="cem-name" required
                                       pattern="[a-z0-9\\-]+" placeholder="my-environment">
                                <div class="form-text">Lowercase letters, numbers, and hyphens only</div>
                            </div>
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Display Name</label>
                                <input type="text" class="form-control" id="cem-displayName" placeholder="My Environment">
                            </div>
                        </div>
                        <div class="mb-3">
                            <label class="form-label">Description</label>
                            <textarea class="form-control" id="cem-description" rows="2"
                                      placeholder="Brief description of this environment"></textarea>
                        </div>
                        <div class="row">
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Default Cloud Provider</label>
                                <select class="form-select" id="cem-cloudProvider">
                                    <option value="AWS">AWS</option>
                                    <option value="AZURE">Azure</option>
                                    <option value="GCP">Google Cloud</option>
                                    <option value="OCI">Oracle Cloud</option>
                                </select>
                            </div>
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Owner Team</label>
                                <input type="text" class="form-control" id="cem-ownerTeam" placeholder="e.g., Platform Team">
                            </div>
                        </div>
                    </div>

                    <!-- EKS section (hidden by default) -->
                    <div id="cem-eks-section" style="display:none;">
                        <input type="hidden" id="cem-eks-region" value="">
                        <div class="mb-3">
                            <label class="form-label">Select EKS Cluster from AWS</label>
                            <div id="cem-eks-picker" class="border rounded p-3 bg-light"
                                 style="min-height:80px; max-height:200px; overflow-y:auto;">
                                <div class="text-center text-muted py-2 small" id="cem-eks-picker-idle">
                                    <i class="fas fa-info-circle me-1"></i>
                                    Switch to EKS above to load available clusters.
                                </div>
                            </div>
                        </div>
                        <div class="row">
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Cluster Name <span class="text-danger">*</span></label>
                                <div class="input-group">
                                    <input type="text" class="form-control" id="cem-eks-name"
                                           placeholder="Select a cluster above or type manually"
                                           pattern="[a-zA-Z0-9\\-_]+" required>
                                    <span class="input-group-text d-none" id="cem-eks-lock-icon"
                                          data-bs-toggle="tooltip" title="Name locked to selected cluster">
                                        <i class="fas fa-lock text-muted small"></i>
                                    </span>
                                </div>
                                <div id="cem-eks-region-info" class="form-text d-none">
                                    <i class="fas fa-map-marker-alt me-1"></i> Region: <strong id="cem-eks-region-val"></strong>
                                </div>
                            </div>
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Display Name</label>
                                <input type="text" class="form-control" id="cem-eks-displayName"
                                       placeholder="Auto-filled from cluster name">
                            </div>
                        </div>
                        <div class="row">
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Owner Team</label>
                                <input type="text" class="form-control" id="cem-eks-ownerTeam"
                                       placeholder="e.g., Platform Team">
                            </div>
                            <div class="col-md-6 mb-3">
                                <label class="form-label">Description</label>
                                <input type="text" class="form-control" id="cem-eks-description"
                                       placeholder="Optional description">
                            </div>
                        </div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Create Environment', class: 'btn-primary', id: 'cem-submitBtn' }
            ],
            onShow: function() {
                let eksPickerLoaded = false;

                // Toggle between EC2 and EKS
                function switchToEc2() {
                    $('#cem-serviceType').val('EC2');
                    $('#cem-type-ec2').removeClass('btn-outline-secondary').addClass('btn-primary');
                    $('#cem-type-eks').removeClass('btn-primary').addClass('btn-outline-secondary');
                    $('#cem-ec2-section').show();
                    $('#cem-eks-section').hide();
                    // Make EC2 name required, EKS name not
                    document.getElementById('cem-name').required = true;
                    document.getElementById('cem-eks-name').required = false;
                }

                function switchToEks() {
                    $('#cem-serviceType').val('EKS');
                    $('#cem-type-eks').removeClass('btn-outline-secondary').addClass('btn-primary');
                    $('#cem-type-ec2').removeClass('btn-primary').addClass('btn-outline-secondary');
                    $('#cem-ec2-section').hide();
                    $('#cem-eks-section').show();
                    document.getElementById('cem-name').required = false;
                    document.getElementById('cem-eks-name').required = true;
                    if (!eksPickerLoaded) {
                        loadEksClusters();
                        eksPickerLoaded = true;
                    }
                }

                function loadEksClusters() {
                    $('#cem-eks-picker').html(`
                        <div class="text-center text-muted py-2 small">
                            <i class="fas fa-spinner fa-spin me-1"></i> Fetching clusters from AWS...
                        </div>
                    `);
                    ApiClient.get(Config.API.environments.discoverEks)
                        .done(function(clusters) {
                            if (!clusters || clusters.length === 0) {
                                $('#cem-eks-picker').html(`
                                    <div class="text-muted small py-1">
                                        <i class="fas fa-check-circle text-success me-1"></i>
                                        All EKS clusters in this region are already registered.
                                        Enter a cluster name manually below.
                                    </div>
                                `);
                                return;
                            }
                            // API now returns [{clusterName, region}, ...]
                            const rows = clusters.map(c => `
                                <div class="cem-eks-cluster-row d-flex align-items-center gap-2 p-2 rounded mb-1"
                                     style="cursor:pointer; background:#fff; border:1px solid #e2e8f0;"
                                     data-cluster="${Utils.escapeHtml(c.clusterName)}"
                                     data-region="${Utils.escapeHtml(c.region)}">
                                    <i class="fas fa-dharmachakra text-primary" style="font-size:0.85rem;"></i>
                                    <span style="font-size:0.875rem; font-weight:500;">${Utils.escapeHtml(c.clusterName)}</span>
                                    <span class="ms-auto text-muted small">${Utils.escapeHtml(c.region)}</span>
                                </div>
                            `).join('');
                            $('#cem-eks-picker').html(rows);

                            // Cluster row click
                            $('#cem-eks-picker').on('click', '.cem-eks-cluster-row', function() {
                                const clusterName = $(this).data('cluster');
                                const region = $(this).data('region');
                                // Highlight selected
                                $('.cem-eks-cluster-row').css({ background: '#fff', borderColor: '#e2e8f0' });
                                $(this).css({ background: '#eff6ff', borderColor: '#3b82f6' });
                                // Fill and lock name + store region
                                $('#cem-eks-name').val(clusterName).prop('readonly', true);
                                $('#cem-eks-region').val(region);
                                $('#cem-eks-lock-icon').removeClass('d-none');
                                // Auto-fill display name if empty
                                if (!$('#cem-eks-displayName').val()) {
                                    $('#cem-eks-displayName').val(clusterName);
                                }
                                $('#cem-eks-region-val').text(region);
                                $('#cem-eks-region-info').removeClass('d-none');
                            });
                        })
                        .fail(function() {
                            $('#cem-eks-picker').html(`
                                <div class="text-warning small py-1">
                                    <i class="fas fa-exclamation-triangle me-1"></i>
                                    Could not reach AWS. Enter cluster name manually below.
                                </div>
                            `);
                        });
                }

                $('#cem-type-ec2').on('click', switchToEc2);
                $('#cem-type-eks').on('click', switchToEks);

                // Start in EC2 mode
                switchToEc2();
                document.getElementById('cem-name').focus();

                document.getElementById('cem-submitBtn').addEventListener('click', function() {
                    const serviceType = document.getElementById('cem-serviceType').value;
                    const isEks = serviceType === 'EKS';

                    const envName     = isEks
                        ? (document.getElementById('cem-eks-name').value || '').trim()
                        : (document.getElementById('cem-name').value || '').trim();
                    const displayName = isEks
                        ? (document.getElementById('cem-eks-displayName').value || '').trim() || envName
                        : (document.getElementById('cem-displayName').value || '').trim() || envName;
                    const description = isEks
                        ? (document.getElementById('cem-eks-description').value || '').trim() || null
                        : (document.getElementById('cem-description').value || '').trim() || null;
                    const ownerTeam   = isEks
                        ? (document.getElementById('cem-eks-ownerTeam').value || '').trim() || null
                        : (document.getElementById('cem-ownerTeam').value || '').trim() || null;
                    const cloudProv   = isEks ? 'AWS' : (document.getElementById('cem-cloudProvider').value || 'AWS');
                    const eksRegion   = isEks ? (document.getElementById('cem-eks-region').value || '') : '';

                    if (!envName) {
                        showModalError('createEnvModal', isEks
                            ? 'Please select a cluster or enter a cluster name.'
                            : 'Environment Name is required.');
                        return;
                    }

                    const metaObj = isEks
                        ? { ownerTeam, defaultCloudProvider: 'AWS', ...(eksRegion && { region: eksRegion }) }
                        : { ownerTeam, defaultCloudProvider: cloudProv };

                    const data = {
                        name: envName,
                        displayName: displayName,
                        description: description,
                        serviceType: serviceType,
                        metadata: JSON.stringify(metaObj)
                    };

                    this.disabled = true;
                    this.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Creating...';

                    ApiClient.post(Config.API.environments.create, data, { suppressGlobalError: true })
                        .done(function(env) {
                            hide('createEnvModal');
                            Notifications.success(`Environment "${env.name}" created successfully`);
                            if (onSuccess) onSuccess(env);
                        })
                        .fail(function(xhr) {
                            const btn = document.getElementById('cem-submitBtn');
                            if (btn) { btn.disabled = false; btn.innerHTML = 'Create Environment'; }
                            const errMsg = parseValidationError(xhr);
                            showModalError('createEnvModal', errMsg);
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
                        <div class="col-md-4 mb-3">
                            <label class="form-label">Owner Team</label>
                            <input type="text" class="form-control" id="editEnvOwnerTeam"
                                   value="${Utils.escapeHtml(meta.ownerTeam || '')}">
                        </div>
                        <div class="col-md-4 mb-3">
                            <label class="form-label">Default Cloud Provider</label>
                            <select class="form-select" id="editEnvCloudProvider">
                                <option value="AWS" ${meta.defaultCloudProvider === 'AWS' ? 'selected' : ''}>AWS</option>
                                <option value="AZURE" ${meta.defaultCloudProvider === 'AZURE' ? 'selected' : ''}>Azure</option>
                                <option value="GCP" ${meta.defaultCloudProvider === 'GCP' ? 'selected' : ''}>Google Cloud</option>
                                <option value="OCI" ${meta.defaultCloudProvider === 'OCI' ? 'selected' : ''}>Oracle Cloud</option>
                            </select>
                        </div>
                        <div class="col-md-4 mb-3">
                            <label class="form-label">Service Type</label>
                            <select class="form-select" id="editEnvServiceType">
                                <option value="EC2" ${(!env.serviceType || env.serviceType === 'EC2') ? 'selected' : ''}>EC2 — Virtual Machines</option>
                                <option value="EKS" ${env.serviceType === 'EKS' ? 'selected' : ''}>EKS — Kubernetes</option>
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
                const $modal = $('#editEnvModal');
                $modal.find('#saveEnvBtn').off('click').on('click', function() {
                    const data = {
                        displayName: ($modal.find('#editEnvDisplayName').val() || '').trim() || env.displayName,
                        description: ($modal.find('#editEnvDescription').val() || '').trim() || null,
                        serviceType: ($modal.find('#editEnvServiceType').val() || 'EC2'),
                        metadata: JSON.stringify({
                            ownerTeam: ($modal.find('#editEnvOwnerTeam').val() || '').trim() || null,
                            defaultCloudProvider: ($modal.find('#editEnvCloudProvider').val() || '') || null
                        })
                    };

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Saving...');

                    ApiClient.put(Config.API.environments.update(env.environmentId), data, { suppressGlobalError: true })
                        .done(function(updated) {
                            hide('editEnvModal');
                            Notifications.success('Environment updated successfully');
                            if (onSuccess) onSuccess(updated);
                        })
                        .fail(function(xhr) {
                            $modal.find('#saveEnvBtn').prop('disabled', false).html('Save Changes');
                            const errMsg = parseValidationError(xhr);
                            showModalError('editEnvModal', errMsg);
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

    /**
     * Parse a Spring validation error response into a clean human-readable string.
     * Handles both {errors:[{field,message}]} and {message:"field: msg, field: msg"} formats.
     */
    function parseValidationError(xhr) {
        const json = xhr.responseJSON;
        if (!json) return 'An unexpected error occurred. Please try again.';

        // Spring MethodArgumentNotValidException → {errors: [{field, message}]}
        if (json.errors && Array.isArray(json.errors) && json.errors.length > 0) {
            return json.errors.map(e => `<li>${Utils.escapeHtml(e.message || e.defaultMessage || '')}</li>`).join('');
        }

        // Single message string — may be comma-separated "field: msg, field: msg"
        if (json.message) {
            const parts = json.message.split(/,\s*(?=[a-zA-Z]+:)/);
            if (parts.length > 1) {
                return parts.map(p => {
                    // Strip "fieldName: " prefix for cleaner display
                    const clean = p.replace(/^[a-zA-Z]+:\s*/, '');
                    return `<li>${Utils.escapeHtml(clean)}</li>`;
                }).join('');
            }
            return Utils.escapeHtml(json.message);
        }

        return 'Failed to save. Please check your input and try again.';
    }

    /**
     * Show an inline error alert inside a modal body, above the form.
     * Replaces any existing error alert — no duplicate toasts.
     */
    function showModalError(modalId, htmlOrText) {
        const isList = htmlOrText.includes('<li>');
        const content = isList
            ? `<ul class="mb-0 ps-3">${htmlOrText}</ul>`
            : htmlOrText;

        const $modal = $(`#${modalId}`);
        $modal.find('.modal-error-alert').remove();
        $modal.find('.modal-body').prepend(`
            <div class="alert alert-danger modal-error-alert py-2 mb-3" role="alert">
                <i class="fas fa-exclamation-circle me-2"></i>${content}
            </div>
        `);
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


/**
 * Destructive Confirm Modal (TASK-004)
 * Type-to-confirm modal for destructive actions like Stop All, Delete, Break Lock
 */
const DestructiveConfirm = (function() {
    'use strict';

    let currentCallback = null;

    /**
     * Show a destructive confirmation modal
     * @param {object} options - Configuration options
     * @param {string} options.title - Modal title
     * @param {string} options.message - Description of what will happen
     * @param {array} options.impact - List of impact items to display
     * @param {string} options.confirmText - Text user must type to confirm
     * @param {string} options.actionText - Button text for confirm action
     * @param {function} options.onConfirm - Callback when confirmed
     */
    function show(options) {
        const { title, message, impact = [], confirmText, actionText, onConfirm } = options;

        // Update modal content
        document.getElementById('destructiveConfirmTitle').innerHTML =
            `<i class="fas fa-exclamation-triangle me-2" aria-hidden="true"></i>${title}`;
        document.getElementById('destructive-message').textContent = message;
        document.getElementById('confirm-text-required').textContent = confirmText;
        document.getElementById('destructive-action-text').textContent = actionText;

        // Populate impact list
        const impactList = document.getElementById('impact-list');
        impactList.innerHTML = impact.map(i => `<li>${i}</li>`).join('');

        // Show/hide impact section
        document.getElementById('destructive-impact').style.display =
            impact.length > 0 ? 'block' : 'none';

        // Reset input and button
        const input = document.getElementById('confirm-text-input');
        const confirmBtn = document.getElementById('btn-destructive-confirm');
        const errorDiv = document.getElementById('confirm-text-error');

        input.value = '';
        confirmBtn.disabled = true;
        errorDiv.style.display = 'none';

        // Store callback
        currentCallback = onConfirm;

        // Remove old event listener and add new one
        const newInput = input.cloneNode(true);
        input.parentNode.replaceChild(newInput, input);

        newInput.addEventListener('input', function() {
            const matches = this.value === confirmText;
            confirmBtn.disabled = !matches;
            errorDiv.style.display = (this.value && !matches) ? 'block' : 'none';
        });

        // Handle confirm button click
        confirmBtn.onclick = function() {
            const inputVal = document.getElementById('confirm-text-input').value;
            if (inputVal === confirmText && currentCallback) {
                // Hide modal
                const modalEl = document.getElementById('destructiveConfirmModal');
                const modal = bootstrap.Modal.getInstance(modalEl);
                if (modal) modal.hide();

                // Execute callback
                currentCallback();
                currentCallback = null;
            }
        };

        // Show modal
        const modalEl = document.getElementById('destructiveConfirmModal');
        const modal = new bootstrap.Modal(modalEl);
        modal.show();

        // Focus on input when modal is shown
        modalEl.addEventListener('shown.bs.modal', function() {
            document.getElementById('confirm-text-input').focus();
        }, { once: true });
    }

    /**
     * Convenience method for Stop All VMs
     */
    function confirmStopAll(environmentName, vmCount, onConfirm) {
        show({
            title: 'Stop All VMs',
            message: `This will stop all VMs in "${environmentName}".`,
            impact: [
                `${vmCount} VM(s) will be stopped`,
                'Running applications will be interrupted',
                'This action cannot be undone automatically'
            ],
            confirmText: environmentName,
            actionText: 'Stop All VMs',
            onConfirm
        });
    }

    /**
     * Convenience method for Break Lock
     */
    function confirmBreakLock(environmentName, lockHolder, onConfirm) {
        show({
            title: 'Break Lock',
            message: `This will forcibly break the lock on "${environmentName}".`,
            impact: [
                `Lock held by ${lockHolder} will be released`,
                'Any in-progress operations may be interrupted',
                'The lock holder will be notified'
            ],
            confirmText: environmentName,
            actionText: 'Break Lock',
            onConfirm
        });
    }

    /**
     * Convenience method for Delete Environment
     */
    function confirmDeleteEnvironment(environmentName, vmCount, onConfirm) {
        show({
            title: 'Delete Environment',
            message: `This will permanently delete "${environmentName}".`,
            impact: [
                `${vmCount} VM(s) will be unregistered`,
                'All access permissions will be removed',
                'This action cannot be undone'
            ],
            confirmText: environmentName,
            actionText: 'Delete Environment',
            onConfirm
        });
    }

    return {
        show,
        confirmStopAll,
        confirmBreakLock,
        confirmDeleteEnvironment
    };
})();

