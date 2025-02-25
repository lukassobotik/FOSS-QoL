package dev.lukassobotik.fossqol.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import java.io.IOException

/**
 * Extension function that registers a launcher for sharing-to-save operations.
 *
 * @param getCurrentSourceUri A lambda that returns the current source URI to be saved.
 * @param onComplete A lambda invoked when the save operation completes. By default, it calls
 *                   finishAndRemoveTask() on the activity.
 */
fun ComponentActivity.registerShareToSaveLauncher(
    getCurrentSourceUri: () -> Uri?,
    onComplete: () -> Unit = { finishAndRemoveTask() }
): ActivityResultLauncher<CreateDocumentRequest> {
    return registerForActivityResult(CreateDocumentContract()) { destinationUri ->
        destinationUri?.let { destUri ->
            getCurrentSourceUri()?.let { sourceUri ->
                FileSavingUtils.saveUriToDocument(this, destUri, sourceUri, onComplete)
            }
        }
    }
}

// Data class for passing parameters to the create document intent.
data class CreateDocumentRequest(val mimeType: String, val suggestedName: String)

// ActivityResultContract to create a document (file) with the system file picker.
class CreateDocumentContract : ActivityResultContract<CreateDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: CreateDocumentRequest): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType
            putExtra(Intent.EXTRA_TITLE, input.suggestedName)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

/**
 * Utility functions for sharing-to-save.
 */
object FileSavingUtils {

    /**
     * Copies the content from [sourceUri] to the [destinationUri].
     *
     * @param context The context to access the ContentResolver and show Toast messages.
     * @param destinationUri The URI where the file should be saved.
     * @param sourceUri The URI of the file to be saved.
     * @param onComplete Optional callback when the save operation completes.
     */
    fun saveUriToDocument(
        context: Context,
        destinationUri: Uri,
        sourceUri: Uri,
        onComplete: (() -> Unit)? = null
    ) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Toast.makeText(context, "File saved successfully.", Toast.LENGTH_SHORT).show()
            onComplete?.invoke()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save file.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Attempts to extract a file name from the given [uri].
     */
    fun extractFileName(uri: Uri): String? {
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
