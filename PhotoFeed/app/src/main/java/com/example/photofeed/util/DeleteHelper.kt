package com.example.photofeed.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object DeleteHelper {
    /**
     * Returns the system delete-request PendingIntent on Android 11+.
     * Callers must launch it through an ActivityResultLauncher so the user sees
     * the system confirmation dialog.
     */
    fun buildDeleteRequest(context: Context, uris: Collection<Uri>): PendingIntent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("createDeleteRequest requires API 30")
        }
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    /**
     * Android 10 path: a direct delete raises RecoverableSecurityException.
     * Returns the IntentSender the user must confirm, or null if the delete
     * already succeeded (or failed for another reason).
     */
    fun deleteWithRecovery(context: Context, uri: Uri): Result {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) Result.Deleted else Result.Failed
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recovery = (e as? android.app.RecoverableSecurityException)
                if (recovery != null) {
                    Result.NeedsConfirmation(recovery.userAction.actionIntent.intentSender)
                } else {
                    Result.Failed
                }
            } else {
                Result.Failed
            }
        } catch (e: Exception) {
            Result.Failed
        }
    }

    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    sealed class Result {
        object Deleted : Result()
        object Failed : Result()
        data class NeedsConfirmation(val intentSender: android.content.IntentSender) : Result()
    }
}
