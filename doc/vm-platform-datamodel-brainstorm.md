# VM Platform - Data Model Brainstorming

## Overview
This document brainstorms the complete data model for the VM Self-Service Platform based on the mockup UI and requirements document.

---

## 1. USER & HIERARCHY

### Summary: Two-Level Admin System
```
┌─────────────────────────────────────────────────────────────┐
│ User Registration: Automatic on first Azure AD login        │
│ Default: admin=false, env_admin=false (normal user)        │
└─────────────────────────────────────────────────────────────┘

Three User Types:
1. Normal User (admin=false, env_admin=false)
   └─ Can access assigned environments only
   └─ Can start/stop VMs, acquire locks, view logs
   └─ CANNOT grant access or break locks
   
2. Environment Admin (admin=false, env_admin=true)
   └─ Can be designated as admin for SPECIFIC environments
   └─ For THEIR environments, can:
      ├─ Grant/revoke access to other users
      ├─ Approve/deny access requests
      ├─ Break locks (emergency unlock)
      ├─ View audit logs for their environment
      └─ Manage environment settings
   └─ Set via: EnvironmentAccess.access_level = 'admin'
   └─ CANNOT access other environments unless explicitly granted
   
3. Global Admin (admin=true)
   └─ Full platform access across ALL environments
   └─ Can break locks anywhere, force operations, override dependencies
   └─ Can promote users to env_admin or admin
   └─ Can grant access to any environment
   └─ No environment-level restrictions

### Permissions Matrix

| Action | Normal User | Environment Admin | Global Admin |
|--------|-------------|-------------------|--------------|
| **Environment Access** |
| View assigned environments | ✅ | ✅ | ✅ All environments |
| Request access to environment | ✅ | ✅ | N/A (has all access) |
| **User Management** |
| Grant access to users | ❌ | ✅ (own envs) | ✅ (all envs) |
| Approve access requests | ❌ | ✅ (own envs) | ✅ (all envs) |
| Revoke user access | ❌ | ✅ (own envs) | ✅ (all envs) |
| Promote to env_admin | ❌ | ❌ | ✅ |
| Promote to admin | ❌ | ❌ | ✅ |
| **VM Operations** |
| Start/Stop VMs | ✅ | ✅ | ✅ |
| View VM logs | ✅ | ✅ | ✅ |
| View VM details | ✅ | ✅ | ✅ |
| Force stop (bypass deps) | ❌ | ❌ | ✅ |
| Override dependencies | ❌ | ❌ | ✅ |
| **Lock Management** |
| Acquire lock | ✅ | ✅ | ✅ |
| Release own lock | ✅ | ✅ | ✅ |
| Break others' locks | ❌ | ✅ (own envs) | ✅ (all envs) |
| **Monitoring & Logs** |
| View own audit logs | ✅ | ✅ | ✅ |
| View environment audit logs | ❌ | ✅ (own envs) | ✅ (all envs) |
| View platform-wide logs | ❌ | ❌ | ✅ |
| Export audit logs | ❌ | ✅ (own envs) | ✅ (all envs) |
| **Configuration** |
| Manage environment settings | ❌ | ✅ (own envs) | ✅ (all envs) |
| Register/deregister VMs | ❌ | ✅ (own envs) | ✅ (all envs) |
| Create/modify groups | ❌ | ✅ (own envs) | ✅ (all envs) |
| System configuration | ❌ | ❌ | ✅ |
```
```

### 1.1 User Entity
```
User
├── user_id (PK, UUID)
├── email (unique, from Azure AD)
├── display_name
├── azure_ad_object_id (unique)
├── admin (boolean, default false)
├── env_admin (boolean, default false)
├── is_active (boolean, default true)
├── created_at
├── updated_at
└── last_login_at
```

**Key Considerations:**
- **Auto-registration:** User automatically created on first login via Azure AD SSO
- **No password storage:** All authentication delegated to Azure AD
- **Two admin levels:**
  - `admin` = **Global Admin** - Full platform access, can manage all environments, break locks, force operations
  - `env_admin` = **Environment Admin Eligible** - Can be designated as admin for specific environments
- Users are created as normal users by default (`admin=false`, `env_admin=false`)
- Can be promoted to env_admin or admin by existing admins

**Auto-Registration Flow:**
1. User logs in via Azure AD (first time)
2. System receives Azure AD token with email, display_name, object_id
3. Check if user exists: `SELECT * FROM User WHERE azure_ad_object_id = ?`
4. If NOT exists → `INSERT INTO User` with defaults (admin=false, env_admin=false)
5. If exists → Update last_login_at
6. User is now in system, can request environment access

**Admin Promotion Flow:**
- **To promote user to env_admin:**
  ```sql
  UPDATE User 
  SET env_admin = true, updated_at = NOW() 
  WHERE user_id = ? 
  AND admin = true; -- Only global admins can promote
  ```
  - Only global admins can set `env_admin = true`
  - Once env_admin is true, user can be assigned as admin to specific environments
  - Logged in AuditLog with action_type = 'user_promoted_to_env_admin'

- **To promote user to global admin:**
  ```sql
  UPDATE User 
  SET admin = true, updated_at = NOW() 
  WHERE user_id = ?;
  ```
  - Only existing global admins can promote to admin
  - This is a sensitive operation - requires confirmation
  - Logged in AuditLog with action_type = 'user_promoted_to_admin'
  - Consider: Send notification to all other admins

- **To assign environment admin:**
  ```sql
  -- First, user must have env_admin = true
  INSERT INTO EnvironmentAccess (environment_id, user_id, access_level, ...) 
  VALUES (?, ?, 'admin', ...)
  WHERE (SELECT env_admin FROM User WHERE user_id = ?) = true;
  ```

### 1.2 User Role Assignment (Simplified)
**No separate Role table needed!** Role is determined by:
- `admin = true` → Global Admin (full access)
- `env_admin = true` + entry in `EnvironmentAccess` with `access_level = 'admin'` → Environment Admin for specific environments
- Regular user → Just access to assigned environments

---

## 2. ENVIRONMENTS, GROUPS & VMs RELATIONSHIPS

### 2.1 Environment Entity
```
Environment
├── environment_id (PK, UUID)
├── name (unique, e.g., "mcube-demo-environment")
├── display_name
├── description
├── is_active (boolean)
├── created_at
├── updated_at
└── metadata (JSON - flexible for future attributes)
```

**Key Features:**
- Top-level organizational unit
- Scope for locking mechanism
- Users granted access at this level
- Can have 0 to many groups

### 2.2 Environment Access (User to Environment Mapping)
```
EnvironmentAccess
├── access_id (PK, UUID)
├── environment_id (FK)
├── user_id (FK)
├── access_level (enum: 'viewer', 'user', 'admin')
├── granted_by_user_id (FK to User)
├── granted_at
├── expires_at (nullable - for temporary access)
├── revoked_at (nullable)
├── status (enum: 'pending', 'active', 'revoked', 'expired')
└── notes

