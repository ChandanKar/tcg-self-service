/**
 * VM Self-Service Platform - VM Operations Module
 * Handles VM start/stop operations with progress tracking
 */

const VmOperations = (function() {
    'use strict';

    // Active operation tracking
    let activeOperations = new Map();
    let pollInterval = null;

    /**
     * Start an environment (all VMs)
     */
    function startEnvironment(envId, envName) {
        return executeOperation(envId, {
            operationType: 'START_ENVIRONMENT',
            targetType: 'ENVIRONMENT',
            targetIds: []
        }, `Starting ${envName}`);
    }

    /**
     * Stop an environment (all VMs)
     */
    function stopEnvironment(envId, envName) {
        return executeOperation(envId, {
            operationType: 'STOP_ENVIRONMENT',
            targetType: 'ENVIRONMENT',
            targetIds: []
        }, `Stopping ${envName}`);
    }

    /**
     * Start a VM group
     */
    function startGroup(envId, groupId, groupName) {
        return executeOperation(envId, {
            operationType: 'START_GROUP',
            targetType: 'GROUP',
            targetIds: [groupId]
        }, `Starting group ${groupName || groupId}`);
    }

    /**
     * Stop a VM group
     */
    function stopGroup(envId, groupId, groupName) {
        return executeOperation(envId, {
            operationType: 'STOP_GROUP',
            targetType: 'GROUP',
            targetIds: [groupId]
        }, `Stopping group ${groupName || groupId}`);
    }

    /**
     * Start a single VM
     */
    function startVm(envId, vmId, vmName) {
        return executeOperation(envId, {
            operationType: 'START_VM',
            targetType: 'VM',
            targetIds: [vmId]
        }, `Starting ${vmName || vmId}`);
    }

    /**
     * Stop a single VM
     */
    function stopVm(envId, vmId, vmName) {
        return executeOperation(envId, {
            operationType: 'STOP_VM',
            targetType: 'VM',
            targetIds: [vmId]
        }, `Stopping ${vmName || vmId}`);
    }

    /**
     * Execute an operation and show progress modal
     */
    function executeOperation(envId, operationData, title) {
        return new Promise((resolve, reject) => {
            // Show progress modal
            showProgressModal(title);

            // Submit operation
            ApiClient.post(Config.API.operations.create(envId), operationData)
                .done(function(execution) {
                    // Store operation info
                    activeOperations.set(execution.executionId, {
                        envId,
                        execution,
                        title,
                        startTime: Date.now()
                    });

                    // Start polling for status
                    startPolling(envId, execution.executionId, resolve, reject);
                })
                .fail(function(xhr) {
                    const msg = xhr.responseJSON?.message || 'Failed to start operation';
                    updateProgressModal('error', msg);
                    reject(new Error(msg));
                });
        });
    }

    /**
     * Show progress modal
     */
    function showProgressModal(title) {
        Modals.show({
            id: 'operationProgressModal',
            title: title,
            size: 'lg',
            body: `
                <div class="operation-progress">
                    <div class="progress-header mb-3">
                        <div class="d-flex justify-content-between align-items-center">
                            <span class="progress-status">
                                <i class="fas fa-spinner fa-spin me-2"></i>
                                <span id="progress-status-text">Initializing...</span>
                            </span>
                            <span class="progress-time text-muted" id="progress-elapsed">0s</span>
                        </div>
                    </div>

                    <div class="progress mb-3" style="height: 24px;">
                        <div class="progress-bar progress-bar-striped progress-bar-animated"
                             id="progress-bar" role="progressbar" style="width: 0%">
                            <span id="progress-percent">0%</span>
                        </div>
                    </div>

                    <div class="progress-details">
                        <div class="row text-center mb-3">
                            <div class="col-4">
                                <div class="metric-mini">
                                    <div class="metric-value text-success" id="progress-completed">0</div>
                                    <div class="metric-label">Completed</div>
                                </div>
                            </div>
                            <div class="col-4">
                                <div class="metric-mini">
                                    <div class="metric-value text-primary" id="progress-pending">0</div>
                                    <div class="metric-label">Pending</div>
                                </div>
                            </div>
                            <div class="col-4">
                                <div class="metric-mini">
                                    <div class="metric-value text-danger" id="progress-failed">0</div>
                                    <div class="metric-label">Failed</div>
                                </div>
                            </div>
                        </div>

                        <div class="vm-status-list" id="vm-status-list">
                            <!-- VM status items will be added here -->
                        </div>
                    </div>
                </div>
            `,
            buttons: [
                { text: 'Cancel Operation', class: 'btn-outline-danger', id: 'btn-cancel-operation' },
                { text: 'Close', class: 'btn-secondary', id: 'btn-close-progress', disabled: true }
            ],
            onShow: function() {
                // Start elapsed time counter
                startElapsedTimer();

                // Handle cancel
                $('#btn-cancel-operation').off('click').on('click', function() {
                    cancelActiveOperation();
                });

                // Handle close
                $('#btn-close-progress').off('click').on('click', function() {
                    Modals.hide('operationProgressModal');
                    stopPolling();
                });
            },
            onHide: function() {
                stopPolling();
                stopElapsedTimer();
            }
        });
    }

    /**
     * Update progress modal with current status
     */
    function updateProgressModal(status, message, execution) {
        const $statusText = $('#progress-status-text');
        const $progressBar = $('#progress-bar');
        const $progressPercent = $('#progress-percent');
        const $cancelBtn = $('#btn-cancel-operation');
        const $closeBtn = $('#btn-close-progress');

        if (status === 'error') {
            $statusText.html(`<i class="fas fa-times-circle text-danger me-2"></i>${message}`);
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-danger').css('width', '100%');
            $progressPercent.text('Error');
            $cancelBtn.hide();
            $closeBtn.prop('disabled', false);
            stopElapsedTimer();
            return;
        }

        if (status === 'cancelled') {
            $statusText.html(`<i class="fas fa-ban text-secondary me-2"></i>Operation cancelled`);
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-secondary').css('width', '100%');
            $progressPercent.text('Cancelled');
            $cancelBtn.hide();
            $closeBtn.prop('disabled', false);
            stopElapsedTimer();
            return;
        }

        if (execution) {
            const steps = execution.steps || [];
            const total = steps.length || 1;
            const completed = steps.filter(s => s.status === 'COMPLETED').length;
            const failed = steps.filter(s => s.status === 'FAILED').length;
            const pending = total - completed - failed;
            const percent = Math.round((completed / total) * 100);

            // Update counts
            $('#progress-completed').text(completed);
            $('#progress-pending').text(pending);
            $('#progress-failed').text(failed);

            // Update progress bar
            $progressBar.css('width', `${percent}%`);
            $progressPercent.text(`${percent}%`);

            // Update status text
            if (execution.status === 'COMPLETED') {
                $statusText.html(`<i class="fas fa-check-circle text-success me-2"></i>Completed successfully`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-success');
                $cancelBtn.hide();
                $closeBtn.prop('disabled', false);
                stopElapsedTimer();
            } else if (execution.status === 'FAILED') {
                $statusText.html(`<i class="fas fa-times-circle text-danger me-2"></i>Operation failed`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-danger');
                $cancelBtn.hide();
                $closeBtn.prop('disabled', false);
                stopElapsedTimer();
            } else if (execution.status === 'CANCELLED') {
                $statusText.html(`<i class="fas fa-ban text-secondary me-2"></i>Operation cancelled`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-secondary');
                $cancelBtn.hide();
                $closeBtn.prop('disabled', false);
                stopElapsedTimer();
            } else {
                // In progress
                const currentStep = steps.find(s => s.status === 'IN_PROGRESS');
                if (currentStep) {
                    $statusText.html(`<i class="fas fa-spinner fa-spin me-2"></i>Processing: ${currentStep.vmName || currentStep.targetId}`);
                } else {
                    $statusText.html(`<i class="fas fa-spinner fa-spin me-2"></i>Processing...`);
                }
            }

            // Update VM status list
            updateVmStatusList(steps);
        }
    }

    /**
     * Update VM status list in modal
     */
    function updateVmStatusList(steps) {
        const $list = $('#vm-status-list');

        if (!steps || steps.length === 0) {
            $list.html('<p class="text-muted text-center">No steps to display</p>');
            return;
        }

        const items = steps.map(step => {
            let statusIcon, statusClass;
            switch (step.status) {
                case 'COMPLETED':
                    statusIcon = 'fa-check-circle';
                    statusClass = 'text-success';
                    break;
                case 'FAILED':
                    statusIcon = 'fa-times-circle';
                    statusClass = 'text-danger';
                    break;
                case 'IN_PROGRESS':
                    statusIcon = 'fa-spinner fa-spin';
                    statusClass = 'text-primary';
                    break;
                case 'CANCELLED':
                    statusIcon = 'fa-ban';
                    statusClass = 'text-secondary';
                    break;
                default:
                    statusIcon = 'fa-clock';
                    statusClass = 'text-muted';
            }

            const errorMsg = step.errorMessage ?
                `<small class="text-danger d-block">${Utils.escapeHtml(step.errorMessage)}</small>` : '';

            return `
                <div class="vm-status-item d-flex justify-content-between align-items-center py-2 border-bottom">
                    <div>
                        <i class="fas ${statusIcon} ${statusClass} me-2"></i>
                        <span>${Utils.escapeHtml(step.vmName || step.targetId)}</span>
                        ${errorMsg}
                    </div>
                    <span class="badge bg-${getStatusBadgeClass(step.status)}">${step.status}</span>
                </div>
            `;
        }).join('');

        $list.html(items);
    }

    /**
     * Get badge class for status
     */
    function getStatusBadgeClass(status) {
        switch (status) {
            case 'COMPLETED': return 'success';
            case 'FAILED': return 'danger';
            case 'IN_PROGRESS': return 'primary';
            case 'CANCELLED': return 'secondary';
            default: return 'secondary';
        }
    }

    /**
     * Start polling for operation status
     */
    function startPolling(envId, executionId, resolve, reject) {
        stopPolling(); // Clear any existing poll

        const poll = function() {
            ApiClient.get(Config.API.operations.get(envId, executionId))
                .done(function(execution) {
                    updateProgressModal('progress', null, execution);

                    if (execution.status === 'COMPLETED') {
                        stopPolling();
                        Notifications.success('Operation completed successfully');
                        resolve(execution);
                    } else if (execution.status === 'FAILED') {
                        stopPolling();
                        Notifications.error('Operation failed');
                        reject(new Error('Operation failed'));
                    } else if (execution.status === 'CANCELLED') {
                        stopPolling();
                        Notifications.info('Operation cancelled');
                        resolve(execution);
                    }
                    // Continue polling if still in progress
                })
                .fail(function(xhr) {
                    stopPolling();
                    updateProgressModal('error', 'Failed to get operation status');
                    reject(new Error('Failed to get operation status'));
                });
        };

        // Initial poll
        poll();

        // Continue polling
        pollInterval = setInterval(poll, Config.UI.operationPollInterval || 2000);
    }

    /**
     * Stop polling
     */
    function stopPolling() {
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
    }

    /**
     * Cancel active operation
     */
    function cancelActiveOperation() {
        const operation = Array.from(activeOperations.values())[0];
        if (!operation) return;

        Modals.confirm(
            'Cancel Operation',
            'Are you sure you want to cancel this operation? VMs that have already started/stopped will remain in their current state.',
            function() {
                ApiClient.post(Config.API.operations.cancel(operation.envId, operation.execution.executionId))
                    .done(function() {
                        updateProgressModal('cancelled');
                        stopPolling();
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to cancel operation');
                    });
            },
            { confirmText: 'Cancel Operation', confirmClass: 'btn-warning' }
        );
    }

    // Elapsed time tracking
    let elapsedInterval = null;
    let elapsedStart = null;

    function startElapsedTimer() {
        elapsedStart = Date.now();
        stopElapsedTimer();

        elapsedInterval = setInterval(function() {
            const elapsed = Math.floor((Date.now() - elapsedStart) / 1000);
            const minutes = Math.floor(elapsed / 60);
            const seconds = elapsed % 60;

            const display = minutes > 0 ?
                `${minutes}m ${seconds}s` :
                `${seconds}s`;

            $('#progress-elapsed').text(display);
        }, 1000);
    }

    function stopElapsedTimer() {
        if (elapsedInterval) {
            clearInterval(elapsedInterval);
            elapsedInterval = null;
        }
    }

    /**
     * Get operation history for an environment
     */
    function getHistory(envId, page = 0, size = 20) {
        return new Promise((resolve, reject) => {
            ApiClient.get(`${Config.API.operations.list(envId)}?page=${page}&size=${size}`)
                .done(resolve)
                .fail(reject);
        });
    }

    /**
     * Show operation history modal
     */
    function showHistoryModal(envId, envName) {
        Modals.show({
            id: 'operationHistoryModal',
            title: `Operation History: ${envName}`,
            size: 'lg',
            body: `
                <div class="operation-history">
                    <div class="text-center py-4">
                        <i class="fas fa-spinner fa-spin"></i> Loading history...
                    </div>
                </div>
            `,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true }
            ],
            onShow: function() {
                loadHistoryContent(envId);
            }
        });
    }

    /**
     * Load history content into modal
     */
    function loadHistoryContent(envId) {
        getHistory(envId)
            .then(function(data) {
                const executions = data.content || data || [];

                if (executions.length === 0) {
                    $('.operation-history').html(`
                        <div class="empty-state text-center py-4">
                            <i class="fas fa-history fa-2x text-muted mb-2"></i>
                            <p>No operations found</p>
                        </div>
                    `);
                    return;
                }

                const rows = executions.map(exec => {
                    const statusConfig = Config.STATUS.operation[exec.status] || { class: 'text-secondary', icon: 'fa-question' };
                    const duration = exec.completedAt && exec.startedAt ?
                        Utils.formatDuration(new Date(exec.completedAt) - new Date(exec.startedAt)) : '-';

                    return `
                        <tr>
                            <td>${Utils.formatRelativeTime(exec.createdAt)}</td>
                            <td><span class="badge bg-secondary">${exec.operationType}</span></td>
                            <td>
                                <span class="${statusConfig.class}">
                                    <i class="fas ${statusConfig.icon} me-1"></i>${exec.status}
                                </span>
                            </td>
                            <td>${duration}</td>
                            <td>${exec.initiatedByDisplayName || exec.initiatedBy || '-'}</td>
                        </tr>
                    `;
                }).join('');

                $('.operation-history').html(`
                    <table class="table table-sm table-hover">
                        <thead>
                            <tr>
                                <th>Time</th>
                                <th>Operation</th>
                                <th>Status</th>
                                <th>Duration</th>
                                <th>Initiated By</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${rows}
                        </tbody>
                    </table>
                `);
            })
            .catch(function() {
                $('.operation-history').html(`
                    <div class="alert alert-danger">Failed to load operation history</div>
                `);
            });
    }

    // Public API
    return {
        startEnvironment,
        stopEnvironment,
        startGroup,
        stopGroup,
        startVm,
        stopVm,
        showHistoryModal,
        getHistory
    };
})();

