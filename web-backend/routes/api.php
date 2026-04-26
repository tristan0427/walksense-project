<?php

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\RegisteredUserController;
use App\Http\Controllers\Auth\PasswordResetController;
use App\Http\Controllers\LocationController;

Route::post('/login', [AuthenticatedSessionController::class, 'store'])->name('api.login');
Route::post('/register', [RegisteredUserController::class, 'store'])->name('api.register');
Route::post('/verify-otp', [RegisteredUserController::class, 'verifyOtp'])->name('api.verify-otp');
Route::post('/resend-otp', [RegisteredUserController::class, 'resendOtp'])->name('api.resend-otp');

Route::post('/forgot-password/send-otp', [PasswordResetController::class, 'sendOtp']);
Route::post('/forgot-password/verify-otp', [PasswordResetController::class, 'verifyOtp']);
Route::post('/forgot-password/reset', [PasswordResetController::class, 'resetPassword']);

Route::middleware('auth:sanctum')->group(function () {

    Route::post('/location', [LocationController::class, 'store'])->name('api.location.store');
    Route::post('/logout', [AuthenticatedSessionController::class, 'destroy']);

    Route::post('/notifications', [\App\Http\Controllers\NotificationController::class, 'store']);
    Route::get('/notifications', [\App\Http\Controllers\NotificationController::class, 'index']);
    Route::delete('/notifications/{id}', [\App\Http\Controllers\NotificationController::class, 'destroy']);

    Route::get('/location/pwd/{pwdUserId}', [LocationController::class, 'getCurrentLocations'])->name('api.location.current');
    Route::get('/location/pwd/{pwdUserId}/history', [LocationController::class, 'getLocationHistory'])->name('api.location.history');
    Route::get('/location/all-pwds', [LocationController::class, 'getAllPwdLocations'])->name('api.location.all');


    Route::get('/user', function (Request $request) {
        return response()->json([
            'user' => $request->user(),
        ]);
    });
});
