package com.example.photofeed.data

import android.content.Context
import com.example.photofeed.db.AppDatabase
import com.example.photofeed.db.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object NoteStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _notes = MutableStateFlow<Map<String, NoteEntity>>(emptyMap())
    val notes: StateFlow<Map<String, NoteEntity>> = _notes.asStateFlow()

    private var dao: com.example.photofeed.db.NoteDao? = null

    fun init(context: Context) {
        if (dao != null) return
        val d = AppDatabase.get(context).noteDao()
        dao = d
        scope.launch {
            val all = d.getAll()
            _notes.value = all.associateBy { it.mediaKey }
        }
    }

    fun noteFor(mediaKey: String): NoteEntity? = _notes.value[mediaKey]

    fun isLiked(mediaKey: String): Boolean = _notes.value[mediaKey]?.liked == true

    fun textFor(mediaKey: String): String? =
        _notes.value[mediaKey]?.noteText?.takeIf { it.isNotEmpty() }

    fun setNote(item: MediaItemUi, text: String?) {
        val d = dao ?: return
        val existing = _notes.value[item.mediaKey]
        val liked = existing?.liked ?: false
        val finalText = text?.trim()?.takeIf { it.isNotEmpty() }
        if (finalText == null && !liked) {
            remove(item.mediaKey)
            return
        }
        val entity = NoteEntity(
            mediaKey = item.mediaKey,
            noteText = finalText,
            liked = liked,
            dateTaken = item.dateTaken,
            size = item.size,
            updatedAt = System.currentTimeMillis()
        )
        _notes.value = _notes.value + (item.mediaKey to entity)
        scope.launch { d.upsert(entity) }
    }

    fun toggleLike(item: MediaItemUi) {
        val d = dao ?: return
        val existing = _notes.value[item.mediaKey]
        val newLiked = !(existing?.liked ?: false)
        val text = existing?.noteText
        if (!newLiked && text.isNullOrEmpty()) {
            remove(item.mediaKey)
            return
        }
        val entity = NoteEntity(
            mediaKey = item.mediaKey,
            noteText = text,
            liked = newLiked,
            dateTaken = item.dateTaken,
            size = item.size,
            updatedAt = System.currentTimeMillis()
        )
        _notes.value = _notes.value + (item.mediaKey to entity)
        scope.launch { d.upsert(entity) }
    }

    fun remove(mediaKey: String) {
        val d = dao ?: return
        _notes.value = _notes.value - mediaKey
        scope.launch { d.deleteByKey(mediaKey) }
    }

    fun refresh() {
        val d = dao ?: return
        scope.launch {
            _notes.value = d.getAll().associateBy { it.mediaKey }
        }
    }
}
