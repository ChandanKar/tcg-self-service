-- U01-T06: AWS instance ids are for administrators only, but discovery named VMs without a Name tag after
-- their instance id. Rename them to the placeholder discovery now uses: 'unnamed-' and the first 6 hex of
-- SHA-256(provider_vm_id) (UnnamedVmNames). State sync still replaces it when a Name tag appears.
-- A rename that would clash with a name already used in the same group is skipped (unique group_id, name);
-- the GROUP BY makes MySQL materialise the derived table so vm can be updated while it is read.
UPDATE vm v
LEFT JOIN (SELECT group_id, name FROM vm GROUP BY group_id, name) taken
       ON taken.group_id = v.group_id
      AND taken.name = CONCAT('unnamed-', LEFT(SHA2(v.provider_vm_id, 256), 6))
SET v.name = CONCAT('unnamed-', LEFT(SHA2(v.provider_vm_id, 256), 6))
WHERE v.provider = 'AWS'
  AND LOWER(v.name) = LOWER(v.provider_vm_id)
  AND taken.name IS NULL;

UPDATE vm
SET display_name = CONCAT('unnamed-', LEFT(SHA2(provider_vm_id, 256), 6))
WHERE provider = 'AWS'
  AND LOWER(display_name) = LOWER(provider_vm_id);
