#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
umask 077
BACKUP_DIR="${WENFOU_BACKUP_DIR:-$ROOT_DIR/backups}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/wenfou-$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
TEMP_FILE="$BACKUP_FILE.partial"
trap 'rm -f -- "$TEMP_FILE"' EXIT
dc exec -T db sh -c 'pg_dump -U wenfou_owner -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' > "$TEMP_FILE"
test -s "$TEMP_FILE"
dc exec -T db pg_restore --list < "$TEMP_FILE" >/dev/null
mv "$TEMP_FILE" "$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"
printf 'commit=%s\nimage=%s\nmode=%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)" "$WENFOU_IMAGE_TAG" "$MODE" > "$BACKUP_FILE.meta"
# Explicit retention policy: local dumps expire after 14 days. Keep off-host copies too.
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'wenfou-*.dump*' -mtime +13 -delete
printf '%s\n' "$BACKUP_FILE"
