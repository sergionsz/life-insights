#!/bin/sh
# /usr/local/bin/life-insights-backup
#
# Run by life-insights-backup.timer, as the postgres user.

set -eu

DIR=/var/backups/life-insights
KEEP_DAYS=14

mkdir -p "$DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$DIR/insights-$stamp.sql.gz"

# Write to a temporary name and move it into place, so an interrupted dump never leaves a
# truncated file sitting there looking like a usable backup.
if pg_dump -d insights | gzip > "$target.partial"; then
    mv "$target.partial" "$target"
    echo "wrote $target"
else
    rm -f "$target.partial"
    echo "backup FAILED" >&2
    exit 1
fi

find "$DIR" -name 'insights-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
