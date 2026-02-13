-- =====================================================
-- VM Platform - Useful SQL Queries Reference
-- =====================================================

-- =====================================================
-- USER MANAGEMENT
-- =====================================================

-- Find all global admins
SELECT user_id, email, display_name, last_login_at
FROM user
WHERE admin = TRUE
ORDER BY display_name;

-- Find all environment admins
SELECT DISTINCT u.user_id, u.email, u.display_name
FROM user u
WHERE u.env_admin = TRUE
ORDER BY u.display_name;

-- Find environments where a user is admin
SELECT e.environment_id, e.name, e.display_name, ea.access_level
FROM environment e
JOIN environment_access ea ON e.environment_id = ea.environment_id
WHERE ea.user_id = ? 
  AND ea.status = 'active'
  AND ea.access_level = 'admin';

-- =====================================================
-- ENVIRONMENT ACCESS
-- =====================================================

-- Get all environments a user can access
SELECT e.environment_id, e.name, e.display_name, ea.access_level
FROM environment e
JOIN environment_access ea ON e.environment_id = ea.environment_id
WHERE ea.user_id = ?
  AND ea.status = 'active'
  AND (ea.expires_at IS NULL OR ea.expires_at > CURRENT_TIMESTAMP)
ORDER BY e.name;

-- Find pending access requests for an environment
SELECT 
    ear.request_id,
    ear.environment_id,
    e.name as environment_name,
    u.display_name as requester,
    u.email as requester_email,
    ear.requested_access_level,
    ear.business_justification,
    ear.created_at
FROM environment_access_request ear
JOIN environment e ON ear.environment_id = e.environment_id
JOIN user u ON ear.requester_user_id = u.user_id
WHERE ear.status = 'pending'
  AND ear.environment_id = ?
ORDER BY ear.created_at ASC;

-- Find users with access to an environment
SELECT 
    u.user_id,
    u.display_name,
    u.email,
    ea.access_level,
    ea.granted_at,
    ea.expires_at,
    gu.display_name as granted_by
FROM environment_access ea
JOIN user u ON ea.user_id = u.user_id
JOIN user gu ON ea.granted_by_user_id = gu.user_id
WHERE ea.environment_id = ?
  AND ea.status = 'active'
ORDER BY ea.access_level DESC, u.display_name;

-- =====================================================
-- ENVIRONMENT & VM STATUS
-- =====================================================

-- Get environment overview with VM counts
SELECT 
    e.environment_id,
    e.name,
    e.display_name,
    COUNT(DISTINCT g.group_id) as group_count,
    COUNT(v.vm_id) as total_vms,
    SUM(CASE WHEN v.status = 'running' THEN 1 ELSE 0 END) as running_vms,
    SUM(CASE WHEN v.status = 'stopped' THEN 1 ELSE 0 END) as stopped_vms,
    SUM(CASE WHEN v.status = 'error' THEN 1 ELSE 0 END) as error_vms
FROM environment e
LEFT JOIN vm_group g ON e.environment_id = g.environment_id
LEFT JOIN vm v ON g.group_id = v.group_id
WHERE e.is_active = TRUE
GROUP BY e.environment_id, e.name, e.display_name
ORDER BY e.name;

-- Get all VMs in an environment with group info
SELECT 
    g.name as group_name,
    g.sequence_position as group_sequence,
    v.vm_id,
    v.name as vm_name,
    v.provider,
    v.region,
    v.status,
    v.sequence_position as vm_sequence,
    v.depends_on_vm_ids,
    v.last_state_sync_at
FROM vm_group g
JOIN vm v ON g.group_id = v.group_id
WHERE g.environment_id = ?
ORDER BY g.sequence_position, v.sequence_position;

-- Find VMs with state drift
SELECT 
    e.name as environment,
    g.name as group_name,
    v.name as vm_name,
    v.provider,
    v.status,
    v.last_known_state,
    v.last_state_sync_at
FROM vm v
JOIN vm_group g ON v.group_id = g.group_id
JOIN environment e ON g.environment_id = e.environment_id
WHERE v.state_drift_detected = TRUE
ORDER BY v.last_state_sync_at DESC;

-- =====================================================
-- LOCK MANAGEMENT
-- =====================================================

-- Check if environment is locked
SELECT 
    el.lock_id,
    el.environment_id,
    e.name as environment_name,
    u.display_name as locked_by,
    u.email as locked_by_email,
    el.locked_at,
    el.lock_reason,
    TIMESTAMPDIFF(MINUTE, el.locked_at, CURRENT_TIMESTAMP) as minutes_held
FROM environment_lock el
JOIN environment e ON el.environment_id = e.environment_id
JOIN user u ON el.locked_by_user_id = u.user_id
WHERE el.environment_id = ?
  AND el.is_active = TRUE;

-- Find all active locks (for admin dashboard)
SELECT 
    el.lock_id,
    e.name as environment_name,
    u.display_name as locked_by,
    u.email as locked_by_email,
    el.locked_at,
    el.lock_reason,
    TIMESTAMPDIFF(MINUTE, el.locked_at, CURRENT_TIMESTAMP) as minutes_held
FROM environment_lock el
JOIN environment e ON el.environment_id = e.environment_id
JOIN user u ON el.locked_by_user_id = u.user_id
WHERE el.is_active = TRUE
ORDER BY el.locked_at ASC;

-- Find locks held longer than threshold (e.g., 4 hours)
SELECT 
    el.lock_id,
    e.name as environment_name,
    u.display_name as locked_by,
    u.email as locked_by_email,
    el.locked_at,
    TIMESTAMPDIFF(HOUR, el.locked_at, CURRENT_TIMESTAMP) as hours_held
FROM environment_lock el
JOIN environment e ON el.environment_id = e.environment_id
JOIN user u ON el.locked_by_user_id = u.user_id
WHERE el.is_active = TRUE
  AND TIMESTAMPDIFF(HOUR, el.locked_at, CURRENT_TIMESTAMP) >= 4
ORDER BY el.locked_at ASC;

-- Lock history for an environment
SELECT 
    lh.action,
    u.display_name as performed_by,
    lh.timestamp,
    lh.notes
FROM lock_history lh
JOIN user u ON lh.performed_by_user_id = u.user_id
WHERE lh.environment_id = ?
ORDER BY lh.timestamp DESC
LIMIT 50;

-- =====================================================
-- OPERATIONS & EXECUTION
-- =====================================================

-- Get recent operations for an environment
SELECT 
    oe.execution_id,
    oe.operation_type,
    u.display_name as initiated_by,
    oe.status,
    oe.started_at,
    oe.completed_at,
    oe.total_targets,
    oe.completed_targets,
    oe.failed_targets,
    TIMESTAMPDIFF(SECOND, oe.started_at, COALESCE(oe.completed_at, CURRENT_TIMESTAMP)) as duration_seconds
FROM operation_execution oe
JOIN user u ON oe.initiated_by_user_id = u.user_id
WHERE oe.environment_id = ?
ORDER BY oe.started_at DESC
LIMIT 20;

-- Get operation details (drill down)
SELECT 
    od.target_type,
    od.target_name,
    od.action,
    od.sequence_position,
    od.status,
    od.started_at,
    od.completed_at,
    od.error_message
FROM operation_detail od
WHERE od.execution_id = ?
ORDER BY od.sequence_position;

-- Find currently in-progress operations
SELECT 
    oe.execution_id,
    e.name as environment,
    oe.operation_type,
    u.display_name as initiated_by,
    oe.started_at,
    oe.completed_targets,
    oe.total_targets
FROM operation_execution oe
JOIN environment e ON oe.environment_id = e.environment_id
JOIN user u ON oe.initiated_by_user_id = u.user_id
WHERE oe.status = 'in_progress'
ORDER BY oe.started_at;

-- =====================================================
-- NOTIFICATIONS
-- =====================================================

-- Get unread notifications for a user
SELECT 
    n.notification_id,
    n.notification_type,
    n.title,
    n.message,
    n.severity,
    n.created_at,
    n.related_entity_type,
    n.action_url
FROM notification n
WHERE n.user_id = ?
  AND n.is_read = FALSE
  AND n.is_dismissed = FALSE
ORDER BY n.created_at DESC;

