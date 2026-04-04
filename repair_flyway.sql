-- Delete the failed V3 migration record from flyway_schema_history
DELETE FROM flyway_schema_history WHERE version = '3' AND success = false;
