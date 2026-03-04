<?php

namespace App\Console\Commands;

use App\Models\Location;
use Illuminate\Console\Command;

class CleanOldLocations extends Command
{
    protected $signature = 'locations:clean';
    protected $description = 'Delete location history older than 7 days';

    public function handle()
    {
        $deleted = Location::where('recorded_at', '<', now()->subDays(7))->delete();
        $this->info("Deleted {$deleted} old location records.");
    }
}