-- Count unread notifications by severity
SELECT 
    severity,
    COUNT(*) as count
FROM notification
WHERE user_id = ?
  AND is_read = FALSE
  AND is_dismissed = FALSE
GROUP BY severity;

-- Mark notifications as read
UPDATE notification
SET is_read = TRUE, read_at = CURRENT_TIMESTAMP
WHERE user_id = ?
  AND notification_id IN (?, ?, ?);

-- =====================================================
-- AUDIT LOG QUERIES
-- =====================================================

-- Recent audit log for an environment
SELECT 
    al.audit_id,
    u.display_name as user_name,
    al.action_type,
    al.target_type,
    al.target_name,
    al.action_status,
    al.timestamp,
    al.ip_address
FROM audit_log al
LEFT JOIN user u ON al.user_id = u.user_id
WHERE al.environment_id = ?
ORDER BY al.timestamp DESC
LIMIT 100;

-- User activity log
SELECT 
    al.timestamp,
    e.name as environment,
    al.action_type,
    al.target_type,
    al.target_name,
    al.action_status,
    al.ip_address
FROM audit_log al
LEFT JOIN environment e ON al.environment_id = e.environment_id
WHERE al.user_id = ?
ORDER BY al.timestamp DESC
LIMIT 100;

-- Failed operations audit
SELECT 
    al.timestamp,
    u.display_name as user_name,
    e.name as environment,
    al.action_type,
    al.target_name,
    al.error_message
FROM audit_log al
LEFT JOIN user u ON al.user_id = u.user_id
LEFT JOIN environment e ON al.environment_id = e.environment_id
WHERE al.action_status = 'failed'
  AND al.timestamp >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY)
ORDER BY al.timestamp DESC;

-- Lock break audit (admin actions)
SELECT 
    al.timestamp,
    admin.display_name as admin_name,
    e.name as environment,
    al.details,  -- Contains lock holder info
    al.error_message as break_reason
FROM audit_log al
JOIN user admin ON al.user_id = admin.user_id
LEFT JOIN environment e ON al.environment_id = e.environment_id
WHERE al.action_type = 'lock_broken'
ORDER BY al.timestamp DESC;

-- =====================================================
-- VM STATE TRACKING
-- =====================================================

-- VM state change history
SELECT 
    vsh.timestamp,
    vsh.previous_status,
    vsh.new_status,
    vsh.change_source,
    u.display_name as changed_by
FROM vm_state_history vsh
LEFT JOIN user u ON vsh.changed_by_user_id = u.user_id
WHERE vsh.vm_id = ?
ORDER BY vsh.timestamp DESC
LIMIT 50;

-- VMs that changed state in last 24 hours
SELECT 
    e.name as environment,
    g.name as group_name,
    v.name as vm_name,
    vsh.previous_status,
    vsh.new_status,
    vsh.change_source,
    vsh.timestamp
FROM vm_state_history vsh
JOIN vm v ON vsh.vm_id = v.vm_id
JOIN vm_group g ON v.group_id = g.group_id
JOIN environment e ON g.environment_id = e.environment_id
WHERE vsh.timestamp >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
ORDER BY vsh.timestamp DESC;

-- =====================================================
-- DEPENDENCY QUERIES
-- =====================================================

-- Get VM startup sequence for a group
SELECT 
    v.vm_id,
    v.name,
    v.sequence_position,
    v.depends_on_vm_ids,
    v.status
FROM vm v
WHERE v.group_id = ?
ORDER BY v.sequence_position;

-- Check if all VM dependencies are running
SELECT 
    v.vm_id,
    v.name,
    v.depends_on_vm_ids,
    (
        SELECT GROUP_CONCAT(dv.status)
        FROM vm dv
        WHERE FIND_IN_SET(dv.vm_id, REPLACE(REPLACE(v.depends_on_vm_ids, '[', ''), ']', ''))
    ) as dependency_statuses
FROM vm v
WHERE v.vm_id = ?;

-- Get group startup sequence for environment
SELECT 
    g.group_id,
    g.name,
    g.sequence_position,
    g.depends_on_group_ids,
    COUNT(v.vm_id) as vm_count,
    SUM(CASE WHEN v.status = 'running' THEN 1 ELSE 0 END) as running_count
