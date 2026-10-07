package com.tsetingdms.glasscamera.process

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves photos to the phone's gallery (DCIM/Glass Camera) through MediaStore, which needs no
 * storage permission. Pictures are stored as the sensor delivered them and turned upright /
 * mirrored with the EXIF orientation tag, which every gallery honours (no slow pixel rotation).
 */
object ImageSaver {
    const val FOLDER = "DCIM/Glass Camera"

    fun newValues(): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
    }

    suspend fun saveBitmap(context: Context, bitmap: Bitmap, rotation: Int, mirror: Boolean): Uri = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "pending-${System.nanoTime()}.jpg")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        publish(context, tmp, rotation, mirror)
    }

    suspend fun saveJpeg(context: Context, jpeg: ByteArray, rotation: Int, mirror: Boolean): Uri = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "pending-${System.nanoTime()}.jpg")
        tmp.writeBytes(jpeg)
        publish(context, tmp, rotation, mirror)
    }

    private fun publish(context: Context, tmp: File, rotation: Int, mirror: Boolean): Uri {
        try {
            val exif = ExifInterface(tmp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            // Turn upright first, then mirror left-right as seen (front camera, like the viewfinder).
            if (rotation % 360 != 0) exif.rotate(rotation)
            if (mirror) exif.flipHorizontally()
            val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, now)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Glass Camera")
            exif.saveAttributes()

            val resolver = context.contentResolver
            val values = newValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the photo in the gallery")
            try {
                val out = resolver.openOutputStream(uri) ?: error("Couldn't write the photo")
                out.use { stream -> tmp.inputStream().use { it.copyTo(stream) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        } finally {
            tmp.delete()
        }
    }

    /** The newest photo this app saved (Android only lists an app's own photos without a permission). */
    fun latest(context: Context): Uri? = try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$FOLDER%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null
        }
    } catch (e: Exception) {
        null
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(192, 192), null)
    } catch (e: Exception) {
        null
    }
}
