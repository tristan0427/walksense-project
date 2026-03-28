#!/usr/bin/env bash
set -o errexit

echo ">>> Running build script..."

# Clear and optimize Laravel
php artisan optimize:clear
php artisan optimize

# Storage link (ignore error if already exists)
php artisan storage:link || true

# Set permissions
chmod -R 775 storage bootstrap/cache public/build

# Run database migrations
php artisan migrate --force

echo ">>> Build complete!"
