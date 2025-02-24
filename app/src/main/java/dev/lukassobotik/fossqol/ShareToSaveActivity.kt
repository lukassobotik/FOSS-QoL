package dev.lukassobotik.fossqol

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import java.io.IOException

data class CreateDocumentRequest(val mimeType: String, val suggestedName: String)

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

class ShareToSaveActivity : ComponentActivity() {

    private var currentSourceUri: Uri? = null
    private lateinit var createDocumentLauncher: ActivityResultLauncher<CreateDocumentRequest>

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createDocumentLauncher = registerForActivityResult(CreateDocumentContract()) { destinationUri ->
            destinationUri?.let { destUri ->
                currentSourceUri?.let { sourceUri ->
                    saveUriToDocument(destUri, sourceUri)
                }
            }
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.type?.let { mimeType ->
                    if (mimeType != "text/plain") {
                        val sourceUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        sourceUri?.let {
                            currentSourceUri = it
                            val suggestedName = extractFileName(it) ?: "shared_file_${System.currentTimeMillis()}"
                            createDocumentLauncher.launch(CreateDocumentRequest(mimeType, suggestedName))
                        }
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // TODO: Handle multiple files
                intent.type?.let { mimeType ->
                    if (mimeType != "text/plain") {
                        val sourceUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        sourceUris?.firstOrNull()?.let { uri ->
                            currentSourceUri = uri
                            val suggestedName = extractFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
                            createDocumentLauncher.launch(CreateDocumentRequest(mimeType, suggestedName))
                        }
                    }
                }
            }
        }

        setContent {
            val tintColor = if (isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@ShareToSaveActivity,
                            label = "Share To Save",
                            showBackButton = true,
                            tintColor = tintColor)
                    }
                ) {
                    InfoMessage()
                }
            }
        }
    }

    /**
     * Copies the content from [sourceUri] to the [destinationUri] that the user selected.
     */
    private fun saveUriToDocument(destinationUri: Uri, sourceUri: Uri) {
        try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Toast.makeText(this, "File saved successfully.", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save file.", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }

    /**
     * Attempts to extract a file name from the [uri]. You can enhance this method to better extract
     * names (and file extensions) from various URIs.
     */
    private fun extractFileName(uri: Uri): String? {
        // TODO: Simple approach – might need a more robust solution in production.
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}

@Composable
fun InfoMessage() {
    Text("Any non-text file shared here will be saved to your device using ACTION_CREATE_DOCUMENT.")
}
