#!/usr/bin/env bash
# exit on error
set -o errexit

composer install --no-dev --optimize-autoloader

# Install and build assets using Vite
npm install
npm run build

php artisan optimize:clear
php artisan optimize
# Run migrations (force because it's production)
php artisan migrate --force
