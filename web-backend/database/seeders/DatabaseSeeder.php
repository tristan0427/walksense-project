<?php

namespace Database\Seeders;

use App\Models\User;
use App\Models\Guardian;
use App\Models\Pwd;
use Illuminate\Support\Facades\Hash;
// use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class DatabaseSeeder extends Seeder
{
    /**
     * Seed the application's database.
     */
    public function run(): void
    {
        // Create Guardian User
        $guardianUser = User::create([
            'name' => 'Maria Santos',
            'email' => 'guardian@test.com',
            'email_verified_at' => now(),
            'role' => 'guardian',
            'password' => Hash::make('password123'),
        ]);


        Guardian::create([
            'user_id' => $guardianUser->id,
            'firstname' => 'Maria',
            'lastname' => 'Santos',
            'middle_initial' => 'R',
            'address' => '123 Main Street, Davao City',
        ]);

        // Create PWD User
        $pwdUser = User::create([
            'name' => 'John Doe',
            'email' => 'pwd@test.com',
            'email_verified_at' => now(),
            'role' => 'pwd',
            'password' => null,
        ]);

        // Create PWD Profile
        Pwd::create([
            'user_id' => $pwdUser->id,
            'guardian_id' => $guardianUser->id,
            'firstname' => 'John',
            'lastname' => 'Doe',
            'middle_initial' => 'A',
        ]);
    }
}
