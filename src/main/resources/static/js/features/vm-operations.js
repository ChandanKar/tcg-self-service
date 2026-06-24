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
     * Backend: operationType=START, no vmIds/groupIds → operates on all VMs
     */
    function startEnvironment(envId, envName) {
        return executeOperation(envId, {
            operationType: 'START'
        }, `Starting ${envName}`);
    }

    /**
     * Stop an environment (all VMs)
     */
    function stopEnvironment(envId, envName) {
        return executeOperation(envId, {
            operationType: 'STOP'
        }, `Stopping ${envName}`);
    }

    /**
     * Start a VM group
     * Backend: operationType=START, groupIds=[groupId]
     */
    function startGroup(envId, groupId, groupName) {
        return executeOperation(envId, {
            operationType: 'START',
            groupIds: [groupId]
        }, `Starting group ${groupName || groupId}`);
    }

    /**
     * Stop a VM group
     */
    function stopGroup(envId, groupId, groupName) {
        return executeOperation(envId, {
            operationType: 'STOP',
            groupIds: [groupId]
        }, `Stopping group ${groupName || groupId}`);
    }

    /**
     * Start a single VM
     * Backend: operationType=START, vmIds=[vmId]
     */
    function startVm(envId, vmId, vmName) {
        return executeOperation(envId, {
            operationType: 'START',
            vmIds: [vmId]
        }, `Starting ${vmName || vmId}`);
    }

    /**
     * Stop a single VM
     */
    function stopVm(envId, vmId, vmName) {
        return executeOperation(envId, {
            operationType: 'STOP',
            vmIds: [vmId]
        }, `Stopping ${vmName || vmId}`);
    }

    /**
     * Execute an operation and show progress modal
     */
    // Operation timeout: 30 minutes (in milliseconds)
    const OPERATION_TIMEOUT = 30 * 60 * 1000;
    const MAX_POLL_FAILURES = 3;

    function executeOperation(envId, operationData, title) {
        return new Promise((resolve, reject) => {
            // Show progress modal
            showProgressModal(title);

            // Submit operation
            ApiClient.post(Config.API.operations.create(envId), operationData)
                .done(function(execution) {
                    // Store operation info with explicit state tracking
                    activeOperations.set(execution.executionId, {
                        envId,
                        execution,
                        title,
                        state: 'running',  // Explicit state: running, completed, error, timeout
                        startTime: Date.now(),
                        lastUpdateTime: Date.now(),
                        pollFailures: 0
                    });

                    // Start polling for status
                    startPolling(envId, execution.executionId, resolve, reject);
                })
                .fail(function(xhr) {
                    // User-friendly error messages — stack traces stay in server logs only
                    let userMessage;
                    if (xhr.status === 409) {
                        userMessage = xhr.responseJSON?.message || 'Another operation is already in progress. Please wait for it to complete.';
                    } else if (xhr.status === 400) {
                        userMessage = xhr.responseJSON?.message || 'Invalid operation request. Please check your selection and try again.';
                    } else if (xhr.status === 403) {
                        userMessage = 'You do not have permission to perform this operation. You may need to acquire a lock first.';
                    } else if (xhr.status === 404) {
                        userMessage = 'Environment or VMs not found. The data may have changed — please refresh and try again.';
                    } else if (xhr.status >= 500) {
                        userMessage = 'A server error occurred while starting the operation. Please try again later.';
                    } else if (xhr.status === 0) {
                        userMessage = 'Unable to connect to the server. Please check your network connection.';
                    } else {
                        userMessage = 'Failed to start operation. Please try again.';
                    }
                    console.error('Operation failed:', xhr.status, xhr.responseJSON || xhr.statusText);
                    // Always show error state and stop spinner
                    stopPolling();  // Stop any pending polls
                    updateProgressModal('error', userMessage);
                    // Update operation state to error
                    const operation = Array.from(activeOperations.values())[0];
                    if (operation) {
                        operation.state = 'error';
                    }
                    reject(new Error(userMessage));
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
                                <span id="progress-status-text"><i class="fas fa-spinner fa-spin me-2"></i>Initializing...</span>
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
                    stopPollingIfNoRunningOperation();
                });
            },
            onHide: function() {
                stopPollingIfNoRunningOperation();
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

        if (status === 'timeout') {
            $statusText.html(`<i class="fas fa-clock text-warning me-2"></i>Operation timed out`);
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-warning').css('width', '100%');
            $progressPercent.text('Timed out');
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
            const steps = execution.details || [];
            const hasSteps = steps.length > 0;
            const total = hasSteps ? steps.length : (Number(execution.totalTargets) || 0);
            const completed = hasSteps
                ? steps.filter(s => (s.status || '').toUpperCase() === 'COMPLETED').length
                : (Number(execution.completedTargets) || 0);
            const failed = hasSteps
                ? steps.filter(s => (s.status || '').toUpperCase() === 'FAILED').length
                : (Number(execution.failedTargets) || 0);
            const inProgress = hasSteps
                ? steps.filter(s => (s.status || '').toUpperCase() === 'IN_PROGRESS').length
                : 0;
            const pending = Math.max(0, total - completed - failed);
            const progressValues = hasSteps
                ? steps.map(step => {
                    const normalizedStatus = (step.status || '').toUpperCase();
                    if (normalizedStatus === 'COMPLETED') return 100;
                    if (normalizedStatus === 'FAILED') return 100;
                    return Number(step.progressPercentage) || 0;
                })
                : [];

            // Calculate percent — for terminal states force meaningful values
            let percent;
            if (execution.status === 'COMPLETED') {
                percent = 100;
            } else if (execution.status === 'FAILED' || execution.status === 'PARTIAL_SUCCESS') {
                percent = 100;
            } else if (total === 0) {
                percent = Number(execution.progressPercentage) || 10; // show some progress if no steps yet
            } else if (progressValues.length > 0 && progressValues.some(value => value > 0)) {
                percent = Math.round(progressValues.reduce((sum, value) => sum + value, 0) / progressValues.length);
            } else {
                const activeCredit = inProgress > 0 ? 0.5 : 0;
                percent = Math.round(((completed + failed + activeCredit) / total) * 100);
                percent = Math.max(percent, Number(execution.progressPercentage) || 0);
                percent = Math.min(percent, 95);
            }
            percent = Math.max(0, Math.min(100, percent));
            const currentStep = steps.find(s => (s.status || '').toUpperCase() === 'IN_PROGRESS');
            const stageLabel = getProgressStageLabel(execution, steps, currentStep, percent);

            // Update counts
            $('#progress-completed').text(completed);
            $('#progress-pending').text(pending);
            $('#progress-failed').text(failed);

            // Update progress bar width + label
            $progressBar.css('width', `${percent}%`);
            $progressPercent.text(stageLabel ? `${percent}% - ${stageLabel}` : `${percent}%`);

            // Update status text and bar style based on terminal/in-progress state
            if (execution.status === 'COMPLETED') {
                $statusText.html(`<i class="fas fa-check-circle text-success me-2"></i>Completed successfully`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-success');
                $cancelBtn.hide();
                $closeBtn.prop('disabled', false);
                stopElapsedTimer();
            } else if (execution.status === 'PARTIAL_SUCCESS') {
                $statusText.html(`<i class="fas fa-exclamation-triangle text-warning me-2"></i>Completed with some failures`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-warning');
                $cancelBtn.hide();
                $closeBtn.prop('disabled', false);
                stopElapsedTimer();
            } else if (execution.status === 'FAILED') {
                $statusText.html(`<i class="fas fa-times-circle text-danger me-2"></i>Operation failed`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-danger').css('width', '100%');
                $progressPercent.text('Failed');
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
                // In progress — keep spinner
                if (currentStep) {
                    const stepLabel = currentStep.stageLabel || `Processing: ${currentStep.targetName || currentStep.targetId}`;
                    $statusText.html(`<i class="fas fa-spinner fa-spin me-2"></i>${Utils.escapeHtml(stepLabel)}`);
                } else {
                    $statusText.html(`<i class="fas fa-spinner fa-spin me-2"></i>Processing...`);
                }
            }

            // Update VM status list
            updateVmStatusList(steps);
        }
    }

    function getProgressStageLabel(execution, steps, currentStep, percent) {
        if (execution.status === 'COMPLETED') return 'Completed';
        if (execution.status === 'FAILED') return 'Failed';
        if (execution.status === 'PARTIAL_SUCCESS') return 'Completed with failures';
        if (execution.status === 'CANCELLED') return 'Cancelled';

        if (currentStep?.stageLabel) {
            return currentStep.stageLabel;
        }

        const activeStep = steps.find(step =>
            Number(step.progressPercentage) > 0 &&
            (step.status || '').toUpperCase() !== 'COMPLETED'
        );
        if (activeStep?.stageLabel) {
            return activeStep.stageLabel;
        }

        if (percent > 0) return 'Processing';
        return 'Initializing';
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
            const normalizedStatus = (step.status || '').toUpperCase();
            switch (normalizedStatus) {
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
            const stageMsg = step.stageLabel && normalizedStatus !== 'COMPLETED' ?
                `<small class="text-muted d-block">${Utils.escapeHtml(step.stageLabel)}</small>` : '';

            return `
                <div class="vm-status-item d-flex justify-content-between align-items-center py-2 border-bottom">
                    <div>
                        <i class="fas ${statusIcon} ${statusClass} me-2"></i>
                        <span>${Utils.escapeHtml(step.targetName || step.targetId)}</span>
                        ${stageMsg}
                        ${errorMsg}
                    </div>
                    <span class="badge bg-${getStatusBadgeClass(normalizedStatus)}">${normalizedStatus}</span>
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
            case 'PARTIAL_SUCCESS': return 'warning';
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

        const operation = activeOperations.get(executionId);

        const poll = function() {
            // Check for timeout (30 minutes)
            if (operation && (Date.now() - operation.startTime) > OPERATION_TIMEOUT) {
                stopPolling();
                operation.state = 'timeout';
                updateProgressModal('timeout');
                const timeoutMsg = 'Operation exceeded 30-minute timeout. Please contact support if the operation is still running.';
                Notifications.error(timeoutMsg);
                reject(new Error(timeoutMsg));
                return;
            }

            ApiClient.get(Config.API.operations.get(envId, executionId))
                .done(function(execution) {
                    // Update last status check time
                    if (operation) {
                        operation.lastUpdateTime = Date.now();
                        operation.pollFailures = 0;
                    }

                    updateProgressModal('progress', null, execution);
                    publishOperationStatus(envId, execution);

                    if (execution.status === 'COMPLETED') {
                        stopPolling();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        Notifications.success('Operation completed successfully! All VMs have been processed.');
                        resolve(execution);
                    } else if (execution.status === 'PARTIAL_SUCCESS') {
                        stopPolling();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        Notifications.warning('Operation completed with some failures. Check the details for more information.');
                        resolve(execution);
                    } else if (execution.status === 'FAILED') {
                        stopPolling();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        const failMsg = execution.errorMessage || 'One or more VMs failed to process. Check the operation details for more information.';
                        Notifications.error(failMsg);
                        reject(new Error(failMsg));
                    } else if (execution.status === 'CANCELLED') {
                        stopPolling();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        Notifications.info('Operation was cancelled. VMs already processed will remain in their current state.');
                        resolve(execution);
                    }
                    // Continue polling if still in progress
                })
                .fail(function(xhr) {
                    if (operation) {
                        operation.pollFailures = (operation.pollFailures || 0) + 1;
                    }
                    const failures = operation ? operation.pollFailures : MAX_POLL_FAILURES;
                    console.warn('Failed to poll operation status:', xhr.status, xhr.statusText, `attempt ${failures}/${MAX_POLL_FAILURES}`);

                    if (failures < MAX_POLL_FAILURES) {
                        $('#progress-status-text').html('<i class="fas fa-spinner fa-spin me-2"></i>Reconnecting to operation status...');
                        return;
                    }

                    stopPolling();
                    if (operation) {
                        operation.state = 'error';
                    }
                    console.error('Failed to poll operation status:', xhr.status, xhr.statusText);
                    updateProgressModal('error', 'Lost connection to the server while tracking the operation. The operation may still be running — please refresh to check.');
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

    function hasRunningOperation() {
        return Array.from(activeOperations.values()).some(operation => operation.state === 'running');
    }

    function stopPollingIfNoRunningOperation() {
        if (!hasRunningOperation()) {
            stopPolling();
        }
    }

    function publishOperationStatus(envId, execution) {
        if (typeof window === 'undefined' || typeof CustomEvent === 'undefined') {
            return;
        }
        window.dispatchEvent(new CustomEvent('vm-operation-status', {
            detail: { envId, execution }
        }));
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

