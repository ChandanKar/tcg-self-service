# VM Self-Service Platform - UI Integration Plan

**Date:** February 14, 2026  
**Status:** Backend 100% Ready | Frontend 30% (Scaffolding)  
**Estimated Duration:** 3-4 weeks

---

## Executive Summary

The backend is fully implemented with 66 secured API endpoints across 12 controllers. The frontend has basic scaffolding with mock data. This plan outlines the systematic integration of the frontend with the real backend APIs.

---

## Current State Analysis

### Backend APIs Available (66 endpoints)

| Category | Controller | Base URL | Endpoints |
|----------|------------|----------|-----------|
| **Environments** | EnvironmentController | `/api/v1/environments` | 5 |
| **VM Groups** | VmGroupController | `/api/v1/environments/{envId}/groups` | 5 |
| **VMs** | VmMgmtController | `/api/v1/environments/{envId}/vms` | 4 |
| **VM Operations** | VmOperationsController | `/api/v1/environments/{envId}/operations` | 4 |
| **Locks** | LockController | `/api/v1/environments/{envId}/lock` | 5 |
| **Users** | UserController | `/api/v1/users` | 10 |
| **Access Requests** | EnvironmentAccessController | `/api/v1` | 12 |
| **Audit** | AuditController | `/api/v1/audit` | 9 |
| **Monitoring** | MonitoringController | `/api/v1/monitoring` | 8 |
| **EC2 Direct** | Ec2Controller | `/api/ec2` | 4 |

### Frontend Current State

| Component | Status | Notes |
|-----------|--------|-------|
| `index.html` | ✅ Layout Ready | Main shell with sidebar, navbar, content area |
| `app.js` | ✅ Ready | Main initialization |
| `api-client.js` | ✅ Ready | AJAX wrapper with error handling |
| `config.js` | 🟡 Partial | Some endpoints defined, needs completion |
| `dashboard.js` | 🟡 Mock Data | Has UI, needs real API integration |
| `environments.js` | 🟡 Mock Data | Has UI, needs real API integration |
| `sidebar.js` | ✅ Ready | Navigation working |
| `slideout.js` | ✅ Ready | Detail panels working |
| `notifications.js` | ✅ Ready | Toast notifications |
| **User Management** | ❌ Missing | New feature needed |
| **Access Requests** | ❌ Missing | New feature needed |
| **Login/Auth** | ❌ Missing | OAuth2 integration needed |

---

## Implementation Phases

### Phase 1: Authentication & User Context (Week 1 - Days 1-3)

#### 1.1 OAuth2 Login Integration
**Files to create/modify:**
- `js/core/auth.js` (NEW)
- `js/ui/user-menu.js` (NEW)
- `index.html` (modify user profile area)

**Tasks:**
1. Create Auth module to handle OAuth2 login state
2. Check authentication on page load
3. Redirect to `/oauth2/authorization/azure` if not authenticated
4. Display current user info in top navbar
5. Implement logout functionality

**API Endpoints:**
```javascript
// Add to config.js
auth: {
    currentUser: `${API_BASE_URL}/users/me`,
    logout: '/logout'
}
```

#### 1.2 User Profile Dropdown
**Tasks:**
1. Replace hardcoded "John Doe" with actual user data
2. Show user role (Admin/Env Admin/User)
3. Add profile menu with logout option
4. Show "My Access" link

---

### Phase 2: Dashboard Real Data Integration (Week 1 - Days 3-5)

#### 2.1 Dashboard Metrics
**File:** `js/features/dashboard.js`

**Replace mock data with API calls:**
```javascript
// API calls needed:
GET /api/v1/environments                    // List environments
GET /api/v1/environments/{id}/groups        // Get groups per env
GET /api/v1/monitoring/sync-status          // VM counts

// Metrics to calculate:
- Total environments
- Total VMs (sum across environments)
- Running VMs (count status='RUNNING')
- Cost estimation (TBD - may need new endpoint)
```

#### 2.2 Environment Summary Cards
**Tasks:**
1. Fetch real environments list
2. Calculate running/stopped counts from VM data
3. Show lock status from lock API
4. Display cloud provider icons based on VM provider field

