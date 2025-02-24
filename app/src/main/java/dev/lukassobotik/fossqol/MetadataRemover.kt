package dev.lukassobotik.fossqol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

suspend fun removeMetadata(context: Context, sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
    try {
        // Get MIME type to branch processing logic
        val mimeType = context.contentResolver.getType(sourceUri) ?: ""
        return@withContext when {
            mimeType.equals("image/jpeg", ignoreCase = true) ||
                    mimeType.equals("image/jpg", ignoreCase = true) -> {
                processJpeg(context, sourceUri)
            }
            mimeType.equals("image/png", ignoreCase = true) -> {
                processPng(context, sourceUri)
            }
            else -> {
                // Fallback to JPEG processing if file type is unknown
                processJpeg(context, sourceUri)
            }
        }
    } catch (e: Exception) {
        Log.e("MetadataRemover", "Error removing metadata", e)
        null
    }
}

private suspend fun processJpeg(context: Context, sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
    try {
        // Read EXIF data to get orientation
        context.contentResolver.openInputStream(sourceUri)?.use { exifInput ->
            val exif = ExifInterface(exifInput)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            // Open a new stream to decode the bitmap (previous stream was consumed)
            context.contentResolver.openInputStream(sourceUri)?.use { bitmapInput ->
                val bitmap = BitmapFactory.decodeStream(bitmapInput)
                if (bitmap == null) {
                    Log.e("MetadataRemover", "Failed to decode JPEG bitmap.")
                    return@withContext null
                }
                val rotatedBitmap = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                    else -> bitmap
                }
                // Save the clean image as JPEG with a random filename to remove both metadata and naming info
                val cleanFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
                FileOutputStream(cleanFile).use { outputStream ->
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    outputStream.flush()
                }
                return@withContext FileProvider.getUriForFile(context, "dev.lukassobotik.fossqol.fileprovider", cleanFile)
            }
        }
    } catch (e: Exception) {
        Log.e("MetadataRemover", "Error processing JPEG", e)
        null
    }
}

private suspend fun processPng(context: Context, sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
    try {
        // For PNG files, simply decode and re-encode them using PNG format.
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                Log.e("MetadataRemover", "Failed to decode PNG bitmap.")
                return@withContext null
            }
            // PNG re-encoding drops any ancillary text chunks.
            val cleanFile = File(context.cacheDir, "${UUID.randomUUID()}.png")
            FileOutputStream(cleanFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            }
            return@withContext FileProvider.getUriForFile(context, "dev.lukassobotik.fossqol.fileprovider", cleanFile)
        }
    } catch (e: Exception) {
        Log.e("MetadataRemover", "Error processing PNG", e)
        null
    }
}

private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(angle) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}