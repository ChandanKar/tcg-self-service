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

        // User Management
        users: {
            list: `${API_BASE_URL}/users`,
            get: (id) => `${API_BASE_URL}/users/${id}`,
            updateRole: (id) => `${API_BASE_URL}/users/${id}/role`,
            setAdmin: (id) => `${API_BASE_URL}/users/${id}/admin`,
            setEnvAdmin: (id) => `${API_BASE_URL}/users/${id}/env-admin`,
            deactivate: (id) => `${API_BASE_URL}/users/${id}/deactivate`,
            reactivate: (id) => `${API_BASE_URL}/users/${id}/reactivate`,
            search: (query) => `${API_BASE_URL}/users/search?q=${encodeURIComponent(query)}`,
            admins: `${API_BASE_URL}/users/admins`
        },

        // Environment Access
        access: {
            myEnvironments: `${API_BASE_URL}/users/me/access`,
            environmentAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
            grantAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
            revokeAccess: (envId, userId) => `${API_BASE_URL}/environments/${envId}/access/${userId}`,
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
            available: `${API_BASE_URL}/environments/available`,
            get: (id) => `${API_BASE_URL}/environments/${id}`,
            create: `${API_BASE_URL}/environments`,
            update: (id) => `${API_BASE_URL}/environments/${id}`,
            delete: (id) => `${API_BASE_URL}/environments/${id}`
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
            delete: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`
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
            }
        },

        // Locks
        locks: {
            status: (envId) => `${API_BASE_URL}/environments/${envId}/lock`,
            acquire: (envId) => `${API_BASE_URL}/environments/${envId}/lock/acquire`,
            release: (envId) => `${API_BASE_URL}/environments/${envId}/lock/release`,
            break: (envId) => `${API_BASE_URL}/environments/${envId}/lock/break`,
            history: (envId) => `${API_BASE_URL}/environments/${envId}/lock/history`
        },

        // Audit
        audit: {
            logs: `${API_BASE_URL}/audit/logs`,
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
            syncEnvironment: (envId) => `${API_BASE_URL}/monitoring/sync/environment/${envId}`,
            vmHistory: (vmId) => `${API_BASE_URL}/monitoring/vms/${vmId}/history`,
            stateChanges: `${API_BASE_URL}/monitoring/state-changes`,
            driftEvents: `${API_BASE_URL}/monitoring/drift-events`,
            driftCount: `${API_BASE_URL}/monitoring/drift-events/count`,
            driftReport: `${API_BASE_URL}/monitoring/drift-events/report`
        },

        // EC2 (AWS)
        ec2: {
            listInstances: (region) => `/api/ec2/instances?region=${region}`,
            registeredIds: `/api/ec2/registered-ids`
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
            STOPPED: { label: 'Stopped', class: 'stopped', icon: 'fa-circle', color: '#ef4444' },
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
            PARTIAL_SUCCESS: { label: 'Partial Success', class: 'text-warning', icon: 'fa-exclamation-triangle' },
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
        OCI: { icon: 'fas fa-cloud', color: '#F80000', label: 'Oracle Cloud' }
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
        AWS_REGIONS
    };
})();
