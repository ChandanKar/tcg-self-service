/**
 * VM Self-Service Platform - Configuration
 * API endpoints, constants, and settings
 */

const Config = (function() {
    'use strict';

    // API Base URL - change for production
    const API_BASE_URL = '/api/v1';

    // Authentication URLs
    const AUTH = {
        loginUrl: '/oauth2/authorization/azure',
        logoutUrl: '/logout',
        currentUser: `${API_BASE_URL}/users/me`
    };

    // API Endpoints
    const API = {
        // Authentication
        auth: {
            currentUser: `${API_BASE_URL}/users/me`,
            login: '/oauth2/authorization/azure',
            logout: '/logout'
        },

        // Dashboard
        dashboard: {
            summary: `${API_BASE_URL}/dashboard/summary`
        },

        // User Management
        users: {
            list: `${API_BASE_URL}/users`,
            myCost: `${API_BASE_URL}/users/me/cost`,
            notificationPreferences: `${API_BASE_URL}/users/me/notification-preferences`,
            myLeases: `${API_BASE_URL}/users/me/leases`,
            listAll: `${API_BASE_URL}/users?includeInactive=true`,
            create: `${API_BASE_URL}/users`,
            get: (id) => `${API_BASE_URL}/users/${id}`,
            updateRole: (id) => `${API_BASE_URL}/users/${id}/role`,
            setAdmin: (id) => `${API_BASE_URL}/users/${id}/admin`,
            setEnvAdmin: (id) => `${API_BASE_URL}/users/${id}/env-admin`,
            deactivate: (id) => `${API_BASE_URL}/users/${id}/deactivate`,
            reactivate: (id) => `${API_BASE_URL}/users/${id}/reactivate`,
            setPassword: (id) => `${API_BASE_URL}/users/${id}/password`,
            search: (query) => `${API_BASE_URL}/users/search?q=${encodeURIComponent(query)}`,
            admins: `${API_BASE_URL}/users/admins`,
            myProfile: `${API_BASE_URL}/users/me/profile`,
            myActivity: (limit = 25) => `${API_BASE_URL}/users/me/activity?limit=${limit}`
        },

        // Entra ID directory lookup (admin onboarding)
        directory: {
            search: (query, top) => `${API_BASE_URL}/directory/search?q=${encodeURIComponent(query)}`
                + (top ? `&top=${encodeURIComponent(top)}` : '')
        },

        // Environment Access
        access: {
            myEnvironments: `${API_BASE_URL}/users/me/access`,
            myAccessHistory: (days = 30) => `${API_BASE_URL}/users/me/access/history?days=${days}`,
            environmentAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
            grantAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
            revokeAccess: (envId, userId) => `${API_BASE_URL}/environments/${envId}/access/${userId}`,
            // Scoped grants (environment or group), keyed by accessId
            accessGrants: `${API_BASE_URL}/access-grants`,
            updateGrant: (accessId) => `${API_BASE_URL}/access-grants/${accessId}`,
            revokeGrant: (accessId) => `${API_BASE_URL}/access-grants/${accessId}`,
            requestAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access-requests`,
            myRequests: `${API_BASE_URL}/access-requests/my`,
            pendingRequests: `${API_BASE_URL}/access-requests/pending`,
            getRequest: (id) => `${API_BASE_URL}/access-requests/${id}`,
            approveRequest: (id) => `${API_BASE_URL}/access-requests/${id}/approve`,
            denyRequest: (id) => `${API_BASE_URL}/access-requests/${id}/deny`,
            cancelRequest: (id) => `${API_BASE_URL}/access-requests/${id}`,
            environmentRequests: (envId) => `${API_BASE_URL}/environments/${envId}/access-requests`
        },

        // Environments
        environments: {
            list: `${API_BASE_URL}/environments`,
            page: (page = 0, size = 10, search = '') =>
                `${API_BASE_URL}/environments/page?page=${page}&size=${size}${search ? '&search=' + encodeURIComponent(search) : ''}`,
            available: `${API_BASE_URL}/environments/available`,
            get: (id) => `${API_BASE_URL}/environments/${id}`,
            insights: (id) => `${API_BASE_URL}/environments/${id}/insights`,
            cost: (id) => `${API_BASE_URL}/environments/${encodeURIComponent(id)}/cost`,
            eksWorkloadCost: (id, days = 7) => `${API_BASE_URL}/environments/${encodeURIComponent(id)}/eks-workload-cost?days=${days}`,
            create: `${API_BASE_URL}/environments`,
            update: (id) => `${API_BASE_URL}/environments/${id}`,
            delete: (id) => `${API_BASE_URL}/environments/${id}`,
            reactivate: (id) => `${API_BASE_URL}/environments/${id}/reactivate`,
            notifyStop: (id) => `${API_BASE_URL}/environments/${id}/notify-stop`,
            discoverEks: `${API_BASE_URL}/environments/discover/eks`
        },

        // VM Groups
        groups: {
            list: (envId) => `${API_BASE_URL}/environments/${envId}/groups`,
            get: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
            create: (envId) => `${API_BASE_URL}/environments/${envId}/groups`,
            update: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
            delete: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
            startOrder: (envId) => `${API_BASE_URL}/environments/${envId}/groups/start-order`
        },

        // VMs
        vms: {
            list: (envId) => `${API_BASE_URL}/environments/${envId}/vms`,
            get: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`,
            register: (envId) => `${API_BASE_URL}/environments/${envId}/vms`,
            update: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`,
            delete: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`,
            inventory: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/inventory`,
            refreshInventory: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/inventory/refresh`,
            metrics: (envId, vmId, window = '1h', period = 300) =>
                `${API_BASE_URL}/environments/${envId}/vms/${vmId}/metrics?window=${encodeURIComponent(window)}&period=${encodeURIComponent(period)}`,
            utilizationSummary: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/utilization-summary`,
            groupPage: (envId, groupId, page = 0, size = 25) =>
                `${API_BASE_URL}/environments/${envId}/vms/${groupId}/page?page=${page}&size=${size}`,
            // Registry review (E09-T08/T06/T09): state is DRIFT, PENDING or INACTIVE
            review: (envId, state, page = 0, size = 25) =>
                `${API_BASE_URL}/environments/${envId}/vms/review?state=${encodeURIComponent(state)}&page=${page}&size=${size}`,
            reviewCounts: (envId) => `${API_BASE_URL}/environments/${envId}/vms/review/counts`,
            move: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/move`,
            reactivate: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/reactivate`,
            acknowledge: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}/acknowledge`
        },

        // Teams (E23)
        // Operation approvals and their policies (E25)
        // Environment templates and requests (E32-T07)
        templates: {
            list: (includeRetired = false) => `${API_BASE_URL}/templates${includeRetired ? '?includeRetired=true' : ''}`,
            all: () => `${API_BASE_URL}/templates/all`,
            create: () => `${API_BASE_URL}/templates`,
            item: (id) => `${API_BASE_URL}/templates/${encodeURIComponent(id)}`
        },
        provisioning: {
            create: () => `${API_BASE_URL}/provisioning-requests`,
            mine: (page = 0, size = 100) => `${API_BASE_URL}/provisioning-requests/mine?page=${page}&size=${size}`,
            pending: (page = 0, size = 100) => `${API_BASE_URL}/provisioning-requests/pending?page=${page}&size=${size}`,
            approve: (id) => `${API_BASE_URL}/provisioning-requests/${encodeURIComponent(id)}/approve`,
            reject: (id) => `${API_BASE_URL}/provisioning-requests/${encodeURIComponent(id)}/reject`,
            cancel: (id) => `${API_BASE_URL}/provisioning-requests/${encodeURIComponent(id)}/cancel`,
            extend: (id) => `${API_BASE_URL}/provisioning-requests/${encodeURIComponent(id)}/extend-ttl`,
            destroy: (id) => `${API_BASE_URL}/provisioning-requests/${encodeURIComponent(id)}/destroy`
        },
        approvals: {
            list: (view) => `${API_BASE_URL}/approval-requests?view=${encodeURIComponent(view)}`,
            approve: (id) => `${API_BASE_URL}/approval-requests/${encodeURIComponent(id)}/approve`,
            deny: (id) => `${API_BASE_URL}/approval-requests/${encodeURIComponent(id)}/deny`,
            cancel: (id) => `${API_BASE_URL}/approval-requests/${encodeURIComponent(id)}/cancel`
        },
        // Runtime settings (E29-T01), admin only
        settings: {
            list: `${API_BASE_URL}/settings`,
            item: (key) => `${API_BASE_URL}/settings/${encodeURIComponent(key)}`
        },

        // AWS account registry (E28-T07..T09), admin only
        awsAccounts: {
            list: `${API_BASE_URL}/aws-accounts`,
            item: (ref) => `${API_BASE_URL}/aws-accounts/${encodeURIComponent(ref)}`,
            verify: (ref) => `${API_BASE_URL}/aws-accounts/${encodeURIComponent(ref)}/verify`,
            candidates: `${API_BASE_URL}/aws-accounts/organization-candidates`,
            import: `${API_BASE_URL}/aws-accounts/import`,
            options: `${API_BASE_URL}/aws-accounts/options`
        },

        approvalPolicies: {
            list: (envId) => `${API_BASE_URL}/approval-policies` + (envId ? `?environmentId=${encodeURIComponent(envId)}` : ''),
            create: `${API_BASE_URL}/approval-policies`,
            item: (id) => `${API_BASE_URL}/approval-policies/${encodeURIComponent(id)}`,
            evaluate: `${API_BASE_URL}/approval-policies/evaluate`
        },
        // Jira tickets from recommendations and anomalies (E24-T07)
        integrations: {
            jiraStatus: `${API_BASE_URL}/integrations/jira/status`,
            jiraCreate: `${API_BASE_URL}/integrations/jira/tickets`,
            jiraTickets: (type, ids) => `${API_BASE_URL}/integrations/jira/tickets?sourceType=${encodeURIComponent(type)}`
                + `&sourceIds=${encodeURIComponent(ids.join(','))}`
        },
        // One-click Teams action links (E24-T04)
        chatActions: {
            item: (token) => `${API_BASE_URL}/chat-actions/${encodeURIComponent(token)}`
        },
        // Microsoft Teams channels per environment (E24-T02)
        chatChannels: {
            options: `${API_BASE_URL}/chat-channels/options`,
            list: (environmentId) => `${API_BASE_URL}/chat-channels?environmentId=${encodeURIComponent(environmentId)}`,
            create: `${API_BASE_URL}/chat-channels`,
            item: (id) => `${API_BASE_URL}/chat-channels/${encodeURIComponent(id)}`,
            test: (id) => `${API_BASE_URL}/chat-channels/${encodeURIComponent(id)}/test`
        },
        teams: {
            list: (includeInactive) => `${API_BASE_URL}/teams` + (includeInactive ? '?includeInactive=true' : ''),
            create: `${API_BASE_URL}/teams`,
            update: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}`,
            delete: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}`,
            entraGroups: (q) => `${API_BASE_URL}/teams/entra-groups?query=${encodeURIComponent(q)}`,
            entraGroup: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/entra-group`,
            sync: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/sync`,
            members: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/members`,
            member: (id, userId) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`,
            // Monthly quotas (E25-T07/T08)
            quotas: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/quotas`,
            quotaUsage: (id) => `${API_BASE_URL}/teams/${encodeURIComponent(id)}/quota-usage`,
            myQuotaUsage: `${API_BASE_URL}/teams/mine/quota-usage`,
            // Required allocation fields (E23-T04)
            allocationRules: `${API_BASE_URL}/environments/allocation-rules`,
            allocationGaps: `${API_BASE_URL}/environments/allocation-gaps`
        },

        // Waste sweeps (E22)
        waste: {
            summary: `${API_BASE_URL}/waste/summary`,
            findings: `${API_BASE_URL}/waste/findings`,
            dismiss: (id) => `${API_BASE_URL}/waste/findings/${encodeURIComponent(id)}/dismiss`,
            reopen: (id) => `${API_BASE_URL}/waste/findings/${encodeURIComponent(id)}/reopen`,
            sweep: `${API_BASE_URL}/waste/sweep`,
            export: `${API_BASE_URL}/waste/findings/export`,
            // Fix actions (E22-T04/T05)
            actions: `${API_BASE_URL}/waste/actions`,
            actionsConfig: `${API_BASE_URL}/waste/actions/config`,
            propose: (findingId) => `${API_BASE_URL}/waste/findings/${encodeURIComponent(findingId)}/actions`,
            approve: (id) => `${API_BASE_URL}/waste/actions/${encodeURIComponent(id)}/approve`,
            reject: (id) => `${API_BASE_URL}/waste/actions/${encodeURIComponent(id)}/reject`,
            rollback: (id) => `${API_BASE_URL}/waste/actions/${encodeURIComponent(id)}/rollback`
        },

        // Cost anomalies (E21)
        costAnomalies: {
            list: (days) => `${API_BASE_URL}/cost-anomalies?days=${days}&size=100`,
            forEnvironment: (envId, days) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/cost-anomalies?days=${days}`,
            feedback: (id) => `${API_BASE_URL}/cost-anomalies/${encodeURIComponent(id)}/feedback`,
            monitors: `${API_BASE_URL}/cost-anomalies/monitors`
        },

        // Budgets (E20)
        budgets: {
            config: `${API_BASE_URL}/budgets/config`,
            list: `${API_BASE_URL}/budgets`,
            create: `${API_BASE_URL}/budgets`,
            update: (id) => `${API_BASE_URL}/budgets/${encodeURIComponent(id)}`,
            delete: (id) => `${API_BASE_URL}/budgets/${encodeURIComponent(id)}`,
            forEnvironment: (envId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/budget`,
            actions: (status) => `${API_BASE_URL}/budgets/actions` + (status ? `?status=${status}` : ''),
            approve: (id) => `${API_BASE_URL}/budgets/actions/${encodeURIComponent(id)}/approve`,
            reject: (id) => `${API_BASE_URL}/budgets/actions/${encodeURIComponent(id)}/reject`,
            liftFreeze: (envId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/freeze/lift`
        },

        // Savings ledger and Cost Efficiency (E17)
        savings: {
            summary: `${API_BASE_URL}/savings/summary`,
            daily: `${API_BASE_URL}/savings/daily`,
            byEnvironment: `${API_BASE_URL}/savings/by-environment`,
            byTeam: `${API_BASE_URL}/savings/by-team`,
            trend: `${API_BASE_URL}/savings/efficiency/trend`,
            export: `${API_BASE_URL}/savings/export`,
            backfill: `${API_BASE_URL}/savings/backfill`
        },

        // Environment leases (E15)
        leases: {
            get: (envId) => `${API_BASE_URL}/environments/${envId}/lease`,
            policy: (envId) => `${API_BASE_URL}/environments/${envId}/lease-policy`,
            extend: (envId) => `${API_BASE_URL}/environments/${envId}/lease/extend`,
            extensionRequests: (envId) => `${API_BASE_URL}/environments/${envId}/lease/extension-requests`,
            approve: (requestId) => `${API_BASE_URL}/lease-extension-requests/${requestId}/approve`,
            deny: (requestId) => `${API_BASE_URL}/lease-extension-requests/${requestId}/deny`
        },

        // Idle auto-stop (E16)
        idleStop: {
            status: (envId) => `${API_BASE_URL}/environments/${envId}/idle-stop/status`,
            rules: (envId) => `${API_BASE_URL}/environments/${envId}/idle-stop/rules`,
            rule: (envId, ruleId) => `${API_BASE_URL}/environments/${envId}/idle-stop/rules/${ruleId}`,
            snooze: (envId) => `${API_BASE_URL}/environments/${envId}/idle-stop/snooze`
        },

        // VM Operations
        operations: {
            create: (envId) => `${API_BASE_URL}/environments/${envId}/operations`,
            list: (envId) => `${API_BASE_URL}/environments/${envId}/operations`,
            get: (envId, execId) => `${API_BASE_URL}/environments/${envId}/operations/${execId}`,
            cancel: (envId, execId) => `${API_BASE_URL}/environments/${envId}/operations/${execId}/cancel`,
            estimates: (envId, opType, scope) => {
                let url = `${API_BASE_URL}/environments/${envId}/operations/time-estimates?operationType=${opType}`;
                if (scope && scope.groupId) url += `&groupId=${scope.groupId}`;
                if (scope && scope.vmId)    url += `&vmId=${scope.vmId}`;
                return url;
            },
            // Cost of a start, or with extension=true of keeping running VMs up (E25-T01/T02)
            costPreview: (envId, scope, hours, extension) => {
                const params = new URLSearchParams();
                if (scope && scope.groupId) params.set('groupId', scope.groupId);
                if (scope && scope.vmId) params.set('vmId', scope.vmId);
                if (hours) params.set('hours', String(hours));
                if (extension) params.set('extension', 'true');
                const q = params.toString();
                return `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/operations/cost-preview${q ? '?' + q : ''}`;
            }
        },

        // Locks
        locks: {
            status: (envId) => `${API_BASE_URL}/environments/${envId}/lock`,
            extend: (envId) => `${API_BASE_URL}/environments/${envId}/lock/extend`,
            acquire: (envId) => `${API_BASE_URL}/environments/${envId}/lock/acquire`,
            release: (envId) => `${API_BASE_URL}/environments/${envId}/lock/release`,
            break: (envId) => `${API_BASE_URL}/environments/${envId}/lock/break`,
            history: (envId) => `${API_BASE_URL}/environments/${envId}/lock/history`
        },

        // Audit
        audit: {
            logs: `${API_BASE_URL}/audit/logs`,
            stats: `${API_BASE_URL}/audit/logs/stats`,
            allLogs: `${API_BASE_URL}/audit/logs`,
            myLogs: `${API_BASE_URL}/audit/logs/my`,
            recent: `${API_BASE_URL}/audit/logs/recent`,
            byEnvironment: (envId) => `${API_BASE_URL}/audit/logs/environment/${envId}`,
            byUser: (userId) => `${API_BASE_URL}/audit/logs/user/${userId}`,
            byTarget: (type, id) => `${API_BASE_URL}/audit/logs/target/${type}/${id}`,
            failures: `${API_BASE_URL}/audit/logs/failures`,
            report: `${API_BASE_URL}/audit/report`,
            lockReport: `${API_BASE_URL}/audit/report/locks`,
            vmReport: `${API_BASE_URL}/audit/report/vm-operations`,
            actions: `${API_BASE_URL}/audit/actions`
        },

        // Monitoring
        monitoring: {
            syncStatus: `${API_BASE_URL}/monitoring/sync-status`,
            triggerSync: `${API_BASE_URL}/monitoring/sync`,
            triggerEksSync: `${API_BASE_URL}/monitoring/sync/eks`,
            triggerEksSyncForEnvironment: (envId) => `${API_BASE_URL}/monitoring/sync/eks/${envId}`,
            triggerInventorySync: `${API_BASE_URL}/monitoring/inventory/sync`,
            triggerMetricsSync: `${API_BASE_URL}/monitoring/metrics/sync`,
            triggerMetricsArchive: `${API_BASE_URL}/monitoring/metrics/archive`,
            syncEnvironment: (envId) => `${API_BASE_URL}/monitoring/sync/environment/${envId}`,
            vmHistory: (vmId) => `${API_BASE_URL}/monitoring/vms/${vmId}/history`,
            stateChanges: `${API_BASE_URL}/monitoring/state-changes`,
            driftEvents: `${API_BASE_URL}/monitoring/drift-events`,
            driftCount: `${API_BASE_URL}/monitoring/drift-events/count`,
            driftReport: `${API_BASE_URL}/monitoring/drift-events/report`
        },

        // Notifications
        notifications: {
            list:       `${API_BASE_URL}/notifications`,
            unread:     `${API_BASE_URL}/notifications/unread`,
            count:      `${API_BASE_URL}/notifications/count`,
            markRead:   (id) => `${API_BASE_URL}/notifications/${id}/read`,
            markAllRead: `${API_BASE_URL}/notifications/read-all`,
            emailLog:   `${API_BASE_URL}/notifications/email-log`
        },

        // EC2 (AWS)
        ec2: {
            listInstances: (region, accountRef) => `/api/ec2/instances?region=${region}`
                + (accountRef ? `&accountRef=${encodeURIComponent(accountRef)}` : ''),
            registeredIds: `/api/ec2/registered-ids`
        },

        // API tokens and service accounts (E34); 404 while api-tokens.enabled is off
        apiTokens: {
            mine: `${API_BASE_URL}/me/api-tokens`,
            mineItem: (id) => `${API_BASE_URL}/me/api-tokens/${encodeURIComponent(id)}`,
            all: (page) => `${API_BASE_URL}/api-tokens?page=${page || 0}&size=50`,
            item: (id) => `${API_BASE_URL}/api-tokens/${encodeURIComponent(id)}`
        },
        serviceAccounts: {
            list: `${API_BASE_URL}/service-accounts`,
            item: (id) => `${API_BASE_URL}/service-accounts/${encodeURIComponent(id)}`,
            tokens: (id) => `${API_BASE_URL}/service-accounts/${encodeURIComponent(id)}/tokens`
        },

        // RDS databases as VMs (E33-T02); 404 while rds.enabled is off
        rds: {
            status: `${API_BASE_URL}/rds`,
            databases: (region, accountRef) => `${API_BASE_URL}/rds/databases?region=${encodeURIComponent(region)}`
                + (accountRef ? `&accountRef=${encodeURIComponent(accountRef)}` : '')
        },

        // Cost Management (admin only)
        costManagement: {
            summary: `${API_BASE_URL}/cost-management/summary`,
            spendByEnvironment: `${API_BASE_URL}/cost-management/spend-by-environment`,
            spendByVmType: `${API_BASE_URL}/cost-management/spend-by-vm-type`,
            spendByAccount: `${API_BASE_URL}/cost-management/spend-by-account`,
            eksWorkloadCost: (environmentId, days = 7) =>
                `${API_BASE_URL}/cost-management/eks-workload-cost?environmentId=${encodeURIComponent(environmentId)}&days=${days}`,
            spendTrend: (days = 90) => `${API_BASE_URL}/cost-management/spend-trend?days=${days}`,
            spendTrendByTeam: (days = 90) => `${API_BASE_URL}/cost-management/spend-trend-by-team?days=${days}`,
            idleWaste: (page = 0, size = 10) => `${API_BASE_URL}/cost-management/idle-waste?page=${page}&size=${size}`,
            rightsizing: (page = 0, size = 10, direction = '') => `${API_BASE_URL}/cost-management/rightsizing?page=${page}&size=${size}`
                + (direction ? `&direction=${encodeURIComponent(direction)}` : ''),
            vmDetail: (page = 0, size = 10) => `${API_BASE_URL}/cost-management/vm-detail?page=${page}&size=${size}`,
            idleWasteExport: () => `${API_BASE_URL}/cost-management/idle-waste/export`,
            rightsizingExport: () => `${API_BASE_URL}/cost-management/rightsizing/export`,
            applyRightsizing: () => `${API_BASE_URL}/cost-management/rightsizing/apply`,
            spotOpportunities: (page = 0, size = 25) => `${API_BASE_URL}/cost-management/spot-opportunities?page=${page}&size=${size}`,
            spotSummary: (envId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/spot-summary`,
            spotPolicy: (envId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/spot-policy`,
            spotExcluded: (envId, vmId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/vms/${encodeURIComponent(vmId)}/spot-excluded`,
            spotConvert: (envId, vmId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/vms/${encodeURIComponent(vmId)}/convert-to-spot`,
            spotRevert: (envId, vmId) => `${API_BASE_URL}/environments/${encodeURIComponent(envId)}/vms/${encodeURIComponent(vmId)}/revert-to-on-demand`,
            rightsizingPrecheck: (vmId, targetType) => `${API_BASE_URL}/cost-management/rightsizing/precheck?vmId=${encodeURIComponent(vmId)}&targetInstanceType=${encodeURIComponent(targetType)}`,
            rightsizingResize: (resizeId) => `${API_BASE_URL}/cost-management/rightsizing/resizes/${encodeURIComponent(resizeId)}`,
            // Chargeback and split rules (E23-T06..T08)
            chargeback: (month) => `${API_BASE_URL}/cost-management/chargeback?month=${encodeURIComponent(month)}`,
            chargebackFinalize: (month) => `${API_BASE_URL}/cost-management/chargeback/${encodeURIComponent(month)}/finalize`,
            chargebackExport: (month, format) =>
                `${API_BASE_URL}/cost-management/chargeback/export?month=${encodeURIComponent(month)}&format=${encodeURIComponent(format)}`,
            splitRules: `${API_BASE_URL}/cost-management/split-rules`,
            splitRule: (id) => `${API_BASE_URL}/cost-management/split-rules/${encodeURIComponent(id)}`,
            vmDetailExport: () => `${API_BASE_URL}/cost-management/vm-detail/export`,
            backfill: (days = 30) => `${API_BASE_URL}/cost-management/snapshots/backfill?days=${days}`,
            tagsReconcile: `${API_BASE_URL}/cost-management/tags/reconcile`,
            tagsStatus: `${API_BASE_URL}/cost-management/tags/status`,
            reconciliation: (days = 30) => `${API_BASE_URL}/cost-management/reconciliation?days=${days}`,
            reconciliationExport: (days = 90) => `${API_BASE_URL}/cost-management/reconciliation/export?days=${days}`,
            actualsBackfill: (days = 30) => `${API_BASE_URL}/cost-management/actuals/backfill?days=${days}`,
            reservationsCoverage: (days = 30) => `${API_BASE_URL}/cost-management/reservations/coverage?days=${days}`,
            commitmentInsights: `${API_BASE_URL}/cost-management/commitments/insights`,
            forecast: (historyDays = 30, forecastDays = 14) =>
                `${API_BASE_URL}/cost-management/forecast?historyDays=${historyDays}&forecastDays=${forecastDays}`,
            reservationsUtilization: `${API_BASE_URL}/cost-management/reservations/utilization`,
            setupStatus: `${API_BASE_URL}/cost-management/setup/status`,
            setupCheck: `${API_BASE_URL}/cost-management/setup/check`
        },

        // Automation Rules (admin / env admin)
        automationRules: {
            list: (environmentId) => environmentId
                ? `${API_BASE_URL}/automation-rules?environmentId=${encodeURIComponent(environmentId)}`
                : `${API_BASE_URL}/automation-rules`,
            get: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}`,
            create: `${API_BASE_URL}/automation-rules`,
            update: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}`,
            setEnabled: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}/enabled`,
            delete: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}`,
            // Schedule suggestion from usage (E19-T05)
            suggestion: (environmentId, timezone, weeks) => `${API_BASE_URL}/automation-rules/suggestion`
                + `?environmentId=${encodeURIComponent(environmentId)}&timezone=${encodeURIComponent(timezone)}&weeks=${weeks}`,
            // Next runs (E19-T04)
            preview: (ruleId) => `${API_BASE_URL}/automation-rules/preview` + (ruleId ? `?ruleId=${encodeURIComponent(ruleId)}` : ''),
            nextRuns: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}/next-runs`,
            // Overrides (E19-T03)
            overrides: (ruleId) => `${API_BASE_URL}/automation-rules/${ruleId}/overrides`,
            deleteOverride: (ruleId, overrideId) => `${API_BASE_URL}/automation-rules/${ruleId}/overrides/${overrideId}`
        },

        // Holiday calendars (E19-T02)
        holidayCalendars: {
            list: `${API_BASE_URL}/holiday-calendars`,
            create: `${API_BASE_URL}/holiday-calendars`,
            update: (id) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}`,
            delete: (id) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}`,
            dates: (id) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}/dates?size=500`,
            addDate: (id) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}/dates`,
            removeDate: (id, holidayId) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}/dates/${encodeURIComponent(holidayId)}`,
            import: (id) => `${API_BASE_URL}/holiday-calendars/${encodeURIComponent(id)}/import`
        }
    };

    // UI Settings
    const UI = {
        sidebarWidth: 260,
        sidebarCollapsedWidth: 60,
        topNavHeight: 60,
        animationDuration: 300,
        toastDuration: 3000,
        pollInterval: 10000,  // 10 seconds for status polling
        operationPollInterval: 2000  // 2 seconds for operation progress
    };

    // Status mappings
    const STATUS = {
        vm: {
            RUNNING: { label: 'Running', class: 'running', icon: 'fa-circle', color: '#10b981' },
            STOPPED: { label: 'Stopped', class: 'stopped', icon: 'fa-circle', color: '#4b5563' },
            STARTING: { label: 'Starting', class: 'pending', icon: 'fa-spinner fa-spin', color: '#f59e0b' },
            STOPPING: { label: 'Stopping', class: 'pending', icon: 'fa-spinner fa-spin', color: '#f59e0b' },
            UNKNOWN: { label: 'Unknown', class: 'partial', icon: 'fa-question-circle', color: '#6b7280' },
            ERROR: { label: 'Error', class: 'error', icon: 'fa-exclamation-circle', color: '#ef4444' }
        },
        lock: {
            LOCKED: { label: 'Locked', class: 'text-warning', icon: 'fa-lock' },
            UNLOCKED: { label: 'Unlocked', class: 'text-success', icon: 'fa-unlock' }
        },
        operation: {
            PENDING: { label: 'Pending', class: 'text-secondary', icon: 'fa-clock' },
            IN_PROGRESS: { label: 'In Progress', class: 'text-primary', icon: 'fa-spinner fa-spin' },
            COMPLETED: { label: 'Completed', class: 'text-success', icon: 'fa-check-circle' },
            PARTIAL_SUCCESS: { label: 'Completed with failures', class: 'text-warning', icon: 'fa-exclamation-triangle' },
            FAILED: { label: 'Failed', class: 'text-danger', icon: 'fa-times-circle' },
            CANCELLED: { label: 'Cancelled', class: 'text-secondary', icon: 'fa-ban' }
        },
        accessRequest: {
            PENDING: { label: 'Pending', class: 'bg-warning', icon: 'fa-clock' },
            APPROVED: { label: 'Approved', class: 'bg-success', icon: 'fa-check' },
            DENIED: { label: 'Denied', class: 'bg-danger', icon: 'fa-times' },
            CANCELLED: { label: 'Cancelled', class: 'bg-secondary', icon: 'fa-ban' }
        }
    };

    // VM Status Badge Configuration (TASK-025)
    // Used for rendering status badges with icons and animations
    const VM_STATUS_CONFIG = {
        RUNNING: {
            cssClass: 'running',
            icon: 'fa-play-circle',
            text: 'Running',
            spinning: false
        },
        STOPPED: {
            cssClass: 'stopped',
            icon: 'fa-stop-circle',
            text: 'Stopped',
            spinning: false
        },
        STARTING: {
            cssClass: 'starting',
            icon: 'fa-spinner',
            text: 'Starting...',
            spinning: true
        },
        STOPPING: {
            cssClass: 'stopping',
            icon: 'fa-spinner',
            text: 'Stopping...',
            spinning: true
        },
        ERROR: {
            cssClass: 'error',
            icon: 'fa-exclamation-triangle',
            text: 'Error',
            spinning: false
        },
        UNKNOWN: {
            cssClass: 'unknown',
            icon: 'fa-question-circle',
            text: 'Unknown',
            spinning: false
        }
    };

    /**
     * Render a VM status badge HTML
     * @param {string} status - VM status (RUNNING, STOPPED, etc.)
     * @returns {string} - HTML for the status badge
     */
    function renderStatusBadge(status) {
        const config = VM_STATUS_CONFIG[status] || VM_STATUS_CONFIG.UNKNOWN;
        const spinClass = config.spinning ? 'fa-spin' : '';

        return `
            <span class="status-badge ${config.cssClass}" role="status"
                  aria-label="VM status: ${config.text}">
                <i class="fas ${config.icon} ${spinClass}" aria-hidden="true"></i>
                <span class="status-text">${config.text}</span>
            </span>
        `;
    }

    // Cloud provider icons
    const CLOUD_ICONS = {
        AWS: { icon: 'fab fa-aws', color: '#FF9900', label: 'AWS' },
        AZURE: { icon: 'fab fa-microsoft', color: '#0078D4', label: 'Azure' },
        GCP: { icon: 'fab fa-google', color: '#4285F4', label: 'GCP' },
        OCI: { icon: 'fas fa-cloud', color: '#F80000', label: 'Oracle Cloud' },
        AWS_RDS: { icon: 'fas fa-database', color: '#3B48CC', label: 'RDS' }
    };

    // Access levels
    const ACCESS_LEVELS = {
        VIEWER: { label: 'Viewer', description: 'Can view environment details', color: 'secondary' },
        USER: { label: 'User', description: 'Can start/stop VMs', color: 'primary' },
        ADMIN: { label: 'Admin', description: 'Full environment control', color: 'danger' }
    };

    // AWS Region presets
    const AWS_REGIONS = [
        { value: 'ap-south-1', label: 'Asia Pacific (Mumbai)' },
        { value: 'us-east-1', label: 'US East (N. Virginia)' },
        { value: 'us-east-2', label: 'US East (Ohio)' },
        { value: 'us-west-2', label: 'US West (Oregon)' },
        { value: 'eu-west-1', label: 'Europe (Ireland)' },
        { value: 'eu-central-1', label: 'Europe (Frankfurt)' },
        { value: 'ap-southeast-1', label: 'Asia Pacific (Singapore)' },
        { value: 'ap-northeast-1', label: 'Asia Pacific (Tokyo)' }
    ];

    // IANA timezones for Automation Rules schedule forms — curated, not exhaustive
    const IANA_TIMEZONES = [
        'Asia/Kolkata',
        'UTC',
        'America/New_York',
        'America/Chicago',
        'America/Los_Angeles',
        'Europe/London',
        'Europe/Berlin',
        'Asia/Singapore',
        'Asia/Tokyo',
        'Australia/Sydney'
    ];

    return {
        API_BASE_URL,
        AUTH,
        API,
        UI,
        STATUS,
        VM_STATUS_CONFIG,
        renderStatusBadge,
        CLOUD_ICONS,
        ACCESS_LEVELS,
        AWS_REGIONS,
        IANA_TIMEZONES
    };
})();
