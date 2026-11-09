package com.example.photofeed.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM note WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun getNote(mediaKey: String): NoteEntity?

    @Query("SELECT * FROM note WHERE mediaKey = :mediaKey LIMIT 1")
    fun observeNote(mediaKey: String): Flow<NoteEntity?>

    @Query("SELECT * FROM note WHERE noteText IS NOT NULL AND noteText != ''")
    suspend fun getAllNotes(): List<NoteEntity>

    @Query("SELECT * FROM note WHERE dateTaken = :dateTaken AND size = :size LIMIT 1")
    suspend fun findByDateAndSize(dateTaken: Long, size: Long): NoteEntity?

    @Query("SELECT * FROM note")
    suspend fun getAll(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM note WHERE mediaKey = :mediaKey")
    suspend fun deleteByKey(mediaKey: String)
}
