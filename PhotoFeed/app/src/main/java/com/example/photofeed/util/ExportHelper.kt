package com.example.photofeed.util

import android.content.Context
import android.net.Uri
import com.example.photofeed.db.NoteDao
import com.example.photofeed.db.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object ExportHelper {
    data class ImportResult(val imported: Int, val unmatched: Int)

    suspend fun export(dao: NoteDao): String = exportWithCount(dao).first

    suspend fun exportWithCount(dao: NoteDao): Pair<String, Int> = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        val arr = JSONArray()
        all.forEach { n ->
            val o = JSONObject()
            o.put("mediaKey", n.mediaKey)
            o.put("noteText", n.noteText ?: JSONObject.NULL)
            o.put("liked", n.liked)
            o.put("dateTaken", n.dateTaken)
            o.put("size", n.size)
            arr.put(o)
        }
        val wrapper = JSONObject()
        wrapper.put("version", 1)
        wrapper.put("notes", arr)
        wrapper.toString(2) to all.size
    }

    suspend fun import(dao: NoteDao, json: String): ImportResult = withContext(Dispatchers.IO) {
        val trimmed = json.trim()
        val array: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("notes") ?: JSONArray()
        }

        var imported = 0
        var unmatched = 0
        val now = System.currentTimeMillis()

        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val mediaKey = o.optString("mediaKey", "")
            val dateTaken = o.optLong("dateTaken", 0L)
            val size = o.optLong("size", 0L)

            val targetKey: String = when {
                mediaKey.isNotEmpty() -> mediaKey
                else -> {
                    val existing = dao.findByDateAndSize(dateTaken, size)
                    existing?.mediaKey ?: ""
                }
            }
            if (targetKey.isEmpty()) {
                unmatched++
                continue
            }

            val noteText = if (o.isNull("noteText")) null else o.optString("noteText", "")
            val liked = o.optBoolean("liked", false)
            val finalText = noteText?.takeIf { it.isNotEmpty() }
            if (finalText == null && !liked) {
                dao.deleteByKey(targetKey)
            } else {
                dao.upsert(
                    NoteEntity(
                        mediaKey = targetKey,
                        noteText = finalText,
                        liked = liked,
                        dateTaken = dateTaken,
                        size = size,
                        updatedAt = now
                    )
                )
            }
            imported++
        }
        ImportResult(imported, unmatched)
    }
}
