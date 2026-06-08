/**
 * VM Self-Service Platform - Additional Features
 * Placeholder implementations for various features
 * These use mock data and will be connected to APIs later
 */

const Features = (function() {
    'use strict';


    /**
     * Load Access Management view (Admin)
     * Delegates to AccessManagement module
     */
    function loadAccessManagement() {
        if (window.AccessManagement) {
            return window.AccessManagement.load();
        }
        // Module not loaded
        $('#content-area').html(`
            <div class="alert alert-danger m-3">
                <i class="fas fa-exclamation-circle me-2"></i>Access Management module failed to load.
                Please refresh the page.
            </div>
        `);
    }

    // VM Registry feature moved to js/features/vm-registry.js (VmRegistry module)
    // Router now calls VmRegistry.load() directly. This shim keeps backward compatibility.
    function loadVmRegistry() { return window.VmRegistry ? VmRegistry.load() : undefined; }

    // Shims for VmRegistry methods called inline from index.html
    function fetchEc2Instances() { return window.VmRegistry ? VmRegistry.fetchEc2Instances() : undefined; }
    function filterEc2Instances(val) { return window.VmRegistry ? VmRegistry.filterEc2Instances(val) : undefined; }
    function submitGroup() { return window.VmRegistry ? VmRegistry.submitGroup() : undefined; }
    function submitVm() { return window.VmRegistry ? VmRegistry.submitVm() : undefined; }


    /**
     * Load Automation Rules view
     */
    function loadAutomationRules() {
        const html = `
            <div class="content-header">
                <h1>Automation Rules</h1>
                <p>Schedule automatic start/stop operations</p>
            </div>

            <div class="mb-3">
                <button class="btn btn-primary"><i class="fas fa-plus"></i> Create Rule</button>
            </div>

            <h5 class="mb-3">Active Rules (3)</h5>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Nightly Shutdown</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-danger">STOP_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Every day at 7:00 PM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 7:00 PM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Morning Startup</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-success">START_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Weekdays at 8:00 AM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 8:00 AM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        $('#content-area').html(html);
    }

    /**
     * Load System Health view — delegates to SystemHealth module
     */
    function loadSystemHealth() {
        if (window.SystemHealth) {
            return window.SystemHealth.load();
        }
        $('#content-area').html(`
            <div class="alert alert-danger m-3">
                <i class="fas fa-exclamation-circle me-2"></i>System Health module failed to load. Please refresh the page.
            </div>
        `);
    }

    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading...</p>
            </div>
        `);
    }

    /**
     * Load User Management view (Admin only)
     * Delegates to UserManagement module
     */
    function loadUserManagement() {
        if (window.UserManagement && typeof window.UserManagement.load === 'function') {
            window.UserManagement.load();
        } else {
            $('#content-area').html('<div class="alert alert-danger m-3">User Management module not loaded.</div>');
        }
    }

    return {
        loadAccessManagement,
        loadVmRegistry,       // shim — delegates to VmRegistry.load()
        loadAutomationRules,
        loadSystemHealth,
        loadUserManagement,
        fetchEc2Instances,
        filterEc2Instances,
        submitGroup,
        submitVm
    };
})();

// Make Features available on window for router and sidebar navigation
window.Features = Features;
