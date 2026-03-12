#!/usr/bin/env python3
"""
Generate V4 Migration SQL from vm-master.sql
Parses VM master data and creates complete Flyway migration
"""

import re
import json
from datetime import datetime

def parse_vm_master(sql_file):
    """Parse vm-master.sql and extract VM records"""
    with open(sql_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # Pattern to match INSERT VALUES
    pattern = r'\((\d+),\s*\'([^\']+)\',\s*\'(\[.*?\])\',\s*\'([^\']+)\',\s*\'([YN])\'\)'
    matches = re.findall(pattern, content, re.DOTALL)

    vms = []
    for match in matches:
        legacy_id, vm_name, instance_array_str, create_date, is_active = match

        # Only process active VMs
        if is_active != 'Y':
            continue

        # Parse instance array - convert Python list format to JSON
        instance_array_str = instance_array_str.replace("\\'", '"')

        try:
            instance_ids = json.loads(instance_array_str)
            vms.append({
                'legacy_id': int(legacy_id),
                'vm_name': vm_name,
                'instance_ids': instance_ids,
                'create_date': create_date,
                'is_active': is_active
            })
        except json.JSONDecodeError as e:
            print(f"WARNING: Error parsing ID {legacy_id} ({vm_name}): {e}")

    return vms

def escape_sql_string(s):
    """Escape single quotes for SQL"""
    return s.replace("'", "''")

def normalize_name(name):
    """Convert name to lowercase-hyphenated format"""
    # Replace special chars with hyphens
    normalized = re.sub(r'[^a-zA-Z0-9-]', '-', name)
    # Remove multiple consecutive hyphens
    normalized = re.sub(r'-+', '-', normalized)
    # Remove leading/trailing hyphens
    normalized = normalized.strip('-')
    return normalized.lower()

def generate_v4_migration(vms, output_file):
    """Generate complete V4 migration SQL"""

    with open(output_file, 'w', encoding='utf-8') as f:
        # Header
        f.write("""-- =====================================================
-- Flyway Migration: V4__migrate_legacy_vms.sql
-- Purpose: Migrate legacy VM data to new environment structure
-- Generated: {date}
--
-- Strategy:
-- - Each unique vm_name becomes one environment
-- - instance_id contains JSON array of AWS instance IDs
-- - Each instance in array becomes a separate VM
-- - One default group per environment
--
-- Statistics:
-- - Active VMs: {vm_count}
-- - Total Instances: {instance_count}
-- =====================================================

""".format(
            date=datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
            vm_count=len(vms),
            instance_count=sum(len(vm['instance_ids']) for vm in vms)
        ))

        # Step 1: Create temp tables
        f.write("""-- =====================================================
-- Step 1: Create Temporary Staging Tables
-- =====================================================

CREATE TABLE temp_vm_master (
    legacy_id INT NOT NULL PRIMARY KEY,
    vm_name VARCHAR(255) NOT NULL,
    instance_id_array TEXT NOT NULL,
    create_date TIMESTAMP NOT NULL,
    is_active CHAR(3) NOT NULL
);

CREATE TABLE temp_instance_parsed (
    legacy_id INT NOT NULL,
    vm_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    instance_sequence INT NOT NULL,
    create_date TIMESTAMP NOT NULL
);

CREATE INDEX idx_temp_vm_name ON temp_vm_master(vm_name);
CREATE INDEX idx_temp_vm_active ON temp_vm_master(is_active);
CREATE INDEX idx_temp_inst_vm ON temp_instance_parsed(legacy_id);

""")

        # Step 2: Insert VM master data
        f.write("""-- =====================================================
-- Step 2: Insert Active VM Master Data
-- Total: {count} records
-- =====================================================

INSERT INTO temp_vm_master (legacy_id, vm_name, instance_id_array, create_date, is_active) VALUES
""".format(count=len(vms)))

        vm_values = []
        for vm in vms:
            instance_array_json = json.dumps(vm['instance_ids']).replace('"', "''")
            vm_values.append(
                "({id}, '{name}', '{array}', '{date}', '{active}')".format(
                    id=vm['legacy_id'],
                    name=escape_sql_string(vm['vm_name']),
                    array=instance_array_json,
                    date=vm['create_date'],
                    active=vm['is_active']
                )
            )

        f.write(",\n".join(vm_values))
        f.write(";\n\n")

        # Step 3: Parse instances
        f.write("""-- =====================================================
-- Step 3: Parse Instance IDs from Arrays
-- =====================================================

INSERT INTO temp_instance_parsed (legacy_id, vm_name, instance_id, instance_sequence, create_date) VALUES
""")

        instance_values = []
        for vm in vms:
            for idx, instance_id in enumerate(vm['instance_ids'], start=1):
                instance_values.append(
                    "({id}, '{name}', '{instance}', {seq}, '{date}')".format(
                        id=vm['legacy_id'],
                        name=escape_sql_string(vm['vm_name']),
                        instance=instance_id,
                        seq=idx,
                        date=vm['create_date']
                    )
                )

        f.write(",\n".join(instance_values))
        f.write(";\n\n")

        # Step 4: Create environments
        f.write("""-- =====================================================
-- Step 4: Create Environments (One per VM Name)
-- =====================================================

INSERT INTO environment (
    environment_id,
    name,
    display_name,
    description,
    is_active,
    created_at,
    updated_at,
    metadata
)
SELECT
    RANDOM_UUID() AS environment_id,
    LOWER(REGEXP_REPLACE(REGEXP_REPLACE(vm_name, '[^a-zA-Z0-9-]', '-'), '-+', '-')) AS name,
    vm_name AS display_name,
    vm_name AS description,
    TRUE AS is_active,
    create_date AS created_at,
    CURRENT_TIMESTAMP AS updated_at,
    CONCAT('{',
        '"ownerTeam":"CloudOps",',
        '"defaultCloudProvider":"AWS",',
        '"legacyVmId":', legacy_id, ',',
        '"legacyVmName":"', REPLACE(vm_name, '"', '\\"'), '",',
        '"migratedAt":"', CURRENT_TIMESTAMP, '"',
    '}') AS metadata
FROM temp_vm_master
WHERE is_active = 'Y';

""")

        # Step 5: Create groups
        f.write("""-- =====================================================
-- Step 5: Create Default Groups (One per Environment)
-- =====================================================

INSERT INTO vm_group (
    group_id,
    environment_id,
    name,
    display_name,
    description,
    sequence_position,
    depends_on_group_ids,
    created_at,
    updated_at,
    metadata
)
SELECT
    RANDOM_UUID() AS group_id,
    e.environment_id,
    'default-group' AS name,
    'Default Group' AS display_name,
    CONCAT('Default group for ', e.display_name) AS description,
    1 AS sequence_position,
    NULL AS depends_on_group_ids,
    e.created_at,
    CURRENT_TIMESTAMP AS updated_at,
    '{"isDefault":true,"legacyMigration":true}' AS metadata
FROM environment e
WHERE e.metadata LIKE '%legacyVmId%';

""")

        # Step 6: Create VMs
        f.write("""-- =====================================================
-- Step 6: Create VMs (One per Instance)
-- =====================================================

INSERT INTO vm (
    vm_id,
    group_id,
    name,
    display_name,
    description,
    provider,
    region,
    provider_vm_id,
    vm_type,
    sequence_position,
    depends_on_vm_ids,
    status,
    last_known_state,
    last_state_sync_at,
    state_drift_detected,
    created_at,
    updated_at,
    metadata
)
SELECT
    RANDOM_UUID() AS vm_id,
    g.group_id,
    LOWER(ip.instance_id) AS name,
    ip.instance_id AS display_name,
    CONCAT('VM from ', ip.vm_name, ' - Instance ', ip.instance_sequence) AS description,
    'AWS' AS provider,
    'unknown' AS region,
    ip.instance_id AS provider_vm_id,
    'dev' AS vm_type,
    ip.instance_sequence AS sequence_position,
    NULL AS depends_on_vm_ids,
    'unknown' AS status,
    NULL AS last_known_state,
    NULL AS last_state_sync_at,
    FALSE AS state_drift_detected,
    ip.create_date AS created_at,
    CURRENT_TIMESTAMP AS updated_at,
    CONCAT('{',
        '"legacyVmId":', ip.legacy_id, ',',
        '"legacyVmName":"', REPLACE(ip.vm_name, '"', '\\"'), '",',
        '"instanceSequence":', ip.instance_sequence, ',',
        '"migratedAt":"', CURRENT_TIMESTAMP, '"',
    '}') AS metadata
FROM temp_instance_parsed ip
INNER JOIN environment e ON LOWER(REGEXP_REPLACE(REGEXP_REPLACE(ip.vm_name, '[^a-zA-Z0-9-]', '-'), '-+', '-')) = e.name
INNER JOIN vm_group g ON g.environment_id = e.environment_id
WHERE e.metadata LIKE '%legacyVmId%'
ORDER BY ip.legacy_id, ip.instance_sequence;

""")

        # Step 7: Create VM provider details
        f.write("""-- =====================================================
-- Step 7: Create VM Provider Details
-- =====================================================

INSERT INTO vm_provider_details (
    vm_provider_detail_id,
    vm_id,
    provider,
    region_code,
    region_name,
    availability_zone,
    instance_type,
    cpu_cores,
    memory_gb,
    network_interfaces,
    storage_volumes,
    tags,
    labels,
    provider_console_url,
    private_ip,
    public_ip,
    vpc_id,
    subnet_id,
    security_groups,
    iam_profile,
    created_at,
    updated_at
)
SELECT
    RANDOM_UUID() AS vm_provider_detail_id,
    v.vm_id,
    'AWS' AS provider,
    'unknown' AS region_code,
    NULL AS region_name,
    NULL AS availability_zone,
    NULL AS instance_type,
    NULL AS cpu_cores,
    NULL AS memory_gb,
    NULL AS network_interfaces,
    NULL AS storage_volumes,
    NULL AS tags,
    NULL AS labels,
    CONCAT('https://console.aws.amazon.com/ec2/v2/home#Instances:instanceId=', v.provider_vm_id) AS provider_console_url,
    NULL AS private_ip,
    NULL AS public_ip,
    NULL AS vpc_id,
    NULL AS subnet_id,
    NULL AS security_groups,
    NULL AS iam_profile,
    v.created_at,
    CURRENT_TIMESTAMP AS updated_at
FROM vm v
WHERE v.metadata LIKE '%legacyVmId%';

""")

        # Step 8: Cleanup
        f.write("""-- =====================================================
-- Step 8: Cleanup Temporary Tables
-- =====================================================

DROP TABLE IF EXISTS temp_instance_parsed;
DROP TABLE IF EXISTS temp_vm_master;

-- =====================================================
-- End of V4 Migration
-- Migration completed successfully!
-- =====================================================
""")

def main():
    print("=" * 60)
    print("V4 Migration Generator")
    print("=" * 60)
    print()

    # Parse VM data
    print("Step 1: Parsing vm-master.sql...")
    vms = parse_vm_master('data/vm-master.sql')
    print(f"  ✓ Found {len(vms)} active VMs")

    total_instances = sum(len(vm['instance_ids']) for vm in vms)
    print(f"  ✓ Total instances to migrate: {total_instances}")
    print()

    # Generate migration
    print("Step 2: Generating V4__migrate_legacy_vms.sql...")
    output_file = 'src/main/resources/db/migration/V4__migrate_legacy_vms.sql'
    generate_v4_migration(vms, output_file)
    print(f"  ✓ Migration file created: {output_file}")
    print()

    # Summary
    print("=" * 60)
    print("MIGRATION GENERATION COMPLETE!")
    print("=" * 60)
    print()
    print(f"Environments to create: {len(vms)}")
    print(f"Groups to create: {len(vms)}")
    print(f"VMs to create: {total_instances}")
    print()
    print("Next steps:")
    print("1. Review the generated migration file")
    print("2. Test with: gradlew clean build")
    print("3. Run with: gradlew bootRun")
    print()
    print("The migration will execute automatically on startup!")

if __name__ == '__main__':
    main()

