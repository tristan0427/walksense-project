<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Pwd extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id',
        'guardian_id',
        'firstname',
        'lastname',
        'middle_initial',
    ];

    // Relationship to User
    public function user():BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    // Relationship to Guardian
    public function guardian():BelongsTo
    {
        return $this->belongsTo(User::class, 'guardian_id');
    }

    public function guardianProfile():BelongsTo
    {
        return $this->belongsTo(Guardian::class, 'guardian_id', 'user_id');
    }
}
