#!/usr/bin/env bash
# Telegram notifications for CI; the implementation lives in telegram.py.
#   notify_telegram.sh review <changed_files> <added_lines> <removed_lines> [diff_report]
#   notify_telegram.sh release <success|failure|cancelled>
exec python3 "$(dirname "$0")/telegram.py" "$@"
