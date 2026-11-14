#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'This command does not accept arguments.' >&2
    exit 2
fi

docker_local() {
    /usr/bin/env -i PATH=/usr/bin:/bin LC_ALL=C /usr/bin/docker \
        --host unix:///var/run/docker.sock "$@"
}

project=embroidery-shop-test
containers=$(docker_local ps --all --no-trunc \
    --filter "label=com.docker.compose.project=$project" \
    --format '{{.ID}} {{.Names}} {{.Status}}')

if [ -z "$containers" ]; then
    printf 'No containers found for Compose project %s.\n' "$project" >&2
    exit 1
fi

printf '=== %s: container status ===\n%s\n' "$project" "$containers"
result=0
while IFS=' ' read -r id name _; do
    printf '\n=== %s: last 100 log lines ===\n' "$name"
    docker_local logs --timestamps --tail 100 -- "$id" 2>&1 || result=1
done <<EOF
$containers
EOF
exit "$result"
