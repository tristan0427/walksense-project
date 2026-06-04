<?php

namespace App\Http\Controllers;

use App\Models\CurrentLocation;
use App\Models\Location;
use App\Models\Pwd;
use Illuminate\Support\Facades\Log;
use Illuminate\Http\Request;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Auth;

class LocationController extends Controller
{
    /**
     * PWD sends location update
     */

    public function store(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'latitude' => ['required', 'numeric', 'between:-90,90'],
            'longitude' => ['required', 'numeric', 'between:-180,180'],
            'accuracy' => ['nullable', 'numeric', 'min:0'],
            'altitude' => ['nullable', 'numeric'],
            'speed' => ['nullable', 'numeric', 'min:0'],
            'heading' => ['nullable', 'numeric', 'between:0,360'],
            'battery_level' => ['nullable', 'integer', 'between:0,100'],
            'is_stationary' => ['nullable', 'boolean'],
            'recorded_at' => ['nullable', 'date'],
        ]);

        $user = Auth::user();

        try {
            DB::beginTransaction();

            $isStationary = $request->input('is_stationary', false);
            $location = null;
            
            $recordedAt = $request->input('recorded_at') ? \Carbon\Carbon::parse($request->input('recorded_at'))->setTimezone('UTC') : now();

            $location = Location::create([
                'user_id' => $user->id,
                'latitude' => $validated['latitude'],
                'longitude' => $validated['longitude'],
                'accuracy' => $validated['accuracy'] ?? null,
                'altitude' => $validated['altitude'] ?? null,
                'speed' => $validated['speed'] ?? null,
                'heading' => $validated['heading'] ?? null,
                'battery_level' => $validated['battery_level'] ?? null,
                'recorded_at' => $recordedAt,
            ]);

            CurrentLocation::updateOrCreate(
                ['user_id' => $user->id],
                [
                    'latitude' => $validated['latitude'],
                    'longitude' => $validated['longitude'],
                    'accuracy' => $validated['accuracy'] ?? null,
                    'battery_level' => $validated['battery_level'] ?? null,
                    'last_updated' => now(),
                    'is_stationary' => $isStationary,
                ]
            );

            DB::commit();

            return response()->json([
                'message' => 'Location updated successfully.',
                'location' => $location,
            ], 201);

        }catch (\Exception $exception){
            DB::rollBack();
            \Log::error('Location update failed: '. $exception->getMessage());

            return response()->json([
                'message' => 'Failed to update location',
                'error' => $exception->getMessage(),
            ],500);
        }
    }

    /**
     * Guardian gets PWD's current location
     */

    public function getCurrentLocations(Request $request, $pwdUserId): JsonResponse
    {
        $guardian = Auth::user();

        $pwd = Pwd::where('user_id', $pwdUserId)
            ->where('guardian_id', $guardian->id)
            ->first();

        if(!$pwd){
            return response()->json([
                'message' => 'Unauthorized access to this PWD location',
            ],403);
        }

        $currentLocation = CurrentLocation::where ('user_id', $pwdUserId)->first();

        if (!$currentLocation) {
            return response()->json([
                'message' => 'No location data available',
            ],404);
        }

        return response()->json([
            'location' => $currentLocation,
            'pwd' => [
                'id' => $pwd->user_id,
                'name' => $pwd->firstname . ' ' . $pwd->lastname,
            ],
        ], 200);
    }

    /**
     * Guardian gets PWD's location history
     */

    public function getLocationHistory(Request $request, $pwdUserId): JsonResponse
    {
        $guardian = Auth::user();

        $pwd = Pwd::where('user_id', $pwdUserId)
            ->where('guardian_id', $guardian->id)
            ->first();

        if (!$pwd) {
            return response()->json([
                'message' => 'Unauthorized access',
            ], 403);
        }

        $query = Location::where('user_id', $pwd->user_id);

        if ($request->has('date')) {
            $dateStr = $request->query('date');
            $timezone = $request->query('timezone', 'UTC');

            try {
                // Calculate local day start/end boundaries, and convert them back to UTC
                $startUtc = \Carbon\Carbon::parse($dateStr, $timezone)->startOfDay()->setTimezone('UTC');
                $endUtc = \Carbon\Carbon::parse($dateStr, $timezone)->endOfDay()->setTimezone('UTC');
                $query->whereBetween('recorded_at', [$startUtc, $endUtc]);
            } catch (\Exception $e) {
                // Fallback if parsing fails
                $query->whereDate('recorded_at', $dateStr);
            }
        } else {
            $hours = $request->query('hours', 24);
            $query->where('recorded_at', '>=', now()->subHours($hours));
        }

        // Return in chronological ascending order for route drawing and playback
        $locations = $query->orderBy('recorded_at', 'asc')->get();

        return response()->json([
            'locations' => $locations,
            'count' => $locations->count(),
        ],200);
    }

    /**
     * Guardian gets all their PWDs' current locations
     */

    public function getAllPwdLocations(Request $request): JsonResponse
    {
        $guardian = Auth::user();

        $pwds = Pwd::where('guardian_id', $guardian->id)->get();

        $pwdLocations = [];

        foreach ($pwds as $pwd) {
            $currentLocation = CurrentLocation::where('user_id', $pwd->user_id)->first();

            $secondsAgo = null;
            if ($currentLocation && $currentLocation->last_updated) {
                $secondsAgo = now()->diffInSeconds($currentLocation->last_updated);
            }

            $pwdLocations[] = [
                'pwd_id' => $pwd->user_id,
                'pwd_name' => $pwd->firstname . ' ' . $pwd->lastname,
                'location' => $currentLocation,
                'seconds_ago' => $secondsAgo,
            ];
        }
        return response()->json([
            'pwd_locations' => $pwdLocations,
        ],200);
    }


}
