<?php

use App\Http\Controllers\ProfileController;
use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\RegisteredUserController;
use Illuminate\Support\Facades\Route;
use Illuminate\Http\Request;

Route::post('/api/login', [AuthenticatedSessionController::class, 'store']);
Route::post('/api/register', [RegisteredUserController::class, 'store']);


// PROTECTED ROUTES (token required)
Route::middleware('auth:sanctum')->group(function () {

    Route::post('/api/logout', [AuthenticatedSessionController::class, 'destroy']);

    Route::get('/api/user', function (Request $request) {
        return response()->json([
            'user' => $request->user()
        ]);
    });

});


Route::get('/', function () {
    return view('welcome');
});

Route::get('/dashboard', function () {
    return view('dashboard');
})->middleware(['auth', 'verified'])->name('dashboard');

Route::middleware('auth')->group(function () {
    Route::get('/profile', [ProfileController::class, 'edit'])->name('profile.edit');
    Route::patch('/profile', [ProfileController::class, 'update'])->name('profile.update');
    Route::delete('/profile', [ProfileController::class, 'destroy'])->name('profile.destroy');
});

require __DIR__.'/auth.php';
