package com.example.photofeed.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey val mediaKey: String,
    val noteText: String?,
    val liked: Boolean,
    val dateTaken: Long,
    val size: Long,
    val updatedAt: Long
)
