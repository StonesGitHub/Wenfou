#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
command -v docker >/dev/null
command -v curl >/dev/null
docker compose version
docker info --format '{{.OSType}} / {{.Architecture}}'
dc config --quiet
[[ "$(uname -m)" == x86_64 ]] || { echo 'This release targets x86_64.' >&2; exit 1; }
[[ "$(df -Pk "$ROOT_DIR" | awk 'NR==2 {print $4}')" -ge 5242880 ]] || { echo 'Need at least 5 GiB free disk.' >&2; exit 1; }
echo "Preflight passed; mode=$MODE; API binds only to 127.0.0.1:$LOCAL_PORT"
echo 'Production: verify DNS points to this server and inbound TCP 80/443 is allowed before deploy.'