---

### Phase 3: Environment Management (Week 2 - Days 1-3)

#### 3.1 Environment List View
**File:** `js/features/environments.js`

**API Integration:**
```javascript
// Replace mockEnvironments with:
async function loadEnvironments() {
    const environments = await ApiClient.get(Config.API.environments.list);
    
    // For each environment, get additional data:
    for (const env of environments) {
        const groups = await ApiClient.get(Config.API.environments.groups(env.environmentId));
        const lock = await ApiClient.get(Config.API.locks.status(env.environmentId));
        env.groups = groups;
        env.lockStatus = lock;
    }
    
    return environments;
}
```

#### 3.2 Environment Detail View
**Tasks:**
1. Load environment by ID
2. Load groups with VMs
3. Show dependency tree visualization
4. Real-time VM status updates (polling or WebSocket)

#### 3.3 Environment CRUD Operations (Admin Only)
**New features:**
- Create Environment modal
- Edit Environment modal
- Deactivate Environment confirmation

**API Endpoints:**
```javascript
POST   /api/v1/environments           // Create
PUT    /api/v1/environments/{id}      // Update
DELETE /api/v1/environments/{id}      // Deactivate
```

---

### Phase 4: VM Operations Integration (Week 2 - Days 3-5)

#### 4.1 Start/Stop Operations
**Files:** 
- `js/features/vm-operations.js` (NEW)
- Modify `environments.js`

**Tasks:**
1. Implement Start Environment operation
2. Implement Stop Environment operation
3. Implement Start/Stop individual VM
4. Show operation progress in real-time
5. Handle operation cancellation

**API Integration:**
```javascript
// Start operation
POST /api/v1/environments/{envId}/operations
Body: {
    "operationType": "START_ENVIRONMENT",
    "targetIds": [], // empty for all
    "targetType": "ENVIRONMENT"
}

// Poll for status
GET /api/v1/environments/{envId}/operations/{executionId}

// Cancel
POST /api/v1/environments/{envId}/operations/{executionId}/cancel
```

#### 4.2 Operation Progress Modal
**New Component:**
- Show step-by-step progress
- Display VM statuses as they change
- Show errors with retry option
- Handle dependency sequencing visualization

---

### Phase 5: Lock Management (Week 2 - Day 5 + Week 3 - Day 1)

#### 5.1 Lock Status Integration
**Files:**
- `js/features/locks.js` (NEW)
- Modify `environments.js`

**Tasks:**
1. Show lock status on environment cards
2. Implement Acquire Lock modal
3. Implement Release Lock
4. Implement Break Lock (admin only)
5. Show lock history

**API Endpoints:**
```javascript
GET    /api/v1/environments/{envId}/lock              // Status
POST   /api/v1/environments/{envId}/lock/acquire      // Acquire
POST   /api/v1/environments/{envId}/lock/release      // Release
POST   /api/v1/environments/{envId}/lock/break        // Break (admin)
GET    /api/v1/environments/{envId}/lock/history      // History
```

#### 5.2 Lock Awareness in Operations
**Tasks:**
1. Prevent start/stop if not lock holder
2. Auto-acquire lock option before operations
3. Show "locked by X" warnings

---

### Phase 6: User & Access Management (Week 3 - Days 2-4)

#### 6.1 User Management Page (Admin Only)
**Files:**
- `js/features/users.js` (NEW)
- `js/ui/user-table.js` (NEW)

**Features:**
- List all users
- Search/filter users
- View user details
- Change user roles (Admin toggle)
- Deactivate users

**API Endpoints:**
```javascript
GET    /api/v1/users                        // List all
GET    /api/v1/users/{id}                   // Get user
PUT    /api/v1/users/{id}/role              // Update role
POST   /api/v1/users/{id}/deactivate        // Deactivate
POST   /api/v1/users/{id}/reactivate        // Reactivate
```

#### 6.2 Access Request System
**Files:**
- `js/features/access-requests.js` (NEW)

**Features:**
- Request Access form
- My Requests list
- Pending Requests list (admin)
- Approve/Deny workflow
- View environment access

**API Endpoints:**
```javascript
// User actions
POST   /api/v1/environments/{envId}/access-requests  // Request access
GET    /api/v1/access-requests/my                    // My requests
DELETE /api/v1/access-requests/{id}                  // Cancel request

// Admin actions
GET    /api/v1/access-requests/pending               // Pending list
POST   /api/v1/access-requests/{id}/approve          // Approve
POST   /api/v1/access-requests/{id}/deny             // Deny
GET    /api/v1/environments/{envId}/access           // View access list
POST   /api/v1/environments/{envId}/access           // Grant access
DELETE /api/v1/environments/{envId}/access/{userId}  // Revoke access
```

---

### Phase 7: Audit & Monitoring (Week 3 - Days 4-5)

#### 7.1 Audit Log Viewer
**Files:**
- `js/features/audit.js` (NEW)

**Features:**
- Paginated audit log table
- Filter by date range
- Filter by user
- Filter by action type
- Filter by environment
- Export to CSV

**API Endpoints:**
```javascript
GET /api/v1/audit/logs?page=0&size=50&environmentId=X&userId=Y
GET /api/v1/audit/logs/recent
GET /api/v1/audit/report?startDate=X&endDate=Y
```

#### 7.2 VM State Monitoring
**Features:**
- Real-time VM state display
- State change history
- Drift detection alerts
- Manual sync trigger

**API Endpoints:**
```javascript
GET  /api/v1/monitoring/sync-status
POST /api/v1/monitoring/sync
GET  /api/v1/monitoring/vms/{vmId}/history
GET  /api/v1/monitoring/drift-events
```

---

### Phase 8: Polish & Enhancements (Week 4)

#### 8.1 Real-time Updates
**Options:**
1. **Polling:** Refresh data every 10-30 seconds
2. **Server-Sent Events (SSE):** Push updates from server
3. **WebSocket:** Bi-directional real-time (future)

**Implementation:**
- Start with polling for simplicity
- Add SSE for operation progress

#### 8.2 Error Handling & UX
- Loading spinners/skeletons
- Error boundaries
- Retry mechanisms
- Confirmation dialogs
- Toast notifications for operations

#### 8.3 Responsive Design
- Mobile sidebar collapse
- Touch-friendly buttons
- Responsive tables

#### 8.4 Accessibility
- ARIA labels
- Keyboard navigation
- Screen reader support

---

## File Structure (After Implementation)

```
static/js/
├── app.js                    # Main initialization
├── config.js                 # API endpoints, settings
├── core/
│   ├── api-client.js        # AJAX wrapper
│   ├── auth.js              # 🆕 Authentication handler
│   ├── router.js            # Content routing
│   ├── template-loader.js   # Dynamic templates
│   └── utils.js             # Utility functions
├── features/
│   ├── dashboard.js         # ✏️ Update with real APIs
│   ├── environments.js      # ✏️ Update with real APIs
│   ├── vm-operations.js     # 🆕 Start/stop operations
│   ├── locks.js             # 🆕 Lock management
│   ├── users.js             # 🆕 User management
│   ├── access-requests.js   # 🆕 Access workflow
│   ├── audit.js             # 🆕 Audit logs
│   └── monitoring.js        # 🆕 VM monitoring
└── ui/
    ├── notifications.js     # Toast notifications
    ├── sidebar.js           # Sidebar navigation
    ├── slideout.js          # Detail panels
    ├── user-menu.js         # 🆕 User dropdown
    ├── modals.js            # 🆕 Modal dialogs
    └── tables.js            # 🆕 Data table utilities
```

---

## API Config Updates

**Update `config.js` with all endpoints:**

