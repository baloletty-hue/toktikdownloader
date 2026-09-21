package com.tiktokhd.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Saves into Downloads/TikTok/ using MediaStore on Android 10+, plain files below that. */
object Storage {

    private const val SUBDIR = "TikTok"
    private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "/"

    sealed class Target {
        abstract val displayPath: String
        abstract fun open(): OutputStream
        abstract fun finish()
        abstract fun abort()
    }

    private class MediaStoreTarget(
        private val context: Context,
        private val uri: Uri,
        override val displayPath: String
    ) : Target() {
        override fun open(): OutputStream =
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw Downloader.DownloadError("Cannot open the destination file.")

        override fun finish() {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }

        override fun abort() {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private class FileTarget(
        private val file: File,
        override val displayPath: String
    ) : Target() {
        override fun open(): OutputStream {
            file.parentFile?.mkdirs()
            return FileOutputStream(file)
        }

        override fun finish() { /* nothing to do */ }

        override fun abort() {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    fun exists(context: Context, name: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "%$SUBDIR%"),
                null
            )?.use { it.count > 0 } ?: false
        } else {
            File(legacyDir(), name).exists()
        }
    }

    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)

    fun create(context: Context, name: String): Target {
        val shown = "Downloads/$SUBDIR/$name"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Downloader.DownloadError("Cannot create the file in Downloads.")
            MediaStoreTarget(context, uri, shown)
        } else {
            FileTarget(File(legacyDir(), name), shown)
        }
    }
}
