<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use App\Http\Requests\Auth\LoginRequest;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Validator;
use Illuminate\View\View;
use App\Models\User;
use App\Models\Pwd;

class AuthenticatedSessionController extends Controller
{
    /**
     * Display the login view.
     */
    public function create(): View
    {
        return view('auth.login');
    }

    /**
     * Handle an incoming authentication request.
     */
    public function store(LoginRequest $request): JsonResponse
    {
        $request->authenticate();

        $validator = Validator::make($request->all(), [
            'email' => 'required|email',
            'password' => 'required|string',
            'login_as' => 'nullable|in:guardian,pwd',
        ]);

        if($validator->fails()){
            return response()->json([
                'success' => false,
                'message' => 'Validation failed',
                'errors' => $validator->errors()
            ], 422);
        }

        $user = User::where('email', $request->email)->first();

        if(!$user){
            return response()->json([
                'success' => false,
                'message' => 'Invalid credentials',
            ], 401);
        }

        if ($request->login_as === 'pwd' && $user->role === 'guardian'){
            $pwd = Pwd::where('guardian_id', $user->id)->first();

            if (!$pwd){
                return response()->json([
                    'success' => false,
                    'message' => 'No PWD account found for this guardian',
                ],404);
            }
            $pwdUser = User::find($pwd->user_id);

            if(!$pwdUser){
                return response()->json([
                    'success' => false,
                    'message' => 'PWD user not found',
                ], 404);
            }

            $token = $pwdUser->createToken('mobile-token')->plainTextToken;

            return response()->json([
                'success' => true,
                'message' => 'Login successful as PWD',
                'token' => $token,
                'user' => [
                    'id' => $pwdUser->id,
                    'name' => $pwdUser->name,
                    'email' => $pwdUser->email,
                    'role' => $pwdUser->role,
                ],
                'guardian' => [
                    'id' => $user->id,
                    'name' => $user->name,
                    'email' => $user->email,
                ]
            ]);
        }

        //  Normal guardian login
        if ($user->role === 'guardian') {
            $token = $user->createToken('mobile-token')->plainTextToken;

            return response()->json([
                'success' => true,
                'message' => 'Login successful',
                'token' => $token,
                'user' => [
                    'id' => $user->id,
                    'name' => $user->name,
                    'email' => $user->email,
                    'role' => $user->role,
                ]
            ]);
        }

        return response()->json([
            'success' => false,
            'message' => 'Invalid user role'
        ], 403);
    }


    /**
     * Destroy an authenticated session.
     */
    public function destroy(Request $request): JsonResponse
    {
        if ($request->user()) {
            $request->user()->currentAccessToken()->delete();
        }

        return response()->json([
            'success' => true,
            'message' => 'Logged out successfully'
        ]);
    }
}