```javascript
const API = {
    // Existing
    environments: { ... },
    operations: { ... },
    locks: { ... },
    audit: { ... },
    monitoring: { ... },
    
    // New
    auth: {
        currentUser: `${API_BASE_URL}/users/me`,
        logout: '/logout'
    },
    users: {
        list: `${API_BASE_URL}/users`,
        get: (id) => `${API_BASE_URL}/users/${id}`,
        updateRole: (id) => `${API_BASE_URL}/users/${id}/role`,
        deactivate: (id) => `${API_BASE_URL}/users/${id}/deactivate`,
        reactivate: (id) => `${API_BASE_URL}/users/${id}/reactivate`,
        search: (query) => `${API_BASE_URL}/users/search?query=${query}`
    },
    access: {
        myEnvironments: `${API_BASE_URL}/users/me/access`,
        requestAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access-requests`,
        myRequests: `${API_BASE_URL}/access-requests/my`,
        pendingRequests: `${API_BASE_URL}/access-requests/pending`,
        approve: (id) => `${API_BASE_URL}/access-requests/${id}/approve`,
        deny: (id) => `${API_BASE_URL}/access-requests/${id}/deny`,
        cancel: (id) => `${API_BASE_URL}/access-requests/${id}`,
        environmentAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
        grantAccess: (envId) => `${API_BASE_URL}/environments/${envId}/access`,
        revokeAccess: (envId, userId) => `${API_BASE_URL}/environments/${envId}/access/${userId}`
    },
    vms: {
        list: (envId) => `${API_BASE_URL}/environments/${envId}/vms`,
        get: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`,
        register: (envId) => `${API_BASE_URL}/environments/${envId}/vms`,
        update: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`,
        delete: (envId, vmId) => `${API_BASE_URL}/environments/${envId}/vms/${vmId}`
    },
    groups: {
        list: (envId) => `${API_BASE_URL}/environments/${envId}/groups`,
        get: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
        create: (envId) => `${API_BASE_URL}/environments/${envId}/groups`,
        update: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
        delete: (envId, groupId) => `${API_BASE_URL}/environments/${envId}/groups/${groupId}`,
        startOrder: (envId) => `${API_BASE_URL}/environments/${envId}/groups/start-order`
    }
};
```

---

## Priority Order

| Priority | Phase | Effort | Dependencies |
|----------|-------|--------|--------------|
| 🔴 P0 | Phase 1: Authentication | 3 days | Backend ready |
| 🔴 P0 | Phase 2: Dashboard | 2 days | Phase 1 |
| 🟠 P1 | Phase 3: Environments | 3 days | Phase 2 |
| 🟠 P1 | Phase 4: VM Operations | 3 days | Phase 3 |
| 🟡 P2 | Phase 5: Locks | 2 days | Phase 3 |
| 🟡 P2 | Phase 6: Users & Access | 3 days | Phase 1 |
| 🟢 P3 | Phase 7: Audit & Monitoring | 2 days | Phase 1 |
| 🟢 P3 | Phase 8: Polish | 2-3 days | All above |

---

## Testing Checklist

### For Each Feature:
- [ ] API calls work correctly
- [ ] Error handling works (network errors, 4xx, 5xx)
- [ ] Loading states display properly
- [ ] Empty states handled
- [ ] Permissions enforced (admin vs user)
- [ ] Responsive on mobile
- [ ] Keyboard accessible

### Integration Tests:
- [ ] Login flow works end-to-end
- [ ] Start/Stop environment with dependencies
- [ ] Lock acquisition and operation flow
- [ ] Access request workflow
- [ ] Admin user management

---

## Risk & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| OAuth2 redirect issues | Medium | High | Test with real Azure AD early |
| Real-time updates lag | Low | Medium | Implement polling first |
| Browser compatibility | Low | Medium | Test in Chrome, Firefox, Edge |
| Performance with many VMs | Low | High | Implement pagination, lazy loading |

---

## Success Criteria

1. ✅ Users can login via Azure AD
2. ✅ Dashboard shows real environment/VM data
3. ✅ Users can start/stop VMs with proper authorization
4. ✅ Lock management prevents conflicts
5. ✅ Access requests workflow is functional
6. ✅ Admin can manage users and access
7. ✅ Audit logs are viewable
8. ✅ All operations provide feedback (loading, success, error)

---

*Document created: February 14, 2026*

