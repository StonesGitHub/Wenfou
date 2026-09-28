#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${WENFOU_ENV_FILE:-$ROOT_DIR/deploy/.env}"
python3 "$ROOT_DIR/scripts/configure.py" --file "$ENV_FILE" --check >/dev/null
MODE="$(python3 "$ROOT_DIR/scripts/configure.py" --file "$ENV_FILE" --get WENFOU_MODE)"
LOCAL_PORT="$(python3 "$ROOT_DIR/scripts/configure.py" --file "$ENV_FILE" --get WENFOU_LOCAL_PORT)"
COMPOSE_ARGS=(--project-name wenfou --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yml")
if [[ "$MODE" == production ]]; then
  COMPOSE_ARGS+=(-f "$ROOT_DIR/deploy/compose.production.yml")
fi
# Tag every image by checked-out commit. Same tag survives checkout rollback.
export WENFOU_IMAGE_TAG="${WENFOU_IMAGE_TAG:-$(git -C "$ROOT_DIR" rev-parse --short=12 HEAD)}"
if [[ ! "$WENFOU_IMAGE_TAG" =~ ^[a-zA-Z0-9_.-]+$ ]]; then
  echo 'Invalid image tag' >&2; exit 1
fi
dc() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }
