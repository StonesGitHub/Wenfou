#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
bash "$ROOT_DIR/scripts/preflight.sh"
mkdir -p "$ROOT_DIR/deploy/state"
chmod 700 "$ROOT_DIR/deploy/state"
# Build before stopping the old service; a failed download/build leaves it serving.
dc build api
if [[ "$MODE" == production ]]; then dc pull caddy; fi
dc up -d --wait --wait-timeout 120 db
# Always take a verified backup, including on first deployment. No database reset.
bash "$ROOT_DIR/scripts/backup.sh"
PREVIOUS_IMAGE="$(dc images -q api 2>/dev/null || true)"
if [[ -n "$PREVIOUS_IMAGE" ]]; then
  printf '%s\n' "$PREVIOUS_IMAGE" > "$ROOT_DIR/deploy/state/previous-image-id"
fi
dc stop api
if ! dc run --rm migrate; then
  echo 'Migration failed; API stays stopped. Consult backup metadata and HANDOFF.md; do not drop volumes.' >&2
  exit 1
fi
dc up -d --no-build --wait --wait-timeout 120 api
if [[ "$MODE" == production ]]; then dc up -d --wait --wait-timeout 120 caddy; fi
curl --fail --silent --show-error "http://127.0.0.1:$LOCAL_PORT/health/ready"
printf '\n'
printf '%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)" > "$ROOT_DIR/deploy/state/current-commit"
echo 'Deploy finished. Run the acceptance smoke test before handing over.'