UNIQUE constraint: (environment_id, user_id) WHERE status = 'active'
```

**Access Levels:**
1. **viewer** - Read-only access, can view environment but not start/stop VMs
2. **user** - Can start/stop VMs, acquire locks, normal operations
3. **admin** - Environment admin, can approve access requests, manage environment settings
   - **Requirement:** User must have `User.env_admin = true` to be granted 'admin' level
   - Validation: `IF access_level = 'admin' THEN CHECK (SELECT env_admin FROM User WHERE user_id = ?) = true`

**Who Can Grant Access:**
- **Global admins** (`User.admin = true`) can grant any access level to any environment
- **Environment admins** (`User.env_admin = true` + `EnvironmentAccess.access_level = 'admin'`) can grant access to THEIR environments
  - Can grant access_level: 'viewer', 'user', or 'admin' (if target user has env_admin=true)
  - Can only grant access to environments where they are admin
- **Regular users** cannot grant access

**Direct Grant vs. Request Approval:**
- **Direct Grant:** Admin directly adds user to environment without request
  - Used for: Onboarding new team members, bulk access grants
  - Creates EnvironmentAccess record immediately with status='active'
- **Request Approval:** User requests access, admin approves
  - Used for: Self-service access, controlled access
  - Moves EnvironmentAccessRequest from 'pending' to 'approved', creates EnvironmentAccess record

**Access Request Workflow:**
1. User requests access to environment
2. Request stored with status = 'pending'
3. Notification sent to:
   - All global admins
   - All environment admins for that environment
4. Admin approves → status changes to 'active'
5. User can now see environment in sidebar

**Temporary Access:**
- If `expires_at` is set, access automatically revokes after that date
- Background job checks expired access daily
- User receives notification 24h before expiration

### 2.3 Environment Access Request (Optional separate table)
```
EnvironmentAccessRequest
├── request_id (PK, UUID)
├── environment_id (FK)
├── requester_user_id (FK)
├── requested_access_level
├── justification (text)
├── status (enum: pending, approved, denied)
├── reviewed_by_user_id (FK, nullable)
├── reviewed_at (nullable)
├── review_notes (nullable)
├── created_at
└── expires_at (auto-deny after X days)
```

### 2.4 Group Entity
```
Group
├── group_id (PK, UUID)
├── environment_id (FK)
├── name (e.g., "data-tier", "backend-tier")
├── display_name
├── description
├── sequence_position (integer - defines start order)
├── depends_on_group_ids (JSON array of group_ids)
├── created_at
├── updated_at
└── metadata (JSON)

