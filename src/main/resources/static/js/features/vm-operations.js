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
    function startEnvironment(envId, envName, leaseMinutes) {
        const body = { operationType: 'START' };
        if (leaseMinutes) body.leaseMinutes = leaseMinutes; // E15-T06: how long a lease-enabled environment runs
        return executeOperation(envId, body, `Starting ${envName}`);
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
    function startGroup(envId, groupId, groupName, noun, leaseMinutes) {
        const body = { operationType: 'START', groupIds: [groupId] };
        if (leaseMinutes) body.leaseMinutes = leaseMinutes; // U08-T02: the length chosen in the start dialog
        return executeOperation(envId, body, `Starting ${(noun || 'group').toLowerCase()} ${groupName || groupId}`);
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
    function startVm(envId, vmId, vmName, leaseMinutes) {
        const body = { operationType: 'START', vmIds: [vmId] };
        if (leaseMinutes) body.leaseMinutes = leaseMinutes; // U08-T02
        return executeOperation(envId, body, `Starting ${vmName || vmId}`);
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
        const ctx = {
            envId,
            opType: operationData.operationType,
            scope: operationData.vmIds ? { vmId: operationData.vmIds[0] }
                : operationData.groupIds ? { groupId: operationData.groupIds[0] } : null
        };
        return new Promise((resolve, reject) => {
            // Show progress modal
            showProgressModal(title, ctx);

            // Submit operation (errors are shown in the progress modal, with the server's reason)
            ApiClient.post(Config.API.operations.create(envId), operationData, { suppressGlobalError: true })
                .done(function(execution, _status, xhr) {
                    // A team quota that only warns (E25-T07): it runs, and the user is told.
                    const quotaWarning = xhr && xhr.getResponseHeader ? xhr.getResponseHeader('X-Quota-Warning') : null;
                    if (quotaWarning) {
                        Notifications.show(decodeURIComponent(quotaWarning.replace(/\+/g, ' ')), 'warning', 8000);
                    }
                    // Held for approval (E25-T04): nothing runs yet; say who decides and why.
                    if (execution && execution.approvalRequestId) {
                        Modals.hide('operationProgressModal');
                        const why = (execution.reasons || []).join('; ');
                        const who = (execution.approvers || []).join(', ');
                        Notifications.show(`Sent for approval${who ? ' to ' + who : ''}. It starts once approved.`
                            + (why ? ` Why: ${why}.` : ''), 'info', 8000);
                        resolve(execution);
                        if (typeof Approvals !== 'undefined') setTimeout(() => Approvals.refreshBanner(), 300);
                        return;
                    }
                    // Store operation info with explicit state tracking
                    activeOperations.set(execution.executionId, {
                        envId,
                        execution,
                        title,
                        ctx,
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
     * The progress dialog (U08-T03, OperationProgress.dc.html): "1:42 so far · usually about
     * 4 min" next to the title, a bar of VMs done, the live line ("3 of 6 running · Application
     * is starting; Web waits for it", #progress-status-text, which also carries errors and the
     * finished words) and one block per group (#vm-status-list, OperationStages). "Cancel the
     * rest" cancels what has not run yet; "Hide · keeps running" closes it and the banner takes over.
     */
    function showProgressModal(title, ctx) {
        const token = ++modalToken;
        modalExecutionId = null;
        hideBanner();
        Modals.show({
            id: 'operationProgressModal',
            title: title,
            size: 'lg',
            body: `
                <div class="operation-progress">
                    <div class="op-progress-bar" id="progress-bar" role="progressbar" aria-valuemin="0" aria-valuemax="0"
                         aria-valuenow="0" aria-label="${Utils.escapeHtml(title)}"><div class="op-progress-fill" id="progress-fill"></div></div>
                    <div class="op-progress-live" id="progress-status-text" aria-live="polite">Getting ready…</div>
                    <div class="op-stages" id="vm-status-list"></div>
                </div>
            `,
            buttons: [
                { text: 'Cancel the rest', class: 'btn-link op-cancel-rest', id: 'btn-cancel-operation' },
                { text: 'Hide · keeps running', class: 'btn-outline-secondary', id: 'btn-close-progress' }
            ],
            onShow: function() {
                // Shown once the operation exists (see executeOperation), hidden when it ends.
                $('#btn-cancel-operation').prop('hidden', !modalExecutionId);
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
        // The time sits next to the title, as on the board.
        $('#operationProgressModalLabel').after(`<span class="op-progress-time"><span class="mono" id="progress-elapsed">0:00</span><span id="progress-elapsed-word"> so far</span><span id="progress-usual"></span></span>`);
        // Bound now, not on "shown": the buttons work while the dialog is still fading in.
        $('#btn-cancel-operation').prop('hidden', true).on('click', function() {
            const $btn = $(this);
            confirmCancel($btn.data('env-id'), $btn.data('execution-id'), $btn);
        });
        // The operation keeps running server-side and keeps being polled after the dialog closes.
        $('#btn-close-progress').on('click', () => Modals.hideWhenReady(document.getElementById('operationProgressModal')));
        startElapsedTimer();
        loadUsualDuration(ctx, token);
    }

    /** "· usually about 4 min" from past runs of the same kind; nothing without history. */
    function loadUsualDuration(ctx, token) {
        if (!ctx || !ctx.envId || !Config.API.operations.estimates) return;
        ApiClient.get(Config.API.operations.estimates(ctx.envId, ctx.opType || 'START', ctx.scope), { suppressGlobalError: true })
            .done(function(estimate) {
                if (token !== modalToken || !estimate || !estimate.sampleCount || !estimate.avgEnvironmentSeconds) return;
                const minutes = Math.max(1, Math.round(estimate.avgEnvironmentSeconds / 60));
                $('#progress-usual').html(Utils.html` · usually about <span class="mono">${minutes} min</span>`);
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
        $('#operation-count').text(`${summary.completed} of ${summary.total} done`);
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
        showProgressModal(running.op.title, running.op.ctx);
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

    const LIGHT_CLASS = { R: 'pip on', B: 'pip busy', E: 'pip err', K: 'pip skip', S: 'pip' };

    /** A finished or failed dialog: the words in the live line, the bar's tone, Close for Hide. */
    function finishProgress(text, tone) {
        $('#progress-status-text').text(text).attr('data-tone', tone);
        $('#progress-bar').attr('data-tone', tone);
        if (tone !== 'done') $('#progress-fill').css('width', '100%');
        $('#btn-cancel-operation').prop('hidden', true);
        $('#btn-close-progress').text('Close');
        stopElapsedTimer();
    }

    /**
     * Update the progress dialog: status 'error' | 'timeout' | 'cancelled' with a message, or
     * 'progress' with the execution.
     */
    function updateProgressModal(status, message, execution) {
        if (status === 'error') {
            finishProgress(message == null ? '' : String(message), 'failed');
            return;
        }
        if (status === 'timeout') {
            finishProgress('Took too long to report back', 'failed');
            return;
        }
        if (status === 'cancelled') {
            finishProgress('Cancelled', 'cancelled');
            return;
        }
        if (!execution) return;

        const { percent } = summarizeExecution(execution);
        const live = OperationStages.liveLine(execution);
        // The time counts from the run's own start, also after the dialog is re-opened.
        const started = toMillis(execution.startedAt);
        if (started !== null) elapsedStart = Math.min(elapsedStart || started, started);
        $('#progress-elapsed').text(OperationStages.clock(Date.now() - elapsedStart));

        $('#progress-fill').css('width', `${percent}%`);
        $('#progress-bar').attr({ 'aria-valuemax': live.total, 'aria-valuenow': live.done,
            'aria-label': `${live.done} of ${live.total} VMs ${live.word}` });
        renderStages(execution);

        const finished = OperationStages.statusText(execution);
        $('#progress-result').remove();
        if (finished && (execution.status === 'PARTIAL_SUCCESS') && partlyDone(execution).failures.length) {
            $('#progress-status-text').after(`<div id="progress-result">${partialBlock(execution)}</div>`);
        }
        if (finished) {
            const tone = execution.status === 'COMPLETED' ? 'done' : execution.status === 'CANCELLED' ? 'cancelled'
                : execution.status === 'FAILED' ? 'failed' : 'problems';
            finishProgress(finished, tone);
            $('#progress-fill').css('width', '100%');
            const ended = toMillis(execution.completedAt);
            if (started !== null && ended !== null) {
                $('#progress-elapsed').text(OperationStages.clock(ended - started));
                $('#progress-elapsed-word').text(' in all');
                $('#progress-usual').empty();
            }
            return;
        }
        const $live = $('#progress-status-text').removeAttr('data-tone').empty();
        if (live.total) {
            $live.html(Utils.html`<b class="mono">${live.done}</b> of <b class="mono">${live.total}</b> ${live.word}`
                + (live.text ? Utils.html` · ${live.text}` : ''));
        } else {
            $live.text('Getting ready…');
        }
        $('#btn-cancel-operation').prop('hidden', !RUNNING_STATUSES.includes(execution.status));
    }

    /** What ran and what did not, from the steps: {opType, verb, didnt, done, total, failures}. */
    function partlyDone(execution) {
        const up = v => String(v || '').toUpperCase();
        const steps = (execution && execution.details || []).filter(s => !s.targetType || up(s.targetType) === 'VM');
        const opType = up(execution && execution.operationType) === 'STOP' ? 'STOP' : 'START';
        return {
            opType,
            verb: opType === 'STOP' ? 'Stopped' : 'Started',
            didnt: opType === 'STOP' ? 'stop' : 'start',
            done: steps.filter(s => up(s.status) === 'COMPLETED').length,
            total: steps.length,
            failures: steps.filter(s => up(s.status) === 'FAILED')
                .map(s => ({ vmId: s.targetId, name: s.targetName || s.targetId || 'A VM', reason: s.errorMessage || '' }))
        };
    }

    /** The environment as the confirm dialog and the history panel need it. */
    function envOf(execution) {
        return { environmentId: execution.environmentId, name: execution.environmentName, displayName: execution.environmentName, groups: [] };
    }

    /** "Started 5 of 6 VMs. pay-api-02 didn't start: …" with Retry pay-api-02 and See steps (U17-T03). */
    function partialBlock(execution) {
        const p = partlyDone(execution);
        const env = envOf(execution);
        return UiStates.partial({
            verb: p.verb, didnt: p.didnt, done: p.done, total: p.total,
            failures: p.failures.map(f => Object.assign({}, f, { onRetry: () => retryVm(env, p.opType, f) })),
            onSteps: () => seeSteps(env)
        });
    }

    /** Retry one VM: the progress dialog closes and the start (or stop) confirm opens for that VM. */
    function retryVm(env, opType, vm) {
        Modals.hide('operationProgressModal');
        if (typeof OperationConfirm === 'undefined') return;
        OperationConfirm.open({
            env, opType, scope: { level: 'vm', vmId: vm.vmId, name: vm.name },
            onConfirm: minutes => opType === 'STOP' ? stopVm(env.environmentId, vm.vmId, vm.name)
                : startVm(env.environmentId, vm.vmId, vm.name, minutes)
        });
    }

    function seeSteps(env) {
        Modals.hide('operationProgressModal');
        if (typeof OperationHistory !== 'undefined') OperationHistory.open(env);
    }

    function toMillis(ts) {
        if (ts === null || ts === undefined || ts === '') return null;
        const t = typeof ts === 'number' ? ts : Date.parse(ts);
        return Number.isNaN(t) ? null : t;
    }

    /** One block per group, in run order, with a line per VM. */
    function renderStages(execution) {
        const stages = OperationStages.stages(execution);
        $('#vm-status-list').html(stages.map(stage => Utils.html`
            <section class="op-stage" data-tone="${stage.tone}">
                <div class="op-stage-head"><b>${stage.order} · ${stage.name}</b><span class="op-stage-tag">${stage.tag}</span></div>
                ${Utils.raw(stage.vms.map(vm => Utils.html`<div class="op-stage-vm">
                    <span class="${LIGHT_CLASS[vm.light] || 'pip'}" aria-hidden="true"></span><span class="op-stage-vm-name">${vm.name}</span>
                    <span class="op-stage-state">${vm.state}</span></div>`).join(''))}
            </section>`).join(''));
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
                        // U17-T03: say what ran and why the rest did not, with See steps.
                        const p = partlyDone(execution);
                        if (p.failures.length) {
                            const why = p.failures.map(f => `${f.name} didn't ${p.didnt}${f.reason ? `: ${f.reason.replace(/\.$/, '')}` : ''}.`).join(' ');
                            Notifications.warning(`${p.verb} ${p.done} of ${p.total} VMs. ${why}`, undefined,
                                { action: { label: 'See steps', onClick: () => seeSteps(envOf(execution)) } });
                        } else {
                            Notifications.warning('Done, but some VMs were skipped because a VM they wait for did not start.');
                        }
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
                        if (modalExecutionId === executionId) $('#progress-status-text').text('Reconnecting…');
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
    function confirmCancel(envId, executionId, $button, onDone) {
        Modals.confirm('Cancel operation',
            'Cancel this operation? VMs already started or stopped stay that way.',
            function() {
                if ($button) $button.prop('disabled', true);
                ApiClient.post(Config.API.operations.cancel(envId, executionId), {}, { suppressGlobalError: true })
                    .done(function(execution) {
                        Notifications.info('Operation cancelled');
                        if (modalExecutionId === executionId) updateProgressModal('cancelled');
                        if (onDone) onDone(execution);
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
        stopElapsedTimer();
        elapsedStart = Date.now();
        const tick = () => $('#progress-elapsed').text(OperationStages.clock(Date.now() - elapsedStart));
        tick();
        elapsedInterval = setInterval(tick, 1000);
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
    // Public API
    return {
        startEnvironment,
        stopEnvironment,
        startGroup,
        stopGroup,
        startVm,
        stopVm,
        confirmCancel,
        stopAllPolling,
        trackedExecutionIds,
        hasRunningOperation,
        reopenProgress
    };
})();

// The operation banner's Details button re-opens the progress dialog (E13-T07).
$(document).on('click', '#view-operation-details', () => VmOperations.reopenProgress());

