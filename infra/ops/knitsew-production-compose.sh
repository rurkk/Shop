#!/bin/sh
set -eu

cd /opt/knitsew-production
project=$(cat project-name)
access=$(cat access-mode)

case "$project" in
    knitsew-demo|knitsew-production-recovery-*) ;;
    *) echo 'Invalid production Compose project name' >&2; exit 1 ;;
esac

case "$access" in
    pilot) set -- -f current/compose.prod.pilot.yaml "$@" ;;
    public) ;;
    *) echo 'Expected pilot or public in access-mode' >&2; exit 1 ;;
esac

exec docker compose --project-name "$project" --env-file .env.production \
    -f current/compose.yaml -f current/compose.prod.yaml "$@"