UNIQUE constraint: (environment_id, name)
UNIQUE constraint: (environment_id, sequence_position)
```

**Key Logic:**
- Groups within environment start in sequence_position order
- `depends_on_group_ids` stores group dependencies as array
- All VMs in dependent groups must be RUNNING before this group can start
- Groups with same dependencies can start in parallel
- Circular dependency validation on insert/update

**Example Dependencies:**
```json
{
  "group_id": "backend-tier-uuid",
  "sequence_position": 2,
  "depends_on_group_ids": ["data-tier-uuid"]
}
```

### 2.5 VM Entity (Core)
```
VM
├── vm_id (PK, UUID)
├── group_id (FK)
├── name (unique within environment)
├── display_name
├── description
├── provider (enum: AWS, GCP, AZURE, OCI)
├── region (string - provider-specific region code)
├── provider_vm_id (provider's resource ID - unique per provider)
├── vm_type (enum: dev, test, staging - currently only 'dev' in scope)
├── sequence_position (integer - within group)
├── depends_on_vm_ids (JSON array of vm_ids - same group only)
├── status (enum: running, stopped, starting, stopping, error, unknown)
├── last_known_state (for drift detection)
├── last_state_sync_at
├── state_drift_detected (boolean)
├── created_at
├── updated_at
└── metadata (JSON)

UNIQUE constraint: (provider, provider_vm_id)
UNIQUE constraint: (group_id, name)
UNIQUE constraint: (group_id, sequence_position)
```

**Status Flow:**
- stopped → starting → running
- running → stopping → stopped
- any → error (on failure)
- unknown (when sync fails)

---

## 3. VM DETAILS BY CLOUD PROVIDER

### 3.1 VM Provider Details (Extensible approach)
```
VMProviderDetails
├── vm_provider_detail_id (PK, UUID)
├── vm_id (FK, unique)
├── provider (enum: AWS, GCP, AZURE, OCI)
├── region_code (string)
├── region_name (string, human readable)
├── availability_zone (nullable)
├── instance_type (string - t2.micro, n1-standard-1, etc.)
├── cpu_cores (integer)
├── memory_gb (float)
├── network_interfaces (JSON array)
├── storage_volumes (JSON array)
├── tags (JSON object)
├── labels (JSON object - GCP uses labels, AWS uses tags)
├── provider_console_url (string)
├── private_ip (string)
├── public_ip (nullable string)
├── vpc_id (nullable string)
├── subnet_id (nullable string)
├── security_groups (JSON array)
├── iam_profile (nullable string)
├── created_at
└── updated_at
```

**Provider-Specific Extensions:**

#### AWS Specific
```json
{
  "instance_type": "t3.medium",
  "cpu_cores": 2,
  "memory_gb": 4,
  "ami_id": "ami-0c55b159cbfafe1f0",
  "key_pair": "my-key-pair",
  "ebs_optimized": true,
  "monitoring": "detailed",
  "tags": {
    "Environment": "dev",
    "Team": "backend",
    "CostCenter": "123456"
  }
}
```

#### GCP Specific
```json
{
  "machine_type": "n1-standard-2",
  "cpu_cores": 2,
  "memory_gb": 7.5,
  "boot_disk_type": "pd-standard",
  "boot_disk_size_gb": 100,
  "preemptible": false,
  "labels": {
    "environment": "dev",
    "team": "backend"
  }
}
```

#### Azure Specific
```json
{
  "vm_size": "Standard_D2s_v3",
  "cpu_cores": 2,
  "memory_gb": 8,
  "resource_group": "my-rg",
  "os_type": "Linux",
  "os_disk_type": "Premium_LRS",
  "tags": {
    "Environment": "dev",
    "Department": "Engineering"
  }
}
```

#### OCI Specific
```json
{
  "shape": "VM.Standard2.1",
  "cpu_cores": 1,
  "memory_gb": 15,
  "compartment_id": "ocid1.compartment...",
  "defined_tags": {},
  "freeform_tags": {
    "environment": "dev"
  }
}
```

### 3.2 Alternative: Provider-Specific Tables (Normalized)

**Option A:** Single flexible table with JSON (chosen above - more flexible)
**Option B:** Separate tables per provider (more normalized but rigid)

```
VMDetailsAWS
├── vm_id (PK, FK)
├── instance_id
├── instance_type
├── ami_id
└── ... AWS-specific fields

VMDetailsGCP
├── vm_id (PK, FK)
├── instance_name
├── machine_type
├── project_id
└── ... GCP-specific fields

VMDetailsAzure
├── vm_id (PK, FK)
├── resource_id
├── vm_size
├── resource_group
└── ... Azure-specific fields

VMDetailsOCI
├── vm_id (PK, FK)
├── instance_id
├── compartment_id
├── shape
└── ... OCI-specific fields
```

**Recommendation:** Use single `VMProviderDetails` table with JSON for flexibility.

### 3.3 Region/Zone Master Data (Optional)
```
CloudRegion
├── region_id (PK)
├── provider (enum)
├── region_code (e.g., "us-east-1")
├── region_name (e.g., "US East (N. Virginia)")
├── continent
├── country
├── city
├── is_active
└── latency_zone (for grouping)
```

### 3.4 Labels/Tags Management
Since different clouds call them different things:
- AWS: Tags (key-value)
- GCP: Labels (key-value)
- Azure: Tags (key-value)
- OCI: Tags (defined + freeform)

**Store as JSON in VMProviderDetails:**
```json
{
  "labels": {
    "environment": "dev",
    "team": "backend",
    "cost-center": "eng-123",
    "owner": "john.doe@company.com",
    "project": "customer-portal",
    "managed-by": "vm-platform"
  }
}
```

---

## 4. LOCK MANAGEMENT (Acquire/Release/Break)

### 4.1 Environment Lock Entity
```
EnvironmentLock
├── lock_id (PK, UUID)
├── environment_id (FK, unique)
├── locked_by_user_id (FK)
├── locked_at
├── lock_reason (nullable text - user can explain why)
├── expected_duration_minutes (nullable - user estimate)
├── is_active (boolean)
├── released_at (nullable)
├── released_by_user_id (FK, nullable)
├── broken_by_admin_user_id (FK, nullable)
├── break_reason (nullable text - required if broken by admin)
└── updated_at

UNIQUE constraint: (environment_id) WHERE is_active = true
```

**Lock States:**
1. **Active Lock:** `is_active = true`, `released_at = null`
2. **Released Lock:** `is_active = false`, `released_at != null`, `released_by_user_id != null`
3. **Broken Lock:** `is_active = false`, `released_at != null`, `broken_by_admin_user_id != null`

**Lock Operations:**

#### Acquire Lock
```sql
-- Check if lock exists and is active
SELECT * FROM EnvironmentLock 
WHERE environment_id = ? AND is_active = true
FOR UPDATE;  -- Row-level lock for concurrency

-- If no active lock, insert new one
INSERT INTO EnvironmentLock (...)
VALUES (...);
```

#### Release Lock
```sql
UPDATE EnvironmentLock
SET is_active = false,
    released_at = NOW(),
    released_by_user_id = ?
WHERE environment_id = ? 
  AND locked_by_user_id = ?
  AND is_active = true;
```

#### Break Lock
```sql
-- First check authorization
SELECT 
  CASE 
    WHEN u.admin = true THEN true  -- Global admin can break any lock
    WHEN u.env_admin = true 
         AND EXISTS (
           SELECT 1 FROM EnvironmentAccess ea 
           WHERE ea.environment_id = ? 
             AND ea.user_id = u.user_id 
             AND ea.access_level = 'admin' 
             AND ea.status = 'active'
         ) THEN true  -- Env admin for THIS environment
    ELSE false  -- Not authorized
  END as can_break_lock
FROM User u
WHERE u.user_id = ?;

-- If authorized, break the lock
UPDATE EnvironmentLock
SET is_active = false,
    released_at = NOW(),
    broken_by_admin_user_id = ?,
    break_reason = ?
WHERE environment_id = ?
  AND is_active = true;

-- Send notification to original lock holder
-- Log in AuditLog with action_type = 'lock_broken'
```

**Who Can Break Locks:**
1. **Global Admins** (`admin = true`) - Can break locks on ANY environment
2. **Environment Admins** (`env_admin = true` + `access_level = 'admin'` for that environment) - Can break locks ONLY on their environments
3. **Lock Holder** - Can only release (not break) their own lock

**Break Lock UI Flow:**
1. Admin sees locked environment with "Break Lock" button (only if authorized)
2. Clicks "Break Lock" → Modal appears:
   - Shows: Current lock holder, locked since (duration), environment name
   - Requires: Break reason (text field, required, min 10 chars)
   - Warning: "This will notify [username] that their lock was broken"
3. Admin confirms → Lock broken
4. Original lock holder receives notification: "Your lock on [env] was broken by [admin] - Reason: [reason]"
5. Logged in AuditLog with both admin_id and original lock_holder_id

### 4.2 Lock History (Audit Trail)
```
LockHistory
├── history_id (PK, UUID)
├── lock_id (FK)
├── environment_id (FK)
├── action (enum: acquired, released, broken, expired)
├── performed_by_user_id (FK)
├── timestamp
├── notes
└── metadata (JSON)
```

**Questions:**
- Should we auto-expire locks after X hours? (Spec says NO - manual only)
- Should we send notifications when lock held > 4 hours? (Spec says future enhancement)

---

## 5. ACCESS REQUEST & GRANT

### 5.1 Full Access Request Workflow

As mentioned in section 2.3, but expanding:

```
EnvironmentAccessRequest
├── request_id (PK, UUID)
├── environment_id (FK)
├── requester_user_id (FK)
├── requested_access_level (enum: read, write)
├── business_justification (text, required)
├── duration_days (integer, nullable - for temporary access)
├── status (enum: pending, approved, denied, cancelled)
├── reviewed_by_user_id (FK, nullable)
├── reviewed_at (nullable)
├── review_decision_notes (nullable)
├── created_at
├── updated_at
└── auto_expire_at (nullable)

-- Once approved, creates entry in EnvironmentAccess table
```

**Request Flow:**
1. User searches for environment
2. Sees "Request Access" button if not authorized
3. Fills form: justification, access level, duration
4. Request submitted → status = pending
5. Notification sent to environment admins/owners
6. Admin reviews in "Access Requests" tab
7. Approve → creates EnvironmentAccess entry
8. Deny → request marked denied, reason stored
9. User receives notification of decision

**Approval Authority:**
- **Global admins** (`User.admin = true`) can approve any request for any environment
- **Environment admins** (EnvironmentAccess where `access_level = 'admin'`) can approve requests for their environments
- Requester receives in-app + email notification on decision

---

## 6. NOTIFICATIONS (Bell Icon)

### 6.1 Notification Entity
```
Notification
├── notification_id (PK, UUID)
├── user_id (FK - recipient)
├── notification_type (enum)
├── title (string)
├── message (text)
├── severity (enum: info, warning, error, success)
├── is_read (boolean, default false)
├── is_dismissed (boolean, default false)
├── created_at
├── read_at (nullable)
├── related_entity_type (enum: environment, vm, group, lock, access_request)
├── related_entity_id (UUID)
├── action_url (nullable - deep link)
└── metadata (JSON)

INDEX: (user_id, is_read, created_at)
INDEX: (user_id, is_dismissed, created_at)
```

**Notification Types (enum values):**
- `ACCESS_REQUEST_PENDING` - Admin receives when someone requests access
- `ACCESS_GRANTED` - User receives when access approved
- `ACCESS_DENIED` - User receives when access denied
- `ACCESS_EXPIRING` - User receives 24h before access expires
- `ACCESS_REVOKED` - User receives if access revoked
- `LOCK_ACQUIRED` - (Optional) notify relevant users
- `LOCK_RELEASED` - (Optional) notify waiting users
- `LOCK_BROKEN` - Original lock holder notified
- `VM_START_FAILED` - Operation failed
- `VM_STOP_FAILED` - Operation failed
- `VM_STATE_DRIFT` - VM state doesn't match platform
- `ENVIRONMENT_UNLOCKED` - Environment became available
- `DEPENDENCY_BLOCKED` - Attempted start but dependencies not met
- `ADMIN_FORCE_ACTION` - Admin overrode dependencies/forced stop

### 6.2 Notification Preferences (Optional)
```
UserNotificationPreference
├── preference_id (PK, UUID)
├── user_id (FK)
├── notification_type (enum)
├── in_app_enabled (boolean, default true)
├── email_enabled (boolean, default true)
├── updated_at
```

### 6.3 Alertify Integration
For real-time toast notifications on actions:
- Success: "VM started successfully"
- Error: "Failed to start VM: insufficient permissions"
- Warning: "Lock acquired by another user"
- Info: "Environment state synced"

These are NOT stored in database - just transient UI feedback.
Notification table is for persistent bell icon notifications.

---

## 7. START SEQUENCES & DEPENDENCIES

### 7.1 Execution Tracking
```
OperationExecution
├── execution_id (PK, UUID)
├── environment_id (FK)
├── operation_type (enum: start_environment, stop_environment, start_group, stop_group, start_vm, stop_vm)
├── initiated_by_user_id (FK)
├── status (enum: pending, in_progress, completed, failed, cancelled)
├── started_at
├── completed_at (nullable)
├── error_message (nullable text)
├── total_targets (integer - how many VMs/groups affected)
├── completed_targets (integer)
├── failed_targets (integer)
└── execution_plan (JSON - stores the sequence plan)

-- Example execution_plan JSON:
{
  "steps": [
    {
      "sequence": 1,
      "group_id": "data-tier-uuid",
      "vms": [
        {"vm_id": "db-primary-uuid", "sequence": 1},
        {"vm_id": "db-replica-uuid", "sequence": 2, "depends_on": ["db-primary-uuid"]}
      ]
    },
    {
      "sequence": 2,
      "group_id": "backend-tier-uuid",
      "vms": [...]
    }
  ]
}
```

### 7.2 Operation Detail (Individual VM operations)
```
OperationDetail
├── detail_id (PK, UUID)
├── execution_id (FK)
├── target_type (enum: vm, group)
├── target_id (UUID)
├── target_name (string)
├── action (enum: start, stop)
├── sequence_position (integer)
├── depends_on_detail_ids (JSON array)
├── status (enum: pending, waiting, in_progress, completed, failed, skipped)
├── started_at (nullable)
├── completed_at (nullable)
├── cloud_operation_id (nullable - provider's operation tracking ID)
├── error_code (nullable)
├── error_message (nullable)
└── metadata (JSON)
```

**Status Flow:**
- `pending` → operation created but not started
- `waiting` → waiting for dependencies to complete
- `in_progress` → actively executing
- `completed` → successfully finished
- `failed` → operation failed
- `skipped` → skipped due to failed dependency

### 7.3 Dependency Resolution Algorithm

**Start Environment/Group:**
1. Build dependency graph (topological sort)
2. Identify groups that can start in parallel (no dependencies or same dependencies)
3. For each group:
   - Check all dependency groups: are ALL their VMs running?
   - If yes, start VMs in sequence within group
   - If no, wait
4. Within group, start VMs respecting VM-level dependencies

**Example Execution Plan:**
```
Environment: mcube-demo
├── Phase 1 (Parallel capable):
│   └── Group 1: data-tier (sequence 1, no deps)
│       ├── VM 1: database-primary (seq 1, no deps) → START
│       └── VM 2: database-replica (seq 2, deps: db-primary) → WAIT → START
├── Phase 2 (Parallel capable after Phase 1):
│   ├── Group 2: backend-tier (sequence 2, deps: data-tier)
│   │   ├── VM 1: app-server (seq 1) → START
│   │   └── VM 2: worker-queue (seq 2, deps: app-server) → WAIT → START
│   └── Group 3: cache-tier (sequence 3, deps: data-tier)
│       └── VM 1: redis-cache → START
└── Phase 3:
    └── Group 4: frontend-tier (sequence 4, deps: backend-tier + cache-tier)
        └── ... wait for both groups fully running
```

### 7.4 Sequence Configuration
Stored directly in Group and VM entities:
- `Group.sequence_position` - order within environment
- `Group.depends_on_group_ids` - which groups must be fully running first
- `VM.sequence_position` - order within group
- `VM.depends_on_vm_ids` - which VMs (same group) must be running first

### 7.5 Validation Rules (Enforced on Insert/Update)
1. **No Circular Group Dependencies:**
   - Graph traversal to detect cycles
   - Reject: Group A → B → A

2. **No Circular VM Dependencies:**
   - Check within same group
   - Reject: VM1 → VM2 → VM1

3. **VM Dependencies Within Same Group Only:**
   - Reject: VM in Group A depends on VM in Group B

4. **Sequence Positions Must Be Unique:**
   - Within environment for groups
   - Within group for VMs

---

## 8. AUDIT LOGGING & HISTORY

### 8.1 Audit Log
```
AuditLog
├── audit_id (PK, UUID)
├── user_id (FK, nullable for system actions)
├── environment_id (FK, nullable for global actions)
├── action_type (enum - comprehensive list below)
├── target_type (enum: environment, group, vm, lock, access_request, user)
├── target_id (UUID, nullable)
├── target_name (string)
├── action_status (enum: initiated, succeeded, failed)
├── details (JSON - action-specific data)
├── ip_address (string)
├── user_agent (string)
├── session_id (string)
├── timestamp
└── error_message (nullable)

INDEX: (user_id, timestamp DESC)
INDEX: (environment_id, timestamp DESC)
INDEX: (action_type, timestamp DESC)
INDEX: (timestamp DESC)
```

**Action Types:**
- VM Actions: `vm_started`, `vm_stopped`, `vm_start_failed`, `vm_stop_failed`
- Lock Actions: `lock_acquired`, `lock_released`, `lock_broken`, `lock_acquisition_failed`
- Access: `access_requested`, `access_granted`, `access_denied`, `access_revoked`
- Admin: `force_stop_executed`, `dependency_override`, `vm_registered`, `vm_deregistered`
- Group: `group_started`, `group_stopped`, `group_created`, `group_deleted`
- Environment: `environment_created`, `environment_deleted`, `environment_updated`
- User: `user_logged_in`, `user_logged_out`, `user_role_changed`
- System: `state_sync_completed`, `state_drift_detected`, `cloud_api_error`

### 8.2 VM State History (for drift detection)
```
VMStateHistory
├── history_id (PK, UUID)
├── vm_id (FK)
├── previous_status (enum)
├── new_status (enum)
├── change_source (enum: user_action, state_sync, cloud_event, system)
├── changed_by_user_id (FK, nullable)
├── timestamp
└── metadata (JSON)

INDEX: (vm_id, timestamp DESC)
```

### 8.3 Login Session Tracking
```
LoginSession
├── session_id (PK, UUID)
├── vm_id (FK)
├── user_id (FK)
├── session_type (enum: ssh, rdp, console)
├── source_ip (string)
├── started_at
├── ended_at (nullable)
├── duration_minutes (computed)
└── metadata (JSON)

INDEX: (vm_id, started_at DESC)
INDEX: (user_id, started_at DESC)
```

**Privacy Note:** PII in login sessions must be encrypted at rest.

---

## 9. SUPPORTING TABLES

### 9.1 System Configuration
```
SystemConfig
├── config_id (PK, UUID)
├── config_key (unique, string)
├── config_value (JSON)
├── description (text)
├── updated_by_user_id (FK)
└── updated_at

-- Example configs:
-- 'state_sync_interval_seconds': 300
-- 'lock_warning_threshold_hours': 4
-- 'max_concurrent_operations': 20
-- 'notification_retention_days': 90
```

### 9.2 Cloud Provider Credentials (Secure)
```
CloudProviderCredential
├── credential_id (PK, UUID)
├── provider (enum: AWS, GCP, AZURE, OCI)
├── credential_name (string)
├── credential_type (enum: service_account, access_key, etc.)
├── encrypted_credentials (text - encrypted JSON)
├── encryption_key_version (integer)
├── is_active (boolean)
├── created_by_user_id (FK)
├── created_at
├── rotated_at (nullable)
└── expires_at (nullable)
```

**Security:** Credentials encrypted using Key Management Service (KMS).

### 9.3 Error Code Catalog (Optional)
```
ErrorCode
├── error_code (PK, string - e.g., "AWS_001")
├── provider (enum)
├── error_category (enum: authentication, rate_limit, quota, resource_not_found, etc.)
├── description (text)
├── user_friendly_message (text)
├── suggested_action (text)
└── documentation_url (string)
```

### 9.4 Job Scheduling (for background tasks)
```
ScheduledJob
├── job_id (PK, UUID)
├── job_type (enum: state_sync, notification_cleanup, lock_warning, etc.)
├── schedule_cron (string)
├── is_enabled (boolean)
├── last_run_at (nullable)
├── last_run_status (enum)
├── next_run_at (computed)
└── configuration (JSON)

JobExecutionLog
├── execution_id (PK, UUID)
├── job_id (FK)
├── started_at
├── completed_at (nullable)
├── status (enum: running, completed, failed)
├── records_processed (integer)
├── error_message (nullable)
└── execution_details (JSON)
```

---

## 10. DATABASE CONSTRAINTS & INDEXES

### Key Indexes for Performance:
1. **EnvironmentAccess:** `(user_id, status)` - user's active environments
2. **VM:** `(group_id, sequence_position)` - group's VMs in order
3. **VM:** `(status)` - filter by running/stopped
4. **EnvironmentLock:** `(environment_id, is_active)` - check active locks
5. **Notification:** `(user_id, is_read, created_at DESC)` - unread notifications
6. **AuditLog:** `(timestamp DESC)` - recent activity
7. **VMProviderDetails:** `(provider, provider_vm_id)` - lookup by cloud ID

### Foreign Key Relationships:
- All user_id columns reference User(user_id) with ON DELETE RESTRICT
- All environment_id columns reference Environment(environment_id) with ON DELETE CASCADE
- VM.group_id references Group(group_id) with ON DELETE CASCADE
- VMProviderDetails.vm_id references VM(vm_id) with ON DELETE CASCADE

### Unique Constraints:
- Environment.name
- (Environment.id, Group.name)
- (Group.id, VM.name)
- (Group.id, VM.sequence_position)
- (VM.provider, VM.provider_vm_id)
- EnvironmentLock.environment_id WHERE is_active = true

---

## 11. KEY QUESTIONS TO DISCUSS

### 11.1 User & Access Management
- [x] ~~Should we support team/group-based access or only individual users?~~ **DECIDED: Individual users only**
- [x] ~~Role model?~~ **DECIDED: Two boolean flags (admin, env_admin) + access_level in EnvironmentAccess**
- [ ] Should we support temporary access with auto-expiration? **YES - via expires_at in EnvironmentAccess**
- [ ] Should access requests expire if not reviewed within X days?
- [ ] Can one user be env_admin for multiple environments? **YES - via multiple EnvironmentAccess entries**
- [ ] Can global admins be demoted, or is it permanent?
- [ ] Should we log all admin privilege changes?

### 11.2 Lock Management
- [ ] Should we send warnings when lock held > X hours?
- [ ] Should there be a maximum lock duration?
- [ ] Should lock breaking require approval from multiple admins?
- [ ] What happens to in-progress operations when lock is broken?

### 11.3 Notifications
- [ ] Should we support email notifications in addition to in-app?
- [ ] Should users be able to subscribe to specific environments?
- [ ] How long should we retain notifications?
- [ ] Should we batch notifications (e.g., daily digest)?

### 11.4 State Management
- [ ] How do we handle VMs that are started/stopped outside platform?
- [ ] Should we auto-correct drift or just notify?
- [ ] What's the reconciliation strategy for conflicting states?
- [ ] Should we maintain full state history or just current + last?

### 11.5 Dependencies
- [ ] Should we support "soft" dependencies (warning but not blocking)?
- [ ] Should dependencies be bidirectional for stop operations?
- [ ] How do we handle optional VMs in a dependency chain?
- [ ] Should we support "OR" dependencies (A OR B must be running)?

### 11.6 Scalability
- [ ] How do we handle 50 environments with 750 VMs?
- [ ] Should we implement pagination for large environments?
- [ ] Should we cache frequently accessed data?
- [ ] What's the strategy for handling slow cloud provider APIs?

### 11.7 Monitoring & Observability
- [ ] Should we expose metrics (Prometheus, etc.)?
- [ ] What health checks do we need?
- [ ] Should we track operation latency by cloud provider?
- [ ] Do we need SLA tracking per environment?

---

## 12. TECHNOLOGY STACK CONSIDERATIONS

### 12.1 Database
- **PostgreSQL** (recommended): JSON support, strong ACID, row-level locking
- **MySQL**: Alternative, but weaker JSON support
- **MongoDB**: Not recommended - need strong transactions

### 12.2 Caching Layer
- **Redis**: For session management, real-time status, notification delivery
- Cache keys:
  - `env:locks:{env_id}` - active lock status
  - `vm:status:{vm_id}` - current VM status
  - `user:environments:{user_id}` - user's accessible environments
  - `notifications:unread:{user_id}` - unread notification count

### 12.3 Message Queue
- **RabbitMQ** or **AWS SQS**: For async operations
- Queues:
  - `vm.start.queue` - VM start operations
  - `vm.stop.queue` - VM stop operations
  - `state.sync.queue` - State reconciliation jobs
  - `notification.send.queue` - Notification delivery

---

## 13. NEXT STEPS

1. **Review & Refine:** Go through each entity, discuss questions
2. **Prioritize:** What's MVP vs nice-to-have?
3. **Create ERD:** Visual diagram of all relationships
4. **Define APIs:** REST endpoints for each operation
5. **Write Schema:** Actual DDL scripts (CREATE TABLE statements)
6. **Seed Data:** Sample data for development
7. **Migration Strategy:** How to handle schema changes

---

## APPENDIX: Sample Data

### Sample Environment
```json
{
  "environment_id": "uuid-env-1",
  "name": "mcube-demo-environment",
  "display_name": "MCube Demo Environment",
  "description": "Development environment for MCube project",
  "is_active": true
}
```

### Sample Groups
```json
[
  {
    "group_id": "uuid-group-1",
    "environment_id": "uuid-env-1",
    "name": "data-tier",
    "sequence_position": 1,
    "depends_on_group_ids": []
  },
  {
    "group_id": "uuid-group-2",
    "environment_id": "uuid-env-1",
    "name": "backend-tier",
    "sequence_position": 2,
    "depends_on_group_ids": ["uuid-group-1"]
  }
]
```

### Sample VMs
```json
{
  "vm_id": "uuid-vm-1",
  "group_id": "uuid-group-1",
  "name": "database-primary",
  "provider": "AWS",
  "region": "us-east-1",
  "provider_vm_id": "i-0123456789abcdef0",
  "vm_type": "dev",
  "sequence_position": 1,
  "depends_on_vm_ids": [],
  "status": "running"
}
```

---

**Document Status:** Draft for discussion
**Last Updated:** Based on mockup and requirements v1.0
