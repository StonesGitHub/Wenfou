#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
BACKUP_FILE="${1:?Usage: bash scripts/restore.sh BACKUP.dump wenfou_restore_YYYYMMDD_HHMMSS}"
RESTORE_DB="${2:?A NEW database name is required}"
[[ "$RESTORE_DB" =~ ^wenfou_restore_[0-9]{8}_[0-9]{6}$ ]] || { echo 'Invalid recovery database name' >&2; exit 1; }
test -s "$BACKUP_FILE"
# Compare digest independent of the original filesystem location.
python3 - "$BACKUP_FILE" <<'PY'
from pathlib import Path
import hashlib,sys
p=Path(sys.argv[1])
expected=Path(str(p)+'.sha256').read_text().split()[0]
if hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
    raise SystemExit('Backup digest mismatch')
PY
dc exec -T db createdb -U wenfou_owner "$RESTORE_DB"
dc exec -T db pg_restore -U wenfou_owner --dbname="$RESTORE_DB" --exit-on-error --single-transaction --no-owner --no-privileges < "$BACKUP_FILE"
dc exec -T db psql -U wenfou_owner -d "$RESTORE_DB" -v ON_ERROR_STOP=1 <<'SQL'
GRANT USAGE ON SCHEMA public TO wenfou_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wenfou_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wenfou_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wenfou_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO wenfou_app;
SQL
echo "Restored to NEW database $RESTORE_DB; live database was not overwritten."
echo 'Validate counts/schema first, then stop API and edit POSTGRES_DB in deploy/.env to switch.'
