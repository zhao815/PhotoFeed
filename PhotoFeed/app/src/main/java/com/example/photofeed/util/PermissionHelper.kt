package com.example.photofeed.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun readPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun requestedPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullAccess(context: Context): Boolean {
        val required = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            Build.VERSION.SDK_INT >= 33 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val selected = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
        return selected && !hasFullAccess(context)
    }

    fun hasAnyAccess(context: Context): Boolean {
        if (hasFullAccess(context) || hasPartialAccess(context)) return true
        // Android 13 lets the user grant only one of images/videos. Don't lock
        // the UI in that case; the empty channel message covers the rest.
        return readPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasEverAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private const val KEY_ASKED = "has_asked_permission"

    private fun prefs(context: Context) =
        context.getSharedPreferences("photofeed_prefs", Context.MODE_PRIVATE)
}
