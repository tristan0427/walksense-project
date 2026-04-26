<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use App\Mail\OtpMail;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Mail;
use Illuminate\Validation\ValidationException;
use Random\RandomException;

class PasswordResetController extends Controller
{
    /**
     * Send OTP to user's email for password reset
     * @throws RandomException
     */
    public function sendOtp(Request $request): JsonResponse
    {
        $request->validate([
            'email' => ['required', 'email', 'exists:users,email'],
        ]);

        try {
            $user = User::where('email', $request->email)->firstOrFail();

            // Generate OTP
            $otp = str_pad(random_int(0, 999999), 6, '0', STR_PAD_LEFT);
            $otpExpiresAt = now()->addMinutes(5);

            $cacheKey = 'password_reset_' . md5($request->email);

            // Store reset data in cache
            Cache::put($cacheKey, [
                'email' => $request->email,
                'otp' => $otp,
                'otp_expires_at' => $otpExpiresAt->toDateTimeString(),
            ], now()->addMinutes(10));

            \Log::info('Password reset OTP generated', [
                'cache_key' => $cacheKey,
                'email' => $request->email,
                // 'otp' => $otp, // DO NOT log OTP in production
            ]);

            // Send OTP email
            Mail::to($user->email)->send(new OtpMail($otp, $user->name));

            return response()->json([
                'message' => 'Reset code sent. Please check your email.',
                'email' => $user->email,
            ], 200);

        } catch (\Throwable $e) {
            \Log::error('Password reset OTP initiation failed: ' . $e->getMessage());

            return response()->json([
                'message' => 'Failed to send reset code.',
                'error' => config('app.debug') ? $e->getMessage() : 'An error occurred',
            ], 500);
        }
    }

    /**
     * Verify the OTP sent to user's email
     */
    public function verifyOtp(Request $request): JsonResponse
    {
        $request->validate([
            'email' => ['required', 'email'],
            'otp' => ['required', 'string', 'size:6'],
        ]);

        $cacheKey = 'password_reset_' . md5($request->email);
        $pendingData = Cache::get($cacheKey);

        if (!$pendingData) {
            return response()->json([
                'message' => 'No active password reset request found or it has expired.',
            ], 400);
        }

        if ($pendingData['email'] !== $request->email) {
            return response()->json([
                'message' => 'Email does not match the reset request.',
            ], 400);
        }

        if ($pendingData['otp'] !== $request->otp) {
            return response()->json([
                'message' => 'Invalid verification code.',
            ], 400);
        }

        if (now()->gt($pendingData['otp_expires_at'])) {
            return response()->json([
                'message' => 'Verification code has expired. Please request a new one.',
            ], 400);
        }

        // OTP is valid, mark it as verified so they can proceed to reset password step
        // We extend the cache slightly to allow time to type the new password
        $pendingData['is_verified'] = true;
        Cache::put($cacheKey, $pendingData, now()->addMinutes(15));

        return response()->json([
            'message' => 'Code verified successfully. Please enter your new password.',
        ], 200);
    }

    /**
     * Actually reset the password
     */
    public function resetPassword(Request $request): JsonResponse
    {
        $request->validate([
            'email' => ['required', 'email'],
            'password' => ['required', 'string', 'min:8', 'confirmed'],
        ]);

        $cacheKey = 'password_reset_' . md5($request->email);
        $pendingData = Cache::get($cacheKey);

        if (!$pendingData || !isset($pendingData['is_verified']) || !$pendingData['is_verified']) {
            return response()->json([
                'message' => 'Invalid or expired password reset session.',
            ], 400);
        }

        try {
            $user = User::where('email', $request->email)->firstOrFail();
            $user->password = Hash::make($request->password);
            $user->save();

            // Clear the cache to prevent reuse
            Cache::forget($cacheKey);

            return response()->json([
                'message' => 'Password has been successfully reset.',
            ], 200);

        } catch (\Throwable $e) {
            \Log::error('Password reset failed: ' . $e->getMessage());

            return response()->json([
                'message' => 'Failed to reset password. Please try again.',
                'error' => config('app.debug') ? $e->getMessage() : 'An error occurred',
            ], 500);
        }
    }
}
