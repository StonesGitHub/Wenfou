#!/usr/bin/env bash
# Run only on a disposable CI runner, never an existing deployment.
set -Eeuo pipefail
[[ "${CI:-}" == true ]] || { echo 'CI-only acceptance script' >&2; exit 1; }
python3 scripts/configure.py --mode local
bash scripts/deploy.sh
SMOKE_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
printf '%s\n%s\n' "$SMOKE_PASSWORD" "$SMOKE_PASSWORD" | bash scripts/compose.sh exec -T api python -m wenfou.manage create-admin --username administrator
SMOKE_INVITE="$(bash scripts/compose.sh exec -T api python -m wenfou.manage invite --uses 2 --days 1)"
export WENFOU_SMOKE_ADMIN_PASSWORD="$SMOKE_PASSWORD" WENFOU_SMOKE_INVITE="$SMOKE_INVITE"
python3 scripts/smoke.py --base-url http://127.0.0.1:8000
unset WENFOU_SMOKE_ADMIN_PASSWORD WENFOU_SMOKE_INVITE SMOKE_PASSWORD SMOKE_INVITE
bash scripts/compose.sh restart api
bash scripts/compose.sh up -d --wait --wait-timeout 120 api
curl --fail http://127.0.0.1:8000/health/ready
DUMP="$(bash scripts/backup.sh)"
bash scripts/restore.sh "$DUMP" wenfou_restore_20260101_000000
COUNT="$(bash scripts/compose.sh exec -T db psql -U wenfou_owner -d wenfou_restore_20260101_000000 -Atc 'SELECT count(*) FROM users')"
[[ "$COUNT" == 3 ]]
REVISION="$(bash scripts/compose.sh exec -T db psql -U wenfou_owner -d wenfou_restore_20260101_000000 -Atc 'SELECT version_num FROM alembic_version')"
[[ "$REVISION" == 0001 ]]
# Validate production Compose and Caddy syntax without requesting any TLS certificate.
python3 scripts/configure.py --file /tmp/wenfou-ci-production.env --mode production --domain api.wenfou-ci.test --email ci@wenfou-ci.test
WENFOU_ENV_FILE=/tmp/wenfou-ci-production.env bash scripts/compose.sh config --quiet
WENFOU_ENV_FILE=/tmp/wenfou-ci-production.env bash scripts/compose.sh run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
# Test idempotent re-deploy preserving the same users and database volume.
bash scripts/deploy.sh
[[ "$(bash scripts/compose.sh exec -T db psql -U wenfou_owner -d wenfou -Atc 'SELECT count(*) FROM users')" == 3 ]]
echo 'PASS: Compose deployment, DB roles, restart persistence, backup/restore, Caddy config and redeploy'
