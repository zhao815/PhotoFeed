package com.example.photofeed.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object MediaQueryHelper {
    private const val TAG = "MediaQueryHelper"
    private val contentUri: Uri = MediaStore.Files.getContentUri("external")

    private const val COL_SPECIAL_FORMAT = "special_format"
    private const val SPECIAL_MOTION_PHOTO = 2

    /**
     * Files._ID and Images/Video._ID share the same underlying row id, so the
     * typed collections resolve to the same media object. Item URIs must be
     * typed: createDeleteRequest and some ROMs reject external/file URIs.
     */
    private fun typedUri(mediaType: Int, id: Long): Uri = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
            android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
            )
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
            android.content.ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
            )
        else -> Uri.withAppendedPath(contentUri, id.toString())
    }

    fun queryPhotos(cr: ContentResolver): List<MediaItemUi> {
        val withSpecial = query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "AND (special_format IS NULL OR special_format != $SPECIAL_MOTION_PHOTO)",
            true
        )
        if (withSpecial != null) return withSpecial

        return query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
            false
        ) ?: emptyList()
    }

    fun queryVideos(cr: ContentResolver): List<MediaItemUi> =
        query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}",
            true
        ) ?: query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}",
            false
        ) ?: emptyList()

    fun queryMotion(cr: ContentResolver): List<MediaItemUi> =
        query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "AND special_format = $SPECIAL_MOTION_PHOTO",
            true
        ) ?: emptyList()

    /** 不依赖 special_format 列的所有图片，用于动态照片的手动扫描。 */
    fun queryAllImages(cr: ContentResolver): List<MediaItemUi> =
        query(
            cr,
            "MEDIA_TYPE = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
            false
        ) ?: emptyList()

    fun queryAll(cr: ContentResolver): List<MediaItemUi> =
        query(cr, null, true) ?: emptyList()

    private fun query(
        cr: ContentResolver,
        selection: String?,
        withSpecialFormat: Boolean
    ): List<MediaItemUi>? {
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (withSpecialFormat) {
            projection.add(COL_SPECIAL_FORMAT)
        }

        val cursor: Cursor = try {
            cr.query(
                contentUri,
                projection.toTypedArray(),
                selection,
                null,
                "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
            )
        } catch (e: Exception) {
            Log.w(TAG, "query failed (specialFormat=$withSpecialFormat): ${e.message}")
            null
        } ?: return null

        val items = ArrayList<MediaItemUi>(cursor.count)
        try {
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val durCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
            val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val specialCol = cursor.getColumnIndex(COL_SPECIAL_FORMAT)
            val dateModifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mediaType = cursor.getInt(typeCol)
                val type = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                        val special = if (specialCol >= 0) cursor.getInt(specialCol) else 0
                        if (special == SPECIAL_MOTION_PHOTO) {
                            MediaItemUi.MediaType.MOTION
                        } else {
                            MediaItemUi.MediaType.PHOTO
                        }
                    }
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaItemUi.MediaType.VIDEO
                    else -> continue
                }

                var dateTaken = cursor.getLong(dateCol)
                if (dateTaken <= 0L && dateModifiedCol >= 0) {
                    dateTaken = cursor.getLong(dateModifiedCol) * 1000L
                }
                val size = cursor.getLong(sizeCol)
                val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                val name = cursor.getString(nameCol)
                val w = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                val h = if (heightCol >= 0) cursor.getInt(heightCol) else 0

                items.add(
                    MediaItemUi(
                        mediaKey = MediaKey.build(id, dateTaken, size),
                        uri = typedUri(mediaType, id),
                        type = type,
                        dateTaken = dateTaken,
                        size = size,
                        durationMs = if (type == MediaItemUi.MediaType.VIDEO) duration else null,
                        fileName = name,
                        width = w,
                        height = h
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "cursor read failed: ${e.message}")
        } finally {
            cursor.close()
        }
        return items
    }
}
