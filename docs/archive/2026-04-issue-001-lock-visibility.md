# ISSUE-001: Lock Visibility Bug - Users Cannot See Who Locked an Environment

**Created:** April 7, 2026  
**Status:** ✅ Fixed  
**Priority:** High  
**Component:** Lock Management  
**Fixed:** April 7, 2026  

---

## Summary

Users cannot see the lock status or who locked an environment. The dashboard and environment detail views show incorrect information because of a mismatch between backend API response and frontend expectations.

---

## Problem Description

### Symptoms
1. Lock status column in dashboard always shows "🔓 Unlocked" even when environment is locked
2. When lock IS detected, it shows "🔒 another user" instead of the actual user's name
3. Lock banner in environment detail view doesn't display correctly

### Root Cause

| Field | Backend Returns | Frontend Expects | Impact |
|-------|-----------------|------------------|--------|
| Lock status | `locked: true` | `isLocked: true` | Lock appears unlocked |
| Display name | *(missing)* | `lockedByDisplayName` | Shows "another user" |

### Affected Files

**Backend (needs fix):**
- `src/main/java/com/tcgdigital/vmcontrol/dto/LockStatusDTO.java`
- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java`

**Frontend (correct, no changes needed):**
- `src/main/resources/static/js/features/dashboard.js`
- `src/main/resources/static/js/features/environments.js`
- `src/main/resources/static/js/features/locks.js`

---

## Current API Response (Broken)

```json
{
  "locked": true,
  "lockId": "lock-123",
  "environmentId": "env-456",
  "lockedByUserId": "user-789",
  "lockedAt": "2026-04-07T10:30:00Z",
  "lockReason": "Deploying new version",
  "expectedDurationMinutes": 60
}
```

## Expected API Response (Fixed)

```json
{
  "isLocked": true,
  "lockId": "lock-123",
  "environmentId": "env-456",
  "lockedByUserId": "user-789",
  "lockedByDisplayName": "John Doe",
  "lockedAt": "2026-04-07T10:30:00Z",
  "lockReason": "Deploying new version",
  "expectedDurationMinutes": 60
}
```

---

## Frontend Display Specification

### 1. Dashboard / Environments List (Table View)

Lock status appears in the **Lock Status** column:

| Scenario | Display |
|----------|---------|
| Unlocked | `🔓 Unlocked` (green text) |
| Locked by current user | `🔒 you` (yellow/warning text) |
| Locked by another user | `🔒 {displayName}` (yellow/warning text) |

**Example Table:**
```
┌──────────────────┬──────────┬─────────┬─────────────────────┬───────┐
│ Environment      │ Total VMs│ Running │ Lock Status         │ Cloud │
├──────────────────┼──────────┼─────────┼─────────────────────┼───────┤
│ Production       │ 12       │ 12/12   │ 🔒 John Doe         │ ☁️    │
│ Staging          │ 8        │ 0/8     │ 🔓 Unlocked         │ ☁️    │
│ Development      │ 4        │ 4/4     │ 🔒 you              │ ☁️    │
└──────────────────┴──────────┴─────────┴─────────────────────┴───────┘
```

**Frontend Logic:**
```javascript
if (env.lockStatus && env.lockStatus.isLocked) {
    const lockedBy = env.lockStatus.lockedByUserId === Auth.getUserId() 
        ? 'you' 
        : (env.lockStatus.lockedByDisplayName || 'another user');
    lockDisplay = `<span class="text-warning"><i class="fas fa-lock"></i> ${lockedBy}</span>`;
} else {
    lockDisplay = `<span class="text-success"><i class="fas fa-unlock"></i> Unlocked</span>`;
}
```

---

### 2. Environment Detail View (Lock Banner)

A banner appears at the top of the environment detail page.

#### State: Unlocked
```
╔═══════════════════════════════════════════════════════════════════╗
║ 🟢 Background: Green gradient (#d1fae5 → #a7f3d0)                 ║
╠═══════════════════════════════════════════════════════════════════╣
║  🔓  This environment is unlocked                                 ║
║                                                                   ║
║                              [📜 History]  [🔒 Acquire Lock]      ║
╚═══════════════════════════════════════════════════════════════════╝
```
- **Buttons:** History, Acquire Lock
- **Available to:** All authenticated users

#### State: Locked by Current User
```
╔═══════════════════════════════════════════════════════════════════╗
║ 🟡 Background: Amber gradient (#fef3c7 → #fde68a)                 ║
╠═══════════════════════════════════════════════════════════════════╣
║  🔒  Locked by you (2 hours ago)                                  ║
║      💬 Deploying new version                                     ║
║                                                                   ║
║                              [📜 History]  [🔓 Release Lock]      ║
╚═══════════════════════════════════════════════════════════════════╝
```
- **Buttons:** History, Release Lock
- **Lock reason:** Displayed if provided

#### State: Locked by Another User (Regular User View)
```
╔═══════════════════════════════════════════════════════════════════╗
║ 🟡 Background: Amber gradient (#fef3c7 → #fde68a)                 ║
╠═══════════════════════════════════════════════════════════════════╣
║  🔒  Locked by John Doe (30 minutes ago)                          ║
║      💬 Running maintenance                                       ║
║                                                                   ║
║                              [📜 History]                         ║
╚═══════════════════════════════════════════════════════════════════╝
```
- **Buttons:** History only
- **No action buttons** for non-lock-holders

#### State: Locked by Another User (Admin View)
```
╔═══════════════════════════════════════════════════════════════════╗
║ 🟡 Background: Amber gradient (#fef3c7 → #fde68a)                 ║
╠═══════════════════════════════════════════════════════════════════╣
║  🔒  Locked by John Doe (30 minutes ago)                          ║
║      💬 Running maintenance                                       ║
║                                                                   ║
║                              [📜 History]  [🔨 Break Lock]        ║
╚═══════════════════════════════════════════════════════════════════╝
```
- **Buttons:** History, Break Lock
- **Break Lock:** Requires ADMIN or ENV_ADMIN role
- **Break Lock:** Requires mandatory reason

---

### 3. Lock Action Modals

#### Acquire Lock Modal
```
┌─────────────────────────────────────────────────────────────┐
│ 🔒 Acquire Environment Lock                            [X] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ Reason for acquiring lock                                   │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ e.g., Deploying new version, Running maintenance...    │ │
│ └─────────────────────────────────────────────────────────┘ │
│ Optional but recommended for team visibility                │
│                                                             │
│ Expected duration                                           │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ Until manually released                              ▼ │ │
│ └─────────────────────────────────────────────────────────┘ │
│ Options: 30 min, 1 hour, 2 hours, 4 hours, 8 hours         │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                          [Cancel]  [🔒 Acquire Lock]        │
└─────────────────────────────────────────────────────────────┘
```

#### Release Lock Modal
```
┌─────────────────────────────────────────────────────────────┐
│ 🔓 Release Lock                                        [X] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ Are you sure you want to release the lock on this          │
│ environment?                                                │
│                                                             │
│ Other users will be able to lock and modify this           │
│ environment.                                                │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                          [Cancel]  [🔓 Release Lock]        │
└─────────────────────────────────────────────────────────────┘
```

#### Break Lock Modal (Admin Only)
```
┌─────────────────────────────────────────────────────────────┐
│ ⚠️ Break Lock                                          [X] │
├─────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ ⚠️ Warning: This will forcibly remove the lock held    │ │
│ │    by John Doe.                                        │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                             │
│ Reason for breaking lock *                                  │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ Explain why you need to break this lock...             │ │
│ └─────────────────────────────────────────────────────────┘ │
│ This will be logged and the lock holder will be notified.  │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                          [Cancel]  [🔨 Break Lock]          │
└─────────────────────────────────────────────────────────────┘
```

---

### 4. Lock History Modal

```
┌─────────────────────────────────────────────────────────────────────┐
│ 📜 Lock History: Production                                    [X] │
├─────────────────────────────────────────────────────────────────────┤
│ ┌─────────┬─────────────┬───────────┬──────────────────┬──────────┐ │
│ │ Time    │ User        │ Action    │ Reason           │ Duration │ │
│ ├─────────┼─────────────┼───────────┼──────────────────┼──────────┤ │
│ │ 2h ago  │ John Doe    │ 🟢 Acquired│ Deployment      │ Active   │ │
│ │ 5h ago  │ Jane Smith  │ ⚪ Released│ Maintenance     │ 2h 30m   │ │
│ │ 1d ago  │ Admin       │ 🔴 Broken  │ Emergency fix   │ 4h 15m   │ │
│ │ 2d ago  │ Bob Wilson  │ ⚪ Released│ Testing         │ 1h 00m   │ │
│ └─────────┴─────────────┴───────────┴──────────────────┴──────────┘ │
├─────────────────────────────────────────────────────────────────────┤
│                                                      [Close]        │
└─────────────────────────────────────────────────────────────────────┘
```

**Action Badges:**
- 🟢 `Acquired` - Green badge
- ⚪ `Released` - Gray badge  
- 🔴 `Broken` - Red badge
- 🟡 `Expired` - Yellow badge

---

## Solution

### File 1: `LockStatusDTO.java`

**Changes:**
1. Add `@JsonProperty("isLocked")` annotation to serialize as `isLocked`
2. Add `lockedByDisplayName` field
3. Add overloaded `fromEntity()` method accepting display name

```java
package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;

import java.sql.Timestamp;

public class LockStatusDTO {

    private boolean locked;
    private String lockId;
    private String environmentId;
    private String lockedByUserId;
    private String lockedByDisplayName;  // NEW FIELD
    private Timestamp lockedAt;
    private String lockReason;
    private Integer expectedDurationMinutes;

    // ...existing code...

    public static LockStatusDTO fromEntity(EnvironmentLock lock) {
        // ...existing code...
    }

    // NEW METHOD
    public static LockStatusDTO fromEntity(EnvironmentLock lock, String displayName) {
        LockStatusDTO dto = fromEntity(lock);
        dto.setLockedByDisplayName(displayName);
        return dto;
    }

    @JsonProperty("isLocked")  // ADD THIS ANNOTATION
    public boolean isLocked() {
        return locked;
    }

    // ...existing code...

    // NEW GETTER/SETTER
    public String getLockedByDisplayName() {
        return lockedByDisplayName;
    }

    public void setLockedByDisplayName(String lockedByDisplayName) {
        this.lockedByDisplayName = lockedByDisplayName;
    }
}
```

### File 2: `LockController.java`

**Changes:**
1. Add import for `User` model
2. Update `getLockStatus()` to resolve display name
3. Update `acquireLock()` to include display name
4. Add private helper method `resolveDisplayName()`

```java
// Add import
import com.tcgdigital.vmcontrol.model.User;

// Update getLockStatus method
public ResponseEntity<LockStatusDTO> getLockStatus(@PathVariable String environmentId) {
    Optional<EnvironmentLock> lock = lockService.getCurrentLock(environmentId);

    if (lock.isPresent()) {
        String displayName = resolveDisplayName(lock.get().getLockedByUserId());
        return ResponseEntity.ok(LockStatusDTO.fromEntity(lock.get(), displayName));
    } else {
        return ResponseEntity.ok(LockStatusDTO.noLock());
    }
}

// Update acquireLock method
public ResponseEntity<LockStatusDTO> acquireLock(...) {
    // ...existing code...
    EnvironmentLock lock = lockService.acquireLock(environmentId, effectiveUserId, reason, duration);
    String displayName = resolveDisplayName(lock.getLockedByUserId());
    return ResponseEntity.ok(LockStatusDTO.fromEntity(lock, displayName));
}

// Add helper method
private String resolveDisplayName(String userId) {
    try {
        User user = userService.getUserById(userId);
        return user.getDisplayName() != null ? user.getDisplayName() : userId;
    } catch (Exception e) {
        return userId;  // Fallback if user not found
    }
}
```

---

## Testing

### Manual Test Cases

1. **Unlocked environment**: Verify shows "🔓 Unlocked"
2. **Lock as User A**: Verify shows "🔒 you" for User A
3. **View as User B**: Verify shows "🔒 {User A's name}" for User B
4. **Release lock**: Verify reverts to "🔓 Unlocked"
5. **Break lock as Admin**: Verify shows admin's action in history

### API Test

```bash
# Get lock status
curl -X GET http://localhost:8080/api/v1/environments/{envId}/lock \
  -H "Authorization: Bearer {token}"

# Expected response when locked:
{
  "isLocked": true,
  "lockedByUserId": "user-123",
  "lockedByDisplayName": "John Doe",
  ...
}
```

---

## Acceptance Criteria

- [x] Dashboard table shows correct lock status icon and user name
- [x] Environment detail shows lock banner with correct state
- [x] "Locked by you" displays when current user holds lock
- [x] "Locked by {name}" displays actual user name, not "another user"
- [x] Lock/Release/Break actions work correctly
- [x] Lock history displays correct information
- [x] API returns `isLocked` instead of `locked`
- [x] API returns `lockedByDisplayName` field

---

## Related Files

- `src/main/java/com/tcgdigital/vmcontrol/dto/LockStatusDTO.java`
- `src/main/java/com/tcgdigital/vmcontrol/dto/LockHistoryDTO.java`
- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java`
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java`
- `src/main/resources/static/js/features/locks.js`
- `src/main/resources/static/js/features/dashboard.js`
- `src/main/resources/static/css/components/locks.css`

---

## Additional Fix: Lock History Popup

### Problem
Lock history popup was not showing time, user, reason & duration because of field name mismatches.

### Field Mapping Fixed

| Frontend Expects | Backend Returned | Fix Applied |
|-----------------|------------------|-------------|
| `timestamp` | `performedAt` | Added `@JsonProperty("timestamp")` |
| `userId` | `performedByUserId` | Added `@JsonProperty("userId")` |
| `userDisplayName` | *(missing)* | Added field + controller resolution |
| `reason` | `notes` | Added `@JsonProperty("reason")` getter |
| `acquiredAt` | *(missing)* | Added from parent `EnvironmentLock` |
| `releasedAt` | *(missing)* | Added from parent `EnvironmentLock` |

### Files Changed
- `LockHistoryDTO.java` - Added missing fields and JSON property aliases
- `LockController.java` - Updated `getLockHistory()` to resolve display names

---

## Additional Fix: Operation History Popup

### Problem
Operation History popup was not showing time, operation type, status, duration, and user name correctly.

### Field Mapping Fixed

| Frontend Expects | Backend Returned | Fix Applied |
|-----------------|------------------|-------------|
| `createdAt` | `startedAt` | Added `@JsonProperty("createdAt")` |
| `initiatedBy` | `initiatedByUserId` | Added `@JsonProperty("initiatedBy")` |
| `initiatedByDisplayName` | *(missing)* | Added field + controller resolution |

### Files Changed
- `OperationExecutionDTO.java` - Added `initiatedByDisplayName`, `createdAt` alias, `initiatedBy` alias
- `VmOperationsController.java` - Updated `listOperations()` and `getOperation()` to resolve display names

