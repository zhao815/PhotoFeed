package com.example.photofeed.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val channel: String,
    val shuffleSeed: Long,
    val lastIndex: Int,
    val lastMediaKey: String?
)
