#!/usr/bin/env bash
# Consistent logical backup of the compose database: scripts/backup.sh [dir]
# Restore: docker compose exec -T db pg_restore -U conference -d conference --clean --if-exists < FILE
set -euo pipefail
dir="${1:-backups}"
mkdir -p "$dir"
file="$dir/conference-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T db pg_dump -U conference -d conference --format=custom > "$file"
echo "wrote $file"
