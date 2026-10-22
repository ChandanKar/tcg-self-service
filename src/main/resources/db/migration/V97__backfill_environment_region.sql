-- U19-T04: an environment without a stored region gets the one region all its active VMs share,
-- so its cluster or instances are never looked up in aws.region by mistake. Environments whose VMs
-- span several regions, or that have none, are left as they are (the app reports them instead).
-- Metadata that is not a JSON object is never touched.
UPDATE environment e
JOIN (
    SELECT g.environment_id, MIN(v.region) AS region
    FROM vm v
    JOIN vm_group g ON g.group_id = v.group_id
    WHERE v.is_active = TRUE AND v.region IS NOT NULL AND TRIM(v.region) <> ''
    GROUP BY g.environment_id
    HAVING COUNT(DISTINCT v.region) = 1
) r ON r.environment_id = e.environment_id
SET e.metadata = JSON_SET(CASE WHEN e.metadata IS NULL OR TRIM(e.metadata) = '' THEN '{}' ELSE e.metadata END,
                          '$.region', r.region)
WHERE CASE
    WHEN e.metadata IS NULL OR TRIM(e.metadata) = '' THEN TRUE
    WHEN NOT JSON_VALID(e.metadata) THEN FALSE
    WHEN JSON_TYPE(e.metadata) <> 'OBJECT' THEN FALSE
    WHEN JSON_EXTRACT(e.metadata, '$.region') IS NULL THEN TRUE
    WHEN JSON_TYPE(JSON_EXTRACT(e.metadata, '$.region')) <> 'STRING' THEN TRUE
    ELSE TRIM(JSON_UNQUOTE(JSON_EXTRACT(e.metadata, '$.region'))) = ''
END;
