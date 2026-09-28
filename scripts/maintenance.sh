#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
dc exec -T api python -m wenfou.manage cleanup
bash "$ROOT_DIR/scripts/backup.sh"
