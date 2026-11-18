#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

for tool in docker openssl curl jq; do
    command -v "$tool" >/dev/null || { printf 'Missing command: %s\n' "$tool" >&2; exit 1; }
done

if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
elif command -v docker-compose >/dev/null; then
    compose=(docker-compose)
else
    printf '%s\n' 'Install Docker Compose before running Gatekeeper.' >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1 && [[ "$(docker context show)" == colima ]] &&
    command -v colima >/dev/null && ! colima status >/dev/null 2>&1; then
    colima start
fi
docker info >/dev/null

if [[ ! -e .env ]]; then
    (
        umask 077
        set -o noclobber
        for key in GATEKEEPER_DB_ROOT_PASSWORD GATEKEEPER_DB_PASSWORD GATEKEEPER_JWT_SECRET \
            GATEKEEPER_ADMIN_PASSWORD GATEKEEPER_GATEWAY_PASSWORD; do
            printf '%s=%s\n' "$key" "$(openssl rand -hex 32)"
        done > .env
    )
fi

"${compose[@]}" up --build -d
if ! bash scripts/smoke-test.sh; then
    "${compose[@]}" ps
    printf '%s\n' 'Check the API Management and Smart Gateway container logs for the startup error.' >&2
    exit 1
fi

printf '%s\n' 'Console: http://127.0.0.1:8081/' 'Sign in as platform-admin with GATEKEEPER_ADMIN_PASSWORD from .env.'
