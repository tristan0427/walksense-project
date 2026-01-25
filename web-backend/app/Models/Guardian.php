<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Guardian extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id',
        'firstname',
        'lastname',
        'middle_initial',
        'address',
    ];

    // Relationship to User
    public function user():BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    // Relationship to PWDs
    public function pwds():HasMany
    {
        return $this->hasMany(Pwd::class, 'guardian_id', 'user_id');
    }
}