FROM vm_group g
LEFT JOIN vm v ON g.group_id = v.group_id
WHERE g.environment_id = ?
GROUP BY g.group_id, g.name, g.sequence_position, g.depends_on_group_ids
ORDER BY g.sequence_position;

-- =====================================================
-- ADMIN DASHBOARD QUERIES
-- =====================================================

-- Platform overview
SELECT 
    (SELECT COUNT(*) FROM environment WHERE is_active = TRUE) as total_environments,
    (SELECT COUNT(*) FROM vm) as total_vms,
    (SELECT COUNT(*) FROM vm WHERE status = 'running') as running_vms,
    (SELECT COUNT(*) FROM vm WHERE status = 'stopped') as stopped_vms,
    (SELECT COUNT(*) FROM environment_lock WHERE is_active = TRUE) as active_locks,
    (SELECT COUNT(*) FROM user WHERE admin = TRUE) as global_admins,
    (SELECT COUNT(*) FROM user WHERE env_admin = TRUE) as env_admins;

-- VM distribution by cloud provider
SELECT 
    provider,
    COUNT(*) as vm_count,
    SUM(CASE WHEN status = 'running' THEN 1 ELSE 0 END) as running,
    SUM(CASE WHEN status = 'stopped' THEN 1 ELSE 0 END) as stopped
FROM vm
GROUP BY provider
ORDER BY vm_count DESC;

-- Recent user activity summary
SELECT 
    u.display_name,
    u.email,
    COUNT(al.audit_id) as action_count,
    MAX(al.timestamp) as last_activity
FROM user u
LEFT JOIN audit_log al ON u.user_id = al.user_id
WHERE al.timestamp >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY)
GROUP BY u.user_id, u.display_name, u.email
ORDER BY action_count DESC
LIMIT 20;

-- =====================================================
-- CLEANUP / MAINTENANCE QUERIES
-- =====================================================

-- Find expired access to revoke
SELECT 
    access_id,
    environment_id,
    user_id,
    expires_at
FROM environment_access
WHERE status = 'active'
  AND expires_at IS NOT NULL
  AND expires_at < CURRENT_TIMESTAMP;

-- Old notifications to clean up (older than retention period)
SELECT COUNT(*)
FROM notification
WHERE created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 90 DAY);

-- Old audit logs to archive
SELECT COUNT(*)
FROM audit_log
WHERE timestamp < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 365 DAY);

-- =====================================================
-- AUTHORIZATION CHECKS (Use in Application)
-- =====================================================

-- Check if user can access environment
SELECT 
    CASE 
        WHEN u.admin = TRUE THEN TRUE
        WHEN EXISTS (
            SELECT 1 FROM environment_access ea 
            WHERE ea.environment_id = ? 
              AND ea.user_id = u.user_id 
              AND ea.status = 'active'
              AND (ea.expires_at IS NULL OR ea.expires_at > CURRENT_TIMESTAMP)
        ) THEN TRUE
        ELSE FALSE
    END as can_access
FROM user u
WHERE u.user_id = ?;

-- Check if user can break lock on environment
SELECT 
    CASE 
        WHEN u.admin = TRUE THEN TRUE
        WHEN u.env_admin = TRUE AND EXISTS (
            SELECT 1 FROM environment_access ea 
            WHERE ea.environment_id = ? 
              AND ea.user_id = u.user_id 
              AND ea.access_level = 'admin' 
              AND ea.status = 'active'
        ) THEN TRUE
        ELSE FALSE
    END as can_break_lock
FROM user u
WHERE u.user_id = ?;

-- Check if user can grant access to environment
SELECT 
    CASE 
        WHEN u.admin = TRUE THEN TRUE
        WHEN u.env_admin = TRUE AND EXISTS (
            SELECT 1 FROM environment_access ea 
            WHERE ea.environment_id = ? 
              AND ea.user_id = u.user_id 
              AND ea.access_level = 'admin' 
              AND ea.status = 'active'
        ) THEN TRUE
        ELSE FALSE
    END as can_grant_access
FROM user u
WHERE u.user_id = ?;
