/**
 * VM Self-Service Platform - VM Operations Module
 * Handles VM start/stop operations with progress tracking
 */

const VmOperations = (function() {
    'use strict';

    // Active operation tracking: one poll timer per execution, so starting a second operation
    // never stops tracking the first (LOW-OPS-POLL-TIMER). Not page-scoped: an operation keeps
    // being tracked after the user navigates away.
    let activeOperations = new Map();
    const pollTimers = new Map();      // executionId -> interval id
    let modalExecutionId = null;       // the execution the progress modal shows
    let modalToken = 0;                // which progress modal is current (a closed one fades out later)
    const RUNNING_STATUSES = ['PENDING', 'IN_PROGRESS'];

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
    function startGroup(envId, groupId, groupName, noun) {
        return executeOperation(envId, {
            operationType: 'START',
            groupIds: [groupId]
        }, `Starting ${(noun || 'group').toLowerCase()} ${groupName || groupId}`);
    }

    /**
     * Stop a VM group
     */
    function stopGroup(envId, groupId, groupName, noun) {
        return executeOperation(envId, {
            operationType: 'STOP',
            groupIds: [groupId]
        }, `Stopping ${(noun || 'group').toLowerCase()} ${groupName || groupId}`);
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

            // Submit operation (errors are shown in the progress modal, with the server's reason)
            ApiClient.post(Config.API.operations.create(envId), operationData, { suppressGlobalError: true })
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

                    // The modal follows this execution; start polling for its status
                    modalExecutionId = execution.executionId;
                    $('#btn-cancel-operation').prop('hidden', false).prop('disabled', false)
                        .data('env-id', envId).data('execution-id', execution.executionId);
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
                        userMessage = xhr.responseJSON?.message || 'You do not have permission to perform this operation. You may need to acquire a lock first.';
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
                    // Nothing was created, so no poll to stop: just show the reason.
                    modalExecutionId = null;
                    updateProgressModal('error', userMessage);
                    reject(new Error(userMessage));
                });
        });
    }

    /**
     * Show progress modal
     */
    function showProgressModal(title) {
        const token = ++modalToken;
        modalExecutionId = null;
        hideBanner();
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
                            <div class="col-3">
                                <div class="metric-mini">
                                    <div class="metric-value text-success" id="progress-completed">0</div>
                                    <div class="metric-label">Completed</div>
                                </div>
                            </div>
                            <div class="col-3">
                                <div class="metric-mini">
                                    <div class="metric-value text-primary" id="progress-pending">0</div>
                                    <div class="metric-label">Pending</div>
                                </div>
                            </div>
                            <div class="col-3">
                                <div class="metric-mini">
                                    <div class="metric-value text-muted" id="progress-skipped">0</div>
                                    <div class="metric-label">Skipped</div>
                                </div>
                            </div>
                            <div class="col-3">
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
                { text: 'Cancel operation', class: 'btn-outline-danger', id: 'btn-cancel-operation' },
                { text: 'Close', class: 'btn-secondary', id: 'btn-close-progress' }
            ],
            onShow: function() {
                // Start elapsed time counter
                startElapsedTimer();
                // Shown once the operation exists (see executeOperation), hidden when it ends.
                $('#btn-cancel-operation').prop('hidden', !modalExecutionId).off('click').on('click', function() {
                    const $btn = $(this);
                    confirmCancel($btn.data('env-id'), $btn.data('execution-id'), $btn);
                });

                // Handle close — the operation keeps running server-side and keeps being polled
                // after the modal is dismissed, so it's safe to close it at any point.
                $('#btn-close-progress').off('click').on('click', function() {
                    Modals.hide('operationProgressModal');
                });
            },
            onHide: function() {
                // A closed modal's 'hidden' fires after its fade: only unbind if it is still current.
                if (token === modalToken) {
                    modalExecutionId = null;
                    stopElapsedTimer();
                    refreshBanner();
                }
            }
        });
    }

    // ─── Operation banner (E13-T07) ──────────────────────────────────────────

    /** The most recently started operation that is still running, with its id. */
    function latestRunningOperation() {
        let latest = null;
        activeOperations.forEach((op, executionId) => {
            if (op.state === 'running' && (!latest || op.startTime > latest.op.startTime)) {
                latest = { executionId, op };
            }
        });
        return latest;
    }

    function hasRunningOperation() {
        return latestRunningOperation() !== null;
    }

    function hideBanner() {
        $('#operation-banner').hide();
    }

    /** Show the banner while an operation runs and its dialog is closed; hide it otherwise. */
    function refreshBanner() {
        const running = latestRunningOperation();
        const dialogOpen = $('#operationProgressModal').hasClass('show') && modalExecutionId !== null;
        if (!running || dialogOpen) {
            hideBanner();
            return;
        }
        const summary = summarizeExecution(running.op.execution);
        $('#operation-description').text(running.op.title || 'Operation in progress');
        $('#operation-progress-bar').css('width', `${summary.percent}%`).attr('aria-valuenow', summary.percent);
        $('#operation-count').text(`${summary.completed}/${summary.total}`);
        $('#view-operation-details').data('execution-id', running.executionId);
        $('#operation-banner').show();
    }

    /** Re-open the progress dialog for the running operation shown in the banner. */
    function reopenProgress() {
        const running = latestRunningOperation();
        if (!running) {
            hideBanner();
            return;
        }
        showProgressModal(running.op.title);
        modalExecutionId = running.executionId;
        $('#btn-cancel-operation').prop('hidden', false).prop('disabled', false)
            .data('env-id', running.op.envId).data('execution-id', running.executionId);
        if (running.op.execution) updateProgressModal('progress', null, running.op.execution);
    }

    /**
     * Counts and overall percent for an execution (steps when present, else the execution's own
     * totals); terminal states read 100%. Shared by the progress dialog and the banner.
     */
    function summarizeExecution(execution) {
        if (!execution) return { steps: [], total: 0, completed: 0, failed: 0, skipped: 0, pending: 0, percent: 0 };
        const steps = execution.details || [];
        const hasSteps = steps.length > 0;
        const total = hasSteps ? steps.length : (Number(execution.totalTargets) || 0);
        const completed = hasSteps
            ? steps.filter(s => (s.status || '').toUpperCase() === 'COMPLETED').length
            : (Number(execution.completedTargets) || 0);
        const failed = hasSteps
            ? steps.filter(s => (s.status || '').toUpperCase() === 'FAILED').length
            : (Number(execution.failedTargets) || 0);
        const skipped = hasSteps
            ? steps.filter(s => (s.status || '').toUpperCase() === 'SKIPPED').length
            : 0;
        const inProgress = hasSteps
            ? steps.filter(s => (s.status || '').toUpperCase() === 'IN_PROGRESS').length
            : 0;
        const pending = Math.max(0, total - completed - failed - skipped);
        const progressValues = hasSteps
            ? steps.map(step => {
                const normalizedStatus = (step.status || '').toUpperCase();
                if (normalizedStatus === 'COMPLETED') return 100;
                if (normalizedStatus === 'FAILED') return 100;
                if (normalizedStatus === 'SKIPPED') return 100;
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
        return { steps, total, completed, failed, skipped, pending, percent };
    }

    /**
     * Update progress modal with current status
     */
    function updateProgressModal(status, message, execution) {
        const $statusText = $('#progress-status-text');
        const $progressBar = $('#progress-bar');
        const $progressPercent = $('#progress-percent');

        if (status === 'error') {
            $statusText.empty()
                .append('<i class="fas fa-times-circle text-danger me-2"></i>')
                .append(document.createTextNode(message == null ? '' : String(message)));
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-danger').css('width', '100%');
            $progressPercent.text('Error');
            stopElapsedTimer();
            return;
        }

        if (status === 'timeout') {
            $statusText.html(`<i class="fas fa-clock text-warning me-2"></i>Operation timed out`);
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-warning').css('width', '100%');
            $progressPercent.text('Timed out');
            stopElapsedTimer();
            return;
        }

        if (status === 'cancelled') {
            $statusText.html(`<i class="fas fa-ban text-secondary me-2"></i>Operation cancelled`);
            $progressBar.removeClass('progress-bar-animated progress-bar-striped')
                        .addClass('bg-secondary').css('width', '100%');
            $progressPercent.text('Cancelled');
            stopElapsedTimer();
            return;
        }

        if (execution) {
            const { steps, completed, failed, skipped, pending, percent } = summarizeExecution(execution);
            const currentStep = steps.find(s => (s.status || '').toUpperCase() === 'IN_PROGRESS');
            const stageLabel = getProgressStageLabel(execution, steps, currentStep, percent);

            // Update counts
            $('#progress-completed').text(completed);
            $('#progress-pending').text(pending);
            $('#progress-skipped').text(skipped);
            $('#progress-failed').text(failed);

            // Update progress bar width + label
            $progressBar.css('width', `${percent}%`);
            $progressPercent.text(stageLabel ? `${percent}% - ${stageLabel}` : `${percent}%`);

            // Update status text and bar style based on terminal/in-progress state
            if (execution.status === 'COMPLETED') {
                $statusText.html(`<i class="fas fa-check-circle text-success me-2"></i>Completed successfully`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-success');
                stopElapsedTimer();
            } else if (execution.status === 'PARTIAL_SUCCESS') {
                const partialMsg = `Completed with failures (${failed} failed, ${skipped} skipped)`;
                $statusText.html(`<i class="fas fa-exclamation-triangle text-warning me-2"></i>${partialMsg}`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-warning');
                stopElapsedTimer();
            } else if (execution.status === 'FAILED') {
                // The server says why: "All N steps failed", "No step succeeded (...)", "Interrupted: ..."
                $statusText.empty()
                    .append('<i class="fas fa-times-circle text-danger me-2"></i>')
                    .append(document.createTextNode('Failed — ' + (execution.errorMessage || 'no step succeeded')));
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-danger').css('width', '100%');
                $progressPercent.text('Failed');
                stopElapsedTimer();
            } else if (execution.status === 'CANCELLED') {
                $statusText.html(`<i class="fas fa-ban text-secondary me-2"></i>Operation cancelled`);
                $progressBar.removeClass('progress-bar-animated progress-bar-striped').addClass('bg-secondary');
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
            $('#btn-cancel-operation').prop('hidden', !RUNNING_STATUSES.includes(execution.status));
        }
    }

    function getProgressStageLabel(execution, steps, currentStep, percent) {
        if (execution.status === 'COMPLETED') return 'Completed';
        if (execution.status === 'FAILED') return 'Failed';
        if (execution.status === 'PARTIAL_SUCCESS') {
            const anyFailed = (steps || []).some(s => (s.status || '').toUpperCase() === 'FAILED');
            return anyFailed ? 'Completed with failures' : 'Completed, some skipped';
        }
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
                case 'SKIPPED':
                    statusIcon = 'fa-forward';
                    statusClass = 'text-warning';
                    break;
                default:
                    statusIcon = 'fa-clock';
                    statusClass = 'text-muted';
            }

            // A skipped step's message ("dependency X failed to start") is a reason, not an
            // error on this VM — show it amber, not red.
            const msgClass = normalizedStatus === 'SKIPPED' ? 'text-warning' : 'text-danger';
            const errorMsg = step.errorMessage ?
                `<small class="${msgClass} d-block">${Utils.escapeHtml(step.errorMessage)}</small>` : '';
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
            case 'SKIPPED': return 'warning';
            default: return 'secondary';
        }
    }

    /**
     * Start polling for operation status
     */
    function startPolling(envId, executionId, resolve, reject) {
        stopPollingExecution(executionId); // only this execution's timer

        const operation = activeOperations.get(executionId);
        const stopThis = () => stopPollingExecution(executionId);
        // Only the execution the modal was opened for may write to it.
        const showInModal = (status, message, execution) => {
            if (modalExecutionId === executionId) updateProgressModal(status, message, execution);
        };

        const poll = function() {
            // Check for timeout (30 minutes)
            if (operation && (Date.now() - operation.startTime) > OPERATION_TIMEOUT) {
                stopThis();
                operation.state = 'timeout';
                refreshBanner();
                showInModal('timeout');
                const timeoutMsg = 'Operation exceeded 30-minute timeout. Please contact support if the operation is still running.';
                Notifications.error(timeoutMsg);
                reject(new Error(timeoutMsg));
                return;
            }

            ApiClient.get(Config.API.operations.get(envId, executionId), { suppressGlobalError: true }) // failures shown in the progress dialog (pollFailures)
                .done(function(execution) {
                    // Update last status check time
                    if (operation) {
                        operation.lastUpdateTime = Date.now();
                        operation.pollFailures = 0;
                        operation.execution = execution;
                    }

                    showInModal('progress', null, execution);
                    publishOperationStatus(envId, execution);

                    if (execution.status === 'COMPLETED') {
                        stopThis();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        Notifications.success('Operation completed successfully! All VMs have been processed.');
                        resolve(execution);
                    } else if (execution.status === 'PARTIAL_SUCCESS') {
                        stopThis();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        const details = execution.details || [];
                        const anyFailed = details.some(s => (s.status || '').toUpperCase() === 'FAILED');
                        Notifications.warning(anyFailed
                            ? 'Operation completed with some failures. Check the details for more information.'
                            : 'Operation completed. Some steps were skipped because a dependency did not start.');
                        resolve(execution);
                    } else if (execution.status === 'FAILED') {
                        stopThis();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        const failMsg = execution.errorMessage || 'One or more VMs failed to process. Check the operation details for more information.';
                        Notifications.error(failMsg);
                        reject(new Error(failMsg));
                    } else if (execution.status === 'CANCELLED') {
                        stopThis();
                        if (operation) {
                            operation.state = 'completed';
                        }
                        Notifications.info('Operation was cancelled. VMs already processed will remain in their current state.');
                        resolve(execution);
                    }
                    // Continue polling if still in progress
                    refreshBanner();
                })
                .fail(function(xhr) {
                    if (operation) {
                        operation.pollFailures = (operation.pollFailures || 0) + 1;
                    }
                    const failures = operation ? operation.pollFailures : MAX_POLL_FAILURES;
                    console.warn('Failed to poll operation status:', xhr.status, xhr.statusText, `attempt ${failures}/${MAX_POLL_FAILURES}`);

                    if (failures < MAX_POLL_FAILURES) {
                        if (modalExecutionId === executionId) {
                            $('#progress-status-text').html('<i class="fas fa-spinner fa-spin me-2"></i>Reconnecting to operation status...');
                        }
                        return;
                    }

                    stopThis();
                    if (operation) {
                        operation.state = 'error';
                    }
                    refreshBanner();
                    console.error('Failed to poll operation status:', xhr.status, xhr.statusText);
                    showInModal('error', 'Lost connection to the server while tracking the operation. The operation may still be running — please refresh to check.');
                    reject(new Error('Failed to get operation status'));
                });
        };

        // Initial poll
        poll();

        // Continue polling
        pollTimers.set(executionId, setInterval(poll, Config.UI.operationPollInterval || 2000));
    }

    /** Stop polling one execution. */
    function stopPollingExecution(executionId) {
        const timer = pollTimers.get(executionId);
        if (timer) {
            clearInterval(timer);
            pollTimers.delete(executionId);
        }
    }

    /** Stop every poll (logout). */
    function stopAllPolling() {
        pollTimers.forEach(timer => clearInterval(timer));
        pollTimers.clear();
    }

    /** Executions this page is still tracking (for tests and diagnostics). */
    function trackedExecutionIds() {
        return Array.from(pollTimers.keys());
    }

    /**
     * Cancel an operation after confirmation (H12). Allowed for the initiator, the lock holder
     * and environment admins; the server's 403 reason is shown as is.
     */
    function confirmCancel(envId, executionId, $button) {
        Modals.confirm('Cancel operation',
            'Cancel this operation? VMs already started or stopped stay that way.',
            function() {
                if ($button) $button.prop('disabled', true);
                ApiClient.post(Config.API.operations.cancel(envId, executionId), {}, { suppressGlobalError: true })
                    .done(function(execution) {
                        Notifications.info('Operation cancelled');
                        if (modalExecutionId === executionId) updateProgressModal('cancelled');
                        if ($('.operation-history').length) loadHistoryContent(envId);
                    })
                    .fail(function(xhr) {
                        if ($button) $button.prop('disabled', false);
                        Notifications.error(xhr.responseJSON?.message || 'Could not cancel the operation');
                    });
            },
            { confirmText: 'Cancel operation', cancelText: 'Keep running', confirmClass: 'btn-danger' });
    }

    function publishOperationStatus(envId, execution) {
        if (typeof window === 'undefined' || typeof CustomEvent === 'undefined') {
            return;
        }
        window.dispatchEvent(new CustomEvent('vm-operation-status', {
            detail: { envId, execution }
        }));
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
                    const statusConfig = Config.STATUS.operation[exec.status] || { class: 'text-secondary', icon: 'fa-question', label: exec.status };
                    const duration = exec.completedAt && exec.startedAt ?
                        Utils.formatDuration(new Date(exec.completedAt) - new Date(exec.startedAt)) : '-';
                    const skipped = Number(exec.skippedTargets) || 0;
                    const running = RUNNING_STATUSES.includes(exec.status);
                    const action = running
                        ? `<button type="button" class="btn btn-sm btn-outline-danger" data-op-cancel
                                   data-env-id="${Utils.escapeHtml(envId)}" data-execution-id="${Utils.escapeHtml(exec.executionId)}">Cancel</button>`
                        : '';

                    return `
                        <tr data-execution-id="${Utils.escapeHtml(exec.executionId)}">
                            <td>${Utils.formatRelativeTime(exec.startedAt)}</td>
                            <td><span class="badge bg-secondary">${Utils.escapeHtml(exec.operationType)}</span></td>
                            <td>
                                <span class="${statusConfig.class}" data-col="status">
                                    <i class="fas ${statusConfig.icon} me-1"></i>${Utils.escapeHtml(statusConfig.label || exec.status)}
                                </span>${skipped > 0 ? ` <span class="text-muted small">· skipped ${skipped}</span>` : ''}
                            </td>
                            <td>${duration}</td>
                            <td>${Utils.escapeHtml(exec.initiatedByDisplayName || exec.initiatedBy || '-')}</td>
                            <td class="text-end">${action}</td>
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
                                <th class="text-end"><span class="visually-hidden">Actions</span></th>
                            </tr>
                        </thead>
                        <tbody>
                            ${rows}
                        </tbody>
                    </table>
                `);
                $('.operation-history').off('click', '[data-op-cancel]').on('click', '[data-op-cancel]', function() {
                    const $btn = $(this);
                    confirmCancel($btn.data('env-id'), $btn.data('execution-id'), $btn);
                });
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
        getHistory,
        stopAllPolling,
        trackedExecutionIds,
        hasRunningOperation,
        reopenProgress
    };
})();

// The operation banner's Details button re-opens the progress dialog (E13-T07).
$(document).on('click', '#view-operation-details', () => VmOperations.reopenProgress());

