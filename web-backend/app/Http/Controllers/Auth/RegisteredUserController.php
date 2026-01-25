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
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Mail;
use Illuminate\Validation\Rules;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;

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

        try{
            DB::beginTransaction();

            $otp =str_pad(random_int(0,999999), 6, '0', STR_PAD_LEFT);
            $otpExpiresAt = now()->addMinutes(2);

            $guardianUser = User::create([
                'name' => $validated['guardian']['firstname'] . ' ' . $validated['guardian']['lastname'],
                'email' => $validated['guardian']['email'],
                'password' => Hash::make($validated['guardian']['password']),
                'role' => 'guardian',
                'otp' => $otp,
                'otp_expires_at' => $otpExpiresAt,
                'is_verified' => false,
            ]);

            $guardian = Guardian::create([
                'user_id' => $guardianUser->id,
                'firstname' => $validated['guardian']['firstname'],
                'lastname' => $validated['guardian']['lastname'],
                'middle_initial' => $validated['guardian']['middle_initial'] ?? null,
                'address' => $validated['guardian']['address'],
            ]);

            $pwduser = User::create([
                'name' => $validated['pwd']['firstname'] . ' ' . $validated['pwd']['lastname'],
                'email' => 'pwd_'.$guardianUser->id . '_' . time() . '@walksense.local',
                'password' => null,
                'role' => 'pwd',
                'is_verified' => true,
            ]);

            $pwd = Pwd::create([
                'user_id' => $pwduser->id,
                'guardian_id' => $guardian->id,
                'firstname' => $validated['pwd']['firstname'],
                'lastname' => $validated['pwd']['lastname'],
                'middle_initial' => $validated['pwd']['middle_initial'] ?? null,
            ]);

            Mail::to($guardianUser->email)->send(new OtpMail($otp, $guardianUser->name));


            DB::commit();
            event(new Registered($guardianUser));

            return response()->json([
                'message' => 'Registration successfully. Please check your email for a verification code.',
                'email' => $guardianUser->email,
                'requires_verification' => true,
            ], 201);
        }catch (\Exception $e){
            DB::rollBack();
            \Log::error('Registration failed: ' . $e->getMessage());

            return response()->json([
                'message' => 'Registration failed',
                'error' => $e->getMessage(),
            ],500);
        }
    }

    public function verifyOtp(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'email'=> ['required', 'email'],
            'otp' => ['required', 'string', 'size:6'],
        ]);

        $user = User::where('email', $validated['email'])->first();

        if(!$user){
            return response()->json([
               'message' => 'User not found.',
            ],404);
        }

        if($user->is_verified){
            return response()->json([
                'message' => 'Account already verified.',
            ],400);
        }

        if ($user->otp !== $validated['otp']) {
            return response()->json([
                'message' => 'Invalid verification code',
            ], 400);
        }

        if (now()->gt($user->otp_expires_at)) {
            return response()->json([
                'message' => 'Verification code has expired',
            ], 400);
        }

        $user->update([
           'is_verified' => true,
           'otp' => null,
           'otp_expires_at' => null,
           'email_verified_at' => now(),
        ]);

        return response()->json([
            'message' => 'Account already successfully',
            'user' => $user,
        ],200);
    }

    public function resendOtp(Request $request): JsonResponse{
        $validated = $request->validate([
            'email'=> ['required', 'email'],
        ]);

        $user = User::where('email', $validated['email'])->first();

        if (!$user) {
            return response()->json([
                'message' => 'User not found',
            ], 404);
        }

        if ($user->is_verified) {
            return response()->json([
                'message' => 'Account already verified',
            ], 400);
        }

        $otp = str_pad(random_int(0, 999999), 6, '0', STR_PAD_LEFT);
        $otpExpiresAt = now()->addMinutes(2);

        $user->update([
            'otp' => $otp,
            'otp_expires_at' => $otpExpiresAt,
        ]);

        Mail::to($user->email)->send(new OtpMail($otp, $user->email));

        return response()->json([
            'message' => 'Verification code resent successfully',
        ],200);
    }
}
