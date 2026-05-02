package com.longexposure.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Handles all file-system and MediaStore operations for saved images. */
object FileSaveHelper {

    private const val TAG = "FileSaveHelper"
    private const val FOLDER_NAME = "LongExposure"
    private const val MIME_TYPE = "image/jpeg"

    /**
     * Builds [ImageCapture.OutputFileOptions] targeting `DCIM/LongExposure`.
     *
     * @param context  application context
     * @param suffix   optional suffix appended before `.jpg` (e.g. `"-1EV"`, `"BULB"`)
     * @return a pair of the output options and, on pre-Q devices, the target [File]
     *         that must be passed to [notifyMediaScanner] after the image is saved.
     * @throws IllegalStateException if the output folder cannot be created on pre-Q.
     */
    fun buildOutputOptions(
        context: Context,
        suffix: String = ""
    ): Pair<ImageCapture.OutputFileOptions, File?> {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = if (suffix.isEmpty()) "LE_$timestamp.jpg" else "LE_${timestamp}_$suffix.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$FOLDER_NAME")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
            Pair(options, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val folder = File(dir, FOLDER_NAME)
            if (!folder.exists() && !folder.mkdirs()) {
                Log.e(TAG, "Failed to create output folder: ${folder.absolutePath}")
                throw IllegalStateException("Cannot create output folder: ${folder.absolutePath}")
            }
            val outputFile = File(folder, filename)
            Pair(ImageCapture.OutputFileOptions.Builder(outputFile).build(), outputFile)
        }
    }

    /** Notifies the system media scanner so the image appears in the Gallery. */
    fun notifyMediaScanner(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(MIME_TYPE),
            null
        )
    }
}
