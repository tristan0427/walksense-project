<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use App\Mail\OtpMail;
use App\Models\User;
use App\Models\Guardian;
use App\Models\Pwd;
use Illuminate\Auth\Events\Registered;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Mail;
use Illuminate\Validation\Rules;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;
use Random\RandomException;

class RegisteredUserController extends Controller
{
    /**
     * Display the registration view.
     */
    public function create(): View
    {
        return view('auth.register');
    }

    /**
     * Handle an incoming registration request.
     *
     * @throws ValidationException
     */
    public function store(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'guardian.firstname' => ['required', 'string', 'max:50'],
            'guardian.lastname' => ['required', 'string', 'max:50'],
            'guardian.middle_initial' => ['nullable', 'string', 'max:10'],
            'guardian.address' => ['required', 'string', 'max:255'],
            'guardian.email' => ['required', 'string', 'lowercase', 'email', 'max:255', 'unique:users,email'],
            'guardian.password' => ['required', 'string', 'min:8', 'confirmed'],

            'pwd.firstname' => ['required', 'string', 'max:50'],
            'pwd.lastname' => ['required', 'string', 'max:50'],
            'pwd.middle_initial' => ['nullable', 'string', 'max:50'],
        ]);

        try {
            // Generate OTP
            $otp = str_pad(random_int(0, 999999), 6, '0', STR_PAD_LEFT);
            $otpExpiresAt = now()->addMinutes(2);

            $cacheKey = 'registration_' . md5($validated['guardian']['email']);

            // Store registration data in cache
            Cache::put($cacheKey,[
                'guardian' => $validated['guardian'],
                'pwd' => $validated['pwd'],
                'otp' => $otp,
                'otp_expires_at' => $otpExpiresAt->toDateTimeString(),
                ],now()->addMinutes(10));

            \Log::info('Registration data stored in cache', [
                'cache_key' => $cacheKey,
                'email' => $validated['guardian']['email'],
                'otp' => $otp, // Remove this in production!
            ]);

            // Send OTP email
            $guardianName = $validated['guardian']['firstname'] . ' ' . $validated['guardian']['lastname'];
            Mail::to($validated['guardian']['email'])->send(new OtpMail($otp, $guardianName));

            return response()->json([
                'message' => 'Registration initiated. Please check your email for a verification code.',
                'email' => $validated['guardian']['email'],
                'requires_verification' => true,
            ], 201);

        } catch (\Throwable $e) {
            \Log::error('Registration initiation failed: ' . $e->getMessage());

            return response()->json([
                'message' => 'Registration failed',
                'error' => $e->getMessage(),
            ], 500);
        }
    }

    public function verifyOtp(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'email' => ['required', 'email'],
            'otp' => ['required', 'string', 'size:6'],
        ]);

        // Retrieve pending registration data from session
        $cacheKey = 'registration_' . md5($validated['email']);

        \Log::info('Attempting OTP verification', [
            'cache_key' => $cacheKey,
            'email' => $validated['email'],
        ]);

        $pendingData = Cache::get($cacheKey);

        if (!$pendingData) {
            return response()->json([
                'message' => 'No pending registration found. Please register again.',
            ], 400);
        }

        // Verify email matches
        if ($pendingData['guardian']['email'] !== $validated['email']) {
            return response()->json([
                'message' => 'Email does not match pending registration.',
            ], 400);
        }

        // Verify OTP
        if ($pendingData['otp'] !== $validated['otp']) {
            return response()->json([
                'message' => 'Invalid verification code.',
            ], 400);
        }

        // Check if OTP has expired
        if (now()->gt($pendingData['otp_expires_at'])) {
            return response()->json([
                'message' => 'Verification code has expired. Please request a new one.',
            ], 400);
        }

        // OTP is valid! Now create database records with proper error handling
        try {
            DB::beginTransaction();

            // Create Guardian User
            $guardianUser = User::create([
                'name' => $pendingData['guardian']['firstname'] . ' ' . $pendingData['guardian']['lastname'],
                'email' => $pendingData['guardian']['email'],
                'password' => Hash::make($pendingData['guardian']['password']),
                'role' => 'guardian',
                'is_verified' => true,
            ]);

            // Set email_verified_at separately (if it's guarded)
            $guardianUser->email_verified_at = now();
            $guardianUser->save();

            // Create Guardian record
            $guardian = Guardian::create([
                'user_id' => $guardianUser->id,
                'firstname' => $pendingData['guardian']['firstname'],
                'lastname' => $pendingData['guardian']['lastname'],
                'middle_initial' => $pendingData['guardian']['middle_initial'] ?? null,
                'address' => $pendingData['guardian']['address'],
            ]);

            // Create PWD User
            $pwdUser = User::create([
                'name' => $pendingData['pwd']['firstname'] . ' ' . $pendingData['pwd']['lastname'],
                'email' => 'pwd_' . $guardianUser->id . '_' . time() . '@walksense.local',
                'password' => null,
                'role' => 'pwd',
                'is_verified' => true,
            ]);

            // Set email_verified_at for PWD user
            $pwdUser->email_verified_at = now();
            $pwdUser->save();

            // Create PWD record
            $pwd = Pwd::create([
                'user_id' => $pwdUser->id,
                'guardian_id' => $guardianUser->id,
                'firstname' => $pendingData['pwd']['firstname'],
                'lastname' => $pendingData['pwd']['lastname'],
                'middle_initial' => $pendingData['pwd']['middle_initial'] ?? null,
            ]);

            DB::commit();
            Cache::forget($cacheKey);

            // Fire registered event
            event(new Registered($guardianUser));

            return response()->json([
                'message' => 'Account verified successfully! You can now login.',
                'user' => $guardianUser,
            ], 200);

        } catch (\Throwable $e) {
            DB::rollBack();

            \Log::error('Account creation failed after OTP verification: ' . $e->getMessage());
            \Log::error('Stack trace: ' . $e->getTraceAsString());

            return response()->json([
                'message' => 'Failed to create account. Please try again.',
                'error' => config('app.debug') ? $e->getMessage() : 'An error occurred',
            ], 500);
        }
    }

    /**
     * @throws RandomException
     */
    public function resendOtp(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'email' => ['required', 'email'],
        ]);

        try {

            $cacheKey = 'registration_' . md5($validated['email']);

            \Log::info('Attempting to resend OTP', [
                'cache_key' => $cacheKey,
                'email' => $validated['email'],
            ]);

            $pendingData = Cache::get($cacheKey);

            if (!$pendingData) {
                \Log::warning('No pending registration found for OTP resend', [
                    'cache_key' => $cacheKey,
                    'email' => $validated['email'],
                ]);

                return response()->json([
                    'message' => 'No pending registration found. Please register again.',
                ], 404);
            }

            // Verify email matches
            if ($pendingData['guardian']['email'] !== $validated['email']) {
                return response()->json([
                    'message' => 'Email does not match pending registration.',
                ], 404);
            }

            // Generate new OTP
            $otp = str_pad(random_int(0, 999999), 6, '0', STR_PAD_LEFT);
            $otpExpiresAt = now()->addMinutes(2);

            // Update cache with new OTP
            $pendingData['otp'] = $otp;
            $pendingData['otp_expires_at'] = $otpExpiresAt->toDateTimeString();

            Cache::put($cacheKey, $pendingData, now()->addMinutes(10));


            // Send new OTP email
            $guardianName = $pendingData['guardian']['firstname'] . ' ' . $pendingData['guardian']['lastname'];
            Mail::to($validated['email'])->send(new OtpMail($otp, $guardianName));

            return response()->json([
                'message' => 'Verification code resent successfully.',
            ], 200);

        } catch (\Throwable $e) {
            \Log::error('Resend OTP failed: ' . $e->getMessage());

            return response()->json([
                'message' => 'Failed to resend verification code.',
                'error' => config('app.debug') ? $e->getMessage() : 'An error occurred',
            ], 500);
        }
    }
}
