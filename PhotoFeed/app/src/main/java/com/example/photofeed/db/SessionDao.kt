package com.example.photofeed.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE channel = :channel LIMIT 1")
    suspend fun getSession(channel: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)
}
