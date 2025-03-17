package dev.lukassobotik.fossqol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import dev.lukassobotik.fossqol.utils.CreateDocumentRequest
import dev.lukassobotik.fossqol.utils.FileSavingUtils
import dev.lukassobotik.fossqol.utils.registerShareToSaveLauncher

class ShareToSaveActivity : ComponentActivity() {

    private var currentSourceUri: Uri? = null
    private lateinit var createDocumentLauncher: ActivityResultLauncher<CreateDocumentRequest>

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createDocumentLauncher = registerShareToSaveLauncher(
            getCurrentSourceUri = { currentSourceUri },
            onComplete = { finishAndRemoveTask() }
        )

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.type?.let { mimeType ->
                    if (mimeType != "text/plain") {
                        val sourceUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        sourceUri?.let {
                            currentSourceUri = it
                            val suggestedName =
                                FileSavingUtils.extractFileName(it) ?: "shared_file_${System.currentTimeMillis()}"
                            createDocumentLauncher.launch(CreateDocumentRequest(mimeType, suggestedName))
                        }
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // TODO: Handle multiple files appropriately.
                intent.type?.let { mimeType ->
                    if (mimeType != "text/plain") {
                        val sourceUris =
                            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        sourceUris?.firstOrNull()?.let { uri ->
                            currentSourceUri = uri
                            val suggestedName =
                                FileSavingUtils.extractFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
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
                            tintColor = tintColor
                        )
                    }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        InfoMessage()
                    }
                }
            }
        }
    }
}

@Composable
fun InfoMessage() {
    Text("Any non-text file shared here will be saved to your device using ACTION_CREATE_DOCUMENT.")
}
