#!/usr/bin/env bash
# Compares two MySQL schemas structurally: tables and their options, columns (type, nullability,
# default, extra, generated expression, charset), indexes, foreign keys and their rules.
# Prints the differences and exits 1 if there are any.
#
# Usage: scripts/compare-schemas.sh <mysql-defaults-file> <schemaA> <schemaB> [ignored_table ...]
#   The defaults file holds [client] user/password/host/port, so no secret is on the command line.
# Used to prove the V1 baseline equals the schema the old V1-V104 migrations built
# (docs/runbook-new-install.md); also handy to compare staging with a fresh install.
set -euo pipefail
CNF="$1"; A="$2"; B="$3"; shift 3
IGNORE="'flyway_schema_history'"
for t in "$@"; do IGNORE="$IGNORE,'$t'"; done
MYSQL="${MYSQL:-mysql}"

snapshot() {
  local s="$1"
  "$MYSQL" --defaults-extra-file="$CNF" -N -B information_schema -e "
    SELECT 'table', table_name, engine, table_collation, IFNULL(create_options,'')
      FROM tables WHERE table_schema='$s' AND table_type='BASE TABLE' AND table_name NOT IN ($IGNORE)
    UNION ALL
    SELECT 'column', CONCAT(table_name,'.',column_name), column_type,
           CONCAT(is_nullable,' default=',IFNULL(column_default,'<null>'),' extra=',extra),
           CONCAT(IFNULL(generation_expression,''),' cs=',IFNULL(character_set_name,''),' coll=',IFNULL(collation_name,''))
      FROM columns WHERE table_schema='$s' AND table_name NOT IN ($IGNORE)
    UNION ALL
    SELECT 'index', CONCAT(table_name,'.',index_name),
           CONCAT(IF(non_unique=0,'UNIQUE ',''), index_type),
           GROUP_CONCAT(CONCAT(column_name, IFNULL(CONCAT('(',sub_part,')'),''), IFNULL(CONCAT(' expr:',expression),'')) ORDER BY seq_in_index),
           is_visible
      FROM statistics WHERE table_schema='$s' AND table_name NOT IN ($IGNORE)
      GROUP BY table_name, index_name, non_unique, index_type, is_visible
    UNION ALL
    SELECT 'fk', CONCAT(k.table_name,'.',k.constraint_name),
           GROUP_CONCAT(CONCAT(k.column_name,'->',k.referenced_table_name,'.',k.referenced_column_name) ORDER BY k.ordinal_position),
           r.update_rule, r.delete_rule
      FROM key_column_usage k JOIN referential_constraints r
        ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name
      WHERE k.table_schema='$s' AND k.referenced_table_name IS NOT NULL AND k.table_name NOT IN ($IGNORE)
      GROUP BY k.table_name, k.constraint_name, r.update_rule, r.delete_rule
    UNION ALL
    SELECT 'check', CONCAT(t.table_name,'.',c.constraint_name), c.check_clause, '', ''
      FROM check_constraints c JOIN table_constraints t
        ON t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name
      WHERE c.constraint_schema='$s' AND t.table_name NOT IN ($IGNORE)
  " | tr -d '\r' | LC_ALL=C sort
}

TMP=$(mktemp -d)
snapshot "$A" > "$TMP/a"
snapshot "$B" > "$TMP/b"
echo "$A: $(grep -c '^table' "$TMP/a") tables, $(grep -c '^column' "$TMP/a") columns, $(grep -c '^index' "$TMP/a") indexes, $(grep -c '^fk' "$TMP/a") foreign keys, $(grep -c '^check' "$TMP/a") checks"
echo "$B: $(grep -c '^table' "$TMP/b") tables, $(grep -c '^column' "$TMP/b") columns, $(grep -c '^index' "$TMP/b") indexes, $(grep -c '^fk' "$TMP/b") foreign keys, $(grep -c '^check' "$TMP/b") checks"
if diff -u "$TMP/a" "$TMP/b" > "$TMP/diff"; then
  echo "IDENTICAL"; rm -r "$TMP"; exit 0
else
  echo "DIFFERENT:"; grep -E '^[-+][^-+]' "$TMP/diff"; rm -r "$TMP"; exit 1
fi
