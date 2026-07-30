/**
 * Automation Rules Feature Module
 * Admin / Env Admin view for managing calendar-schedule and access-grant/lock-acquire
 * automation rules that start or stop an environment.
 */
const AutomationRules = (function() {
    'use strict';

    const DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

    // State
    let rules = [];
    let environments = [];
    let scopeOptionsCache = {}; // environmentId -> { groups: [...], vms: [...] }
    let modalInstance = null;
    let editingRuleId = null;
    const filters = { search: '', environmentId: '', triggerType: '', status: '' };
    let currentPage = 1;
    const PAGE_SIZE = 10;

    // ============= Load =============

    function load() {
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }

        showLoading();
        fetchInitialData();
    }

    function showLoading() {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="height: 200px;">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
            </div>
        `);
    }

    function showError(message) {
        $('#content-area').html(`<div class="alert alert-danger m-3">${Utils.escapeHtml(message)}</div>`);
    }

    async function fetchInitialData() {
        try {
            const [envList, ruleList] = await Promise.all([
                apiGet(Config.API.environments.list),
                apiGet(Config.API.automationRules.list())
            ]);
            environments = envList || [];
            rules = ruleList || [];
            render();
        } catch (error) {
            console.error('Failed to load automation rules:', error);
            if (error.status === 403) {
                showError('Access denied. You do not have permission to manage automation rules.');
            } else {
                showError('Failed to load automation rules. Please try again.');
            }
        }
    }

    async function refreshRules() {
        rules = await apiGet(Config.API.automationRules.list()) || [];
        renderContent();
    }

    function apiGet(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url).done(resolve).fail(reject);
        });
    }

    // ============= Render =============

    function render() {
        $('#content-area').html(`
            <div class="ar-container" id="automation-rules-view">
                <div class="content-header d-flex justify-content-between align-items-end flex-wrap gap-2">
                    <div>
                        <h1>Automation Rules</h1>
                        <p>Recurring schedules and access-triggered actions that start or stop environments automatically. A rule never breaks an active lock — if the environment is locked or mid-operation, it is skipped and the owner is notified.</p>
                    </div>
                    <button class="btn btn-primary" id="ar-new-rule-btn">
                        <i class="fas fa-plus"></i> New Rule
                    </button>
                </div>
                <div id="ar-content"></div>
            </div>
            <div id="ar-modal-container"></div>
        `);

        $('#ar-new-rule-btn').on('click', () => openRuleModal(null));
        renderContent();
    }

    function renderContent() {
        $('#ar-content').html(`
            ${renderStatCards()}
            ${renderFiltersBar()}
            <div class="card ar-table-card mb-3">
                <div class="card-body ar-table-card-body">
                    <div class="ar-table-wrapper" id="ar-table-wrapper"></div>
                    <div id="ar-pagination" class="pagination-bar-wrap"></div>
                </div>
            </div>
            <div class="card">
                <div class="card-body">
                    <h5 class="mb-3">Recent Rule Activity</h5>
                    ${renderActivityFeed()}
                </div>
            </div>
        `);
        bindFilterEvents();
        renderTableAndPagination();
    }

    /**
     * Re-renders only the table body + pagination bar (not the filters bar above it),
     * so the search input never loses focus mid-keystroke.
     */
    function renderTableAndPagination() {
        const filtered = getFilteredRules();
        const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
        currentPage = Math.min(Math.max(currentPage, 1), totalPages);
        const pageRows = filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

        $('#ar-table-wrapper').html(renderRulesTable(pageRows, filtered.length));
        bindTableEvents();

        Pagination.renderNumbered('#ar-pagination', {
            page: currentPage,
            totalItems: filtered.length,
            pageSize: PAGE_SIZE,
            itemLabel: 'rules',
            bindAncestor: '#ar-content',
            onPageChange: function(page) {
                currentPage = page;
                renderTableAndPagination();
            }
        });
    }

    function getFilteredRules() {
        const term = filters.search.trim().toLowerCase();
        return rules.filter(function(r) {
            if (term && !r.name.toLowerCase().includes(term)) return false;
            if (filters.environmentId && r.environmentId !== filters.environmentId) return false;
            if (filters.triggerType && r.triggerType !== filters.triggerType) return false;
            if (filters.status === 'enabled' && !r.enabled) return false;
            if (filters.status === 'disabled' && r.enabled) return false;
            return true;
        });
    }

    function renderFiltersBar() {
        const envOptions = environments.map(function(e) {
            return `<option value="${e.environmentId}" ${filters.environmentId === e.environmentId ? 'selected' : ''}>${Utils.escapeHtml(e.name)}</option>`;
        }).join('');

        return `
            <div class="ar-filters-bar">
                <div class="input-group">
                    <span class="input-group-text"><i class="fas fa-search"></i></span>
                    <input type="text" class="form-control" id="ar-filter-search"
                           placeholder="Search rules..." value="${Utils.escapeHtml(filters.search)}">
                </div>
                <select class="form-select" id="ar-filter-environment">
                    <option value="">All Environments</option>
                    ${envOptions}
                </select>
                <select class="form-select" id="ar-filter-type">
                    <option value="">All Types</option>
                    <option value="SCHEDULE" ${filters.triggerType === 'SCHEDULE' ? 'selected' : ''}>Schedule</option>
                    <option value="ACCESS_GRANT" ${filters.triggerType === 'ACCESS_GRANT' ? 'selected' : ''}>Access Grant</option>
                </select>
                <select class="form-select" id="ar-filter-status">
                    <option value="">All Status</option>
                    <option value="enabled" ${filters.status === 'enabled' ? 'selected' : ''}>Enabled</option>
                    <option value="disabled" ${filters.status === 'disabled' ? 'selected' : ''}>Disabled</option>
                </select>
            </div>
        `;
    }

    function bindFilterEvents() {
        $('#ar-filter-search').off('input').on('input', function() {
            filters.search = $(this).val();
            currentPage = 1;
            renderTableAndPagination();
        });
        $('#ar-filter-environment').off('change').on('change', function() {
            filters.environmentId = $(this).val();
            currentPage = 1;
            renderTableAndPagination();
        });
        $('#ar-filter-type').off('change').on('change', function() {
            filters.triggerType = $(this).val();
            currentPage = 1;
            renderTableAndPagination();
        });
        $('#ar-filter-status').off('change').on('change', function() {
            filters.status = $(this).val();
            currentPage = 1;
            renderTableAndPagination();
        });
    }

    function renderStatCards() {
        const active = rules.filter(r => r.enabled).length;
        const skippedLocked = rules.filter(r => r.lastRunStatus === 'SKIPPED').length;
        const environmentsCovered = new Set(rules.map(r => r.environmentId)).size;

        return `
            <div class="ar-stat-grid">
                <div class="metric-card">
                    <div class="metric-value">${active}</div>
                    <div class="metric-label-hint">ACTIVE RULES (of ${rules.length})</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${skippedLocked}</div>
                    <div class="metric-label-hint">LAST RUN SKIPPED (LOCKED)</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${environmentsCovered}</div>
                    <div class="metric-label-hint">ENVIRONMENTS COVERED</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${rules.length - active}</div>
                    <div class="metric-label-hint">DISABLED RULES</div>
                </div>
            </div>
        `;
    }

    function renderRulesTable(pageRows, totalCount) {
        let rows;
        if (totalCount === 0) {
            rows = `
                <tr class="ar-empty-row">
                    <td colspan="7">
                        <div class="empty-state">
                            <i class="fas fa-bolt fa-2x mb-2 d-block text-muted opacity-50"></i>
                            No automation rules yet. Click "New Rule" to create a calendar schedule or an access-grant trigger.
                        </div>
                    </td>
                </tr>
            `;
        } else if (pageRows.length === 0) {
            rows = `
                <tr class="ar-empty-row">
                    <td colspan="7">
                        <div class="empty-state">
                            <i class="fas fa-filter fa-2x mb-2 d-block text-muted opacity-50"></i>
                            No rules match the current filters.
                        </div>
                    </td>
                </tr>
            `;
        } else {
            rows = pageRows.map(rule => `
            <tr data-rule-id="${rule.ruleId}" class="${rule.enabled ? '' : 'text-muted'}">
                <td>
                    <div class="fw-semibold">${Utils.escapeHtml(rule.name)}</div>
                    ${rule.description ? `<div class="ar-row-desc">${Utils.escapeHtml(rule.description)}</div>` : ''}
                </td>
                <td>${typeBadge(rule)}</td>
                <td>${Utils.escapeHtml(scopeLabel(rule))}</td>
                <td>${Utils.escapeHtml(triggerSummary(rule))}</td>
                <td class="text-center">
                    <div class="form-check form-switch mb-0 d-inline-block">
                        <input class="form-check-input ar-toggle-enabled" type="checkbox" role="switch"
                               data-rule-id="${rule.ruleId}" ${rule.enabled ? 'checked' : ''}>
                    </div>
                </td>
                <td>
                    ${rule.lastRunAt ? Utils.formatDate(rule.lastRunAt) : '<span class="text-muted">Never run</span>'}
                    ${rule.lastRunStatus ? `<div class="mt-1">${runStatusBadge(rule.lastRunStatus)}</div>` : ''}
                </td>
                <td class="text-end text-nowrap">
                    <button class="btn btn-action ar-edit-btn" data-rule-id="${rule.ruleId}" title="Edit"><i class="fas fa-pencil-alt"></i></button>
                    <button class="btn btn-action btn-danger ar-delete-btn" data-rule-id="${rule.ruleId}" title="Delete"><i class="fas fa-trash"></i></button>
                </td>
            </tr>
        `).join('');
        }

        return `
            <table class="table mb-0">
                <thead>
                    <tr>
                        <th>Rule</th><th>Type</th><th>Scope</th><th>Trigger</th><th class="text-center">Status</th><th>Last Run</th><th></th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        `;
    }

    function renderActivityFeed() {
        const recent = rules
            .filter(r => r.lastRunAt)
            .slice()
            .sort((a, b) => new Date(b.lastRunAt) - new Date(a.lastRunAt))
            .slice(0, 8);

        if (recent.length === 0) {
            return '<div class="text-muted">No automation activity yet.</div>';
        }

        return `
            <ul class="list-unstyled mb-0">
                ${recent.map(r => `
                    <li class="d-flex justify-content-between align-items-start py-2 border-bottom">
                        <div>
                            <strong>${Utils.escapeHtml(r.name)}</strong>
                            — ${Utils.escapeHtml(r.lastRunDetail || '')}
                            <div class="text-muted" style="font-size:.75rem">${Utils.escapeHtml(r.environmentName)}</div>
                        </div>
                        <div class="text-end" style="min-width: 140px">
                            ${runStatusBadge(r.lastRunStatus)}
                            <div class="text-muted" style="font-size:.72rem">${Utils.formatDate(r.lastRunAt)}</div>
                        </div>
                    </li>
                `).join('')}
            </ul>
        `;
    }

    function typeBadge(rule) {
        return rule.triggerType === 'SCHEDULE'
            ? '<span class="ar-type-badge ar-type-schedule"><i class="fas fa-clock"></i> Schedule</span>'
            : '<span class="ar-type-badge ar-type-access"><i class="fas fa-key"></i> Access Grant</span>';
    }

    function runStatusBadge(status) {
        if (status === 'SUCCESS') return '<span class="status-badge running">Success</span>';
        if (status === 'SKIPPED') return '<span class="status-badge stopping">Skipped</span>';
        if (status === 'FAILED') return '<span class="status-badge error">Failed</span>';
        return '';
    }

    function scopeLabel(rule) {
        if (rule.scopeType === 'ENVIRONMENT') {
            return `${rule.environmentName} (environment)`;
        }
        return `${rule.scopeName} (${rule.scopeType.toLowerCase()})`;
    }

    function triggerSummary(rule) {
        if (rule.triggerType === 'SCHEDULE') {
            const days = (rule.daysOfWeek || []).join(', ');
            const times = [
                rule.stopTime ? `Stop ${rule.stopTime}` : null,
                rule.startTime ? `Start ${rule.startTime}` : null
            ].filter(Boolean).join(' → ');
            return `${days} · ${times} · ${rule.timezone}`;
        }
        return rule.accessGrantMode === 'LOCK_ACQUIRE' ? 'On lock acquire' : 'On access approved';
    }

    // ============= Table events =============

    function bindTableEvents() {
        // Delegated from the stable #ar-table-wrapper (not recreated on filter/page
        // changes) and re-bound with .off() first, since renderTableAndPagination()
        // calls this on every filter/page change — without .off(), handlers would stack.
        const $wrapper = $('#ar-table-wrapper');

        $wrapper.off('click.arEdit', '.ar-edit-btn').on('click.arEdit', '.ar-edit-btn', function() {
            const rule = rules.find(r => r.ruleId === $(this).data('rule-id'));
            if (rule) openRuleModal(rule);
        });

        $wrapper.off('click.arDelete', '.ar-delete-btn').on('click.arDelete', '.ar-delete-btn', function() {
            const ruleId = $(this).data('rule-id');
            const rule = rules.find(r => r.ruleId === ruleId);
            if (!rule) return;
            DestructiveConfirm.confirmDeleteAutomationRule(rule.name, async function() {
                try {
                    await apiDelete(Config.API.automationRules.delete(ruleId));
                    Notifications.show('Automation rule deleted.', 'success');
                    await refreshRules();
                } catch (error) {
                    Notifications.showError('Failed to delete automation rule.');
                }
            });
        });

        $wrapper.off('change.arToggle', '.ar-toggle-enabled').on('change.arToggle', '.ar-toggle-enabled', async function() {
            const ruleId = $(this).data('rule-id');
            const enabled = $(this).is(':checked');
            try {
                await apiPatch(Config.API.automationRules.setEnabled(ruleId), { enabled });
                await refreshRules();
            } catch (error) {
                Notifications.showError('Failed to update rule status.');
                $(this).prop('checked', !enabled);
            }
        });
    }

    function apiDelete(url) {
        return new Promise((resolve, reject) => {
            ApiClient.delete(url).done(resolve).fail(reject);
        });
    }

    function apiPatch(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.patch(url, data).done(resolve).fail(reject);
        });
    }

    function apiPost(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.post(url, data).done(resolve).fail(reject);
        });
    }

    function apiPut(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.put(url, data).done(resolve).fail(reject);
        });
    }

    // ============= Create / Edit modal =============

    function openRuleModal(rule) {
        editingRuleId = rule ? rule.ruleId : null;
        $('#ar-modal-container').html(buildModalHtml(rule));
        modalInstance = new bootstrap.Modal(document.getElementById('automationRuleModal'));
        bindModalEvents(rule);
        modalInstance.show();

        // Load scope options for the initially-selected environment
        const envId = rule ? rule.environmentId : (environments[0] && environments[0].environmentId);
        if (envId) {
            loadScopeOptions(envId).then(() => populateScopeSelect(rule));
        }
    }

    function buildModalHtml(rule) {
        const isSchedule = !rule || rule.triggerType === 'SCHEDULE';
        const isAccessGrant = rule && rule.triggerType === 'ACCESS_GRANT';
        const scopeType = rule ? rule.scopeType : 'ENVIRONMENT';
        const selectedDays = rule ? (rule.daysOfWeek || []) : ['MON', 'TUE', 'WED', 'THU', 'FRI'];
        const accessGrantMode = rule ? rule.accessGrantMode : 'LOCK_ACQUIRE';

        const envOptions = environments.map(e =>
            `<option value="${e.environmentId}" ${rule && rule.environmentId === e.environmentId ? 'selected' : ''}>${Utils.escapeHtml(e.name)}</option>`
        ).join('');

        const dayPills = DAY_ORDER.map(day => `
            <span class="ar-day-pill ${selectedDays.includes(day) ? 'on' : ''}" data-day="${day}">${day.charAt(0) + day.slice(1).toLowerCase()}</span>
        `).join('');

        const tzOptions = Config.IANA_TIMEZONES.map(tz =>
            `<option value="${tz}" ${rule && rule.timezone === tz ? 'selected' : ''}>${tz}</option>`
        ).join('');

        return `
            <div class="modal fade" id="automationRuleModal" tabindex="-1" aria-labelledby="automationRuleModalLabel">
                <div class="modal-dialog modal-lg modal-dialog-scrollable">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="automationRuleModalLabel">${rule ? 'Edit Automation Rule' : 'New Automation Rule'}</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                        </div>
                        <div class="modal-body">
                            <form id="ar-form" novalidate>
                                <div class="mb-3">
                                    <label class="form-label">Name <span class="text-danger">*</span></label>
                                    <input type="text" class="form-control" id="ar-name" required maxlength="255" value="${rule ? Utils.escapeHtml(rule.name) : ''}">
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Description</label>
                                    <input type="text" class="form-control" id="ar-description" maxlength="1000" value="${rule ? Utils.escapeHtml(rule.description || '') : ''}">
                                </div>

                                <div class="mb-3">
                                    <label class="form-label">Trigger type</label>
                                    <div class="row gap-cards">
                                        <div class="col-md-6">
                                            <div class="ar-choice-card ${isSchedule ? 'selected' : ''}" data-trigger-type="SCHEDULE">
                                                <div class="ar-choice-top"><i class="fas fa-clock"></i> Calendar Schedule</div>
                                                <p class="mb-0 text-muted" style="font-size:.78rem">Run on a recurring day-of-week and time window.</p>
                                            </div>
                                        </div>
                                        <div class="col-md-6">
                                            <div class="ar-choice-card ${isAccessGrant ? 'selected' : ''}" data-trigger-type="ACCESS_GRANT">
                                                <div class="ar-choice-top"><i class="fas fa-key"></i> Access Grant / Lock Acquire</div>
                                                <p class="mb-0 text-muted" style="font-size:.78rem">Run the moment a user starts a session or is granted access.</p>
                                            </div>
                                        </div>
                                    </div>
                                    <input type="hidden" id="ar-trigger-type" value="${rule ? rule.triggerType : 'SCHEDULE'}">
                                </div>

                                <div class="row mb-3">
                                    <div class="col-md-6">
                                        <label class="form-label">Environment <span class="text-danger">*</span></label>
                                        <select class="form-select" id="ar-environment" ${rule ? 'disabled' : ''} required>
                                            ${envOptions}
                                        </select>
                                    </div>
                                    <div class="col-md-6">
                                        <label class="form-label">Scope</label>
                                        <select class="form-select" id="ar-scope-type">
                                            <option value="ENVIRONMENT" ${scopeType === 'ENVIRONMENT' ? 'selected' : ''}>Entire environment</option>
                                            <option value="GROUP" ${scopeType === 'GROUP' ? 'selected' : ''}>Specific group</option>
                                            <option value="VM" ${scopeType === 'VM' ? 'selected' : ''}>Specific VM</option>
                                        </select>
                                    </div>
                                </div>
                                <div class="mb-3" id="ar-scope-id-wrap" style="display:none">
                                    <label class="form-label">Select target</label>
                                    <select class="form-select" id="ar-scope-id"></select>
                                </div>

                                <div id="ar-schedule-fields" style="display:${isSchedule ? 'block' : 'none'}">
                                    <div class="mb-3">
                                        <label class="form-label">Days of week</label>
                                        <div class="d-flex gap-2 flex-wrap">${dayPills}</div>
                                    </div>
                                    <div class="row mb-3">
                                        <div class="col-md-4">
                                            <label class="form-label">Stop at</label>
                                            <input type="time" class="form-control" id="ar-stop-time" value="${rule ? (rule.stopTime || '') : '20:00'}">
                                        </div>
                                        <div class="col-md-4">
                                            <label class="form-label">Start at</label>
                                            <input type="time" class="form-control" id="ar-start-time" value="${rule ? (rule.startTime || '') : '08:00'}">
                                        </div>
                                        <div class="col-md-4">
                                            <label class="form-label">Timezone</label>
                                            <select class="form-select" id="ar-timezone">${tzOptions}</select>
                                        </div>
                                    </div>
                                </div>

                                <div id="ar-access-grant-fields" style="display:${isAccessGrant ? 'block' : 'none'}">
                                    <div class="mb-3">
                                        <label class="form-label">Fire when</label>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-lock" value="LOCK_ACQUIRE" ${accessGrantMode === 'LOCK_ACQUIRE' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-mode-lock">A user acquires the environment lock (recommended — starts VMs right when someone begins working)</label>
                                        </div>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-approved" value="ACCESS_APPROVED" ${accessGrantMode === 'ACCESS_APPROVED' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-mode-approved">An access request is approved / access is granted</label>
                                        </div>
                                    </div>
                                </div>

                                <div class="form-check mb-3">
                                    <input class="form-check-input" type="checkbox" id="ar-skip-already" ${!rule || rule.skipIfAlreadyInTargetState ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-skip-already">Skip VMs already in the target state</label>
                                </div>
                                <div class="form-check mb-3">
                                    <input class="form-check-input" type="checkbox" id="ar-enabled" ${!rule || rule.enabled ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-enabled">Enabled</label>
                                </div>

                                <div class="alert alert-light border d-flex gap-2 mb-0">
                                    <i class="fas fa-circle-info text-primary mt-1"></i>
                                    <div style="font-size:.8rem">
                                        <strong>Conflict handling.</strong> If the environment is locked by another user or already has an operation in progress, this rule is skipped — the lock holder is notified and the rule retries at its next trigger. Rules never break an active lock.
                                    </div>
                                </div>
                            </form>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="ar-save-btn">${rule ? 'Save Changes' : 'Create Rule'}</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    function bindModalEvents(rule) {
        $('.ar-choice-card').on('click', function() {
            $('.ar-choice-card').removeClass('selected');
            $(this).addClass('selected');
            const triggerType = $(this).data('trigger-type');
            $('#ar-trigger-type').val(triggerType);
            $('#ar-schedule-fields').toggle(triggerType === 'SCHEDULE');
            $('#ar-access-grant-fields').toggle(triggerType === 'ACCESS_GRANT');
        });

        $('.ar-day-pill').on('click', function() {
            $(this).toggleClass('on');
        });

        $('#ar-scope-type').on('change', function() {
            const scopeType = $(this).val();
            $('#ar-scope-id-wrap').toggle(scopeType !== 'ENVIRONMENT');
            populateScopeSelect(rule);
        });

        $('#ar-environment').on('change', function() {
            loadScopeOptions($(this).val()).then(() => populateScopeSelect(null));
        });

        $('#ar-save-btn').on('click', () => submitForm(rule));
    }

    async function loadScopeOptions(environmentId) {
        if (!environmentId || scopeOptionsCache[environmentId]) return;
        try {
            const [groups, vms] = await Promise.all([
                apiGet(Config.API.groups.list(environmentId)),
                apiGet(Config.API.vms.list(environmentId))
            ]);
            scopeOptionsCache[environmentId] = { groups: groups || [], vms: vms || [] };
        } catch (error) {
            scopeOptionsCache[environmentId] = { groups: [], vms: [] };
        }
    }

    function populateScopeSelect(rule) {
        const environmentId = $('#ar-environment').val();
        const scopeType = $('#ar-scope-type').val();
        const options = scopeOptionsCache[environmentId] || { groups: [], vms: [] };
        const $select = $('#ar-scope-id');

        $('#ar-scope-id-wrap').toggle(scopeType !== 'ENVIRONMENT');

        if (scopeType === 'GROUP') {
            $select.html(options.groups.map(g =>
                `<option value="${g.groupId}" ${rule && rule.scopeId === g.groupId ? 'selected' : ''}>${Utils.escapeHtml(g.displayName || g.name)}</option>`
            ).join(''));
        } else if (scopeType === 'VM') {
            $select.html(options.vms.map(v =>
                `<option value="${v.vmId}" ${rule && rule.scopeId === v.vmId ? 'selected' : ''}>${Utils.escapeHtml(v.displayName || v.name)}</option>`
            ).join(''));
        }
    }

    async function submitForm(rule) {
        const triggerType = $('#ar-trigger-type').val();
        const scopeType = $('#ar-scope-type').val();
        const name = $('#ar-name').val().trim();

        if (!name) {
            Notifications.showError('Name is required.');
            return;
        }

        const dto = {
            environmentId: $('#ar-environment').val(),
            name: name,
            description: $('#ar-description').val().trim(),
            scopeType: scopeType,
            scopeId: scopeType === 'ENVIRONMENT' ? null : $('#ar-scope-id').val(),
            triggerType: triggerType,
            skipIfAlreadyInTargetState: $('#ar-skip-already').is(':checked'),
            enabled: $('#ar-enabled').is(':checked')
        };

        if (triggerType === 'SCHEDULE') {
            dto.daysOfWeek = $('.ar-day-pill.on').map(function() { return $(this).data('day'); }).get();
            dto.stopTime = $('#ar-stop-time').val() || null;
            dto.startTime = $('#ar-start-time').val() || null;
            dto.timezone = $('#ar-timezone').val();
        } else {
            dto.accessGrantMode = $('input[name="ar-access-grant-mode"]:checked').val();
        }

        try {
            if (rule) {
                await apiPut(Config.API.automationRules.update(rule.ruleId), dto);
                Notifications.show('Automation rule updated.', 'success');
            } else {
                await apiPost(Config.API.automationRules.create, dto);
                Notifications.show('Automation rule created.', 'success');
            }
            modalInstance.hide();
            await refreshRules();
        } catch (error) {
            const message = (error.responseJSON && error.responseJSON.message) || 'Failed to save automation rule.';
            Notifications.showError(message);
        }
    }

    return {
        load
    };
})();

window.AutomationRules = AutomationRules;
