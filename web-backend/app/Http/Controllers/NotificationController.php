<?php

namespace App\Http\Controllers;

use App\Models\Notification;
use App\Models\Pwd;
use App\Models\Guardian;
use Illuminate\Http\Request;
use Carbon\Carbon;

class NotificationController extends Controller
{
    public function index(Request $request)
    {
        $user = $request->user();
        if ($user->role !== 'guardian') {
            return response()->json(['message' => 'Unauthorized'], 403);
        }

        $guardian = Guardian::where('user_id', $user->id)->first();
        if (!$guardian) {
            return response()->json(['message' => 'Guardian profile not found'], 404);
        }

        $notifications = Notification::where('guardian_id', $guardian->id)
            ->with(['pwd.user'])
            ->orderBy('created_at', 'desc')
            ->get();

        return response()->json(['notifications' => $notifications], 200);
    }

    public function store(Request $request)
    {
        $request->validate([
            'type' => 'required|string|in:distress,fall_detected,location_update',
            'is_emergency' => 'boolean',
            'latitude' => 'nullable|numeric',
            'longitude' => 'nullable|numeric',
        ]);

        $user = $request->user();
        if ($user->role !== 'pwd') {
            return response()->json(['message' => 'Only PWDs can send distress signals'], 403);
        }

        $pwd = Pwd::where('user_id', $user->id)->first();
        if (!$pwd || !$pwd->guardian_id) {
            return response()->json(['message' => 'PWD profile or Guardian not found'], 404);
        }

        $guardian = Guardian::where('user_id', $pwd->guardian_id)->first();
        if (!$guardian) {
            return response()->json(['message' => 'Guardian not found'], 404);
        }

        $notification = Notification::create([
            'pwd_id' => $pwd->id,
            'guardian_id' => $guardian->id,
            'type' => $request->type,
            'status' => 'unread',
            'is_emergency' => $request->is_emergency ?? true,
            'latitude' => $request->latitude,
            'longitude' => $request->longitude,
            'triggered_at' => Carbon::now(),
        ]);

        return response()->json(['message' => 'Distress signal sent successfully', 'notification' => $notification], 201);
    }

    public function destroy(Request $request, $id)
    {
        $user = $request->user();
        if ($user->role !== 'guardian') {
            return response()->json(['message' => 'Unauthorized'], 403);
        }

        $guardian = Guardian::where('user_id', $user->id)->first();
        $notification = Notification::where('id', $id)
            ->where('guardian_id', $guardian->id)
            ->firstOrFail();

        $notification->delete();

        return response()->json(['message' => 'Notification deleted successfully'], 200);
    }
}
