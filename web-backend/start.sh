#!/usr/bin/env bash
set -o errexit

echo ">>> Starting application..."

# Start Supervisor (manages both Nginx and PHP-FPM)
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf
