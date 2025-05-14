package dev.lukassobotik.fossqol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import dev.lukassobotik.fossqol.utils.CreateDocumentRequest
import dev.lukassobotik.fossqol.utils.registerShareToSaveLauncher
import dev.lukassobotik.fossqol.utils.removeMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CleanShareActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var cleanedUri by mutableStateOf<Uri?>(null)

    private lateinit var saveLauncher: ActivityResultLauncher<CreateDocumentRequest>

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for a shared image from an incoming intent.
        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            selectedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        enableEdgeToEdge()

        // Register the file-saving launcher.
        saveLauncher = registerShareToSaveLauncher(
            getCurrentSourceUri = { cleanedUri },
            onComplete = { Toast.makeText(this, "Saved image to gallery.", Toast.LENGTH_SHORT).show() }
        )

        setContent {
            val tintColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@CleanShareActivity,
                            label = "Clean Share",
                            showBackButton = true,
                            tintColor = tintColor,
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        MetadataRemoverScreen(
                            selectedUri = selectedUri,
                            cleanedUri = cleanedUri,
                            onCleanedUriChange = { cleanedUri = it },
                            onResetImage = {
                                selectedUri = null
                                cleanedUri = null
                            },
                            onSaveImage = {
                                // Fire the save launcher using the cleaned image.
                                cleanedUri?.let { uri ->
                                    val mimeType = contentResolver.getType(uri) ?: "image/*"
                                    val suggestedName = dev.lukassobotik.fossqol.utils.FileSavingUtils.extractFileName(uri)
                                        ?: "image.jpg"
                                    saveLauncher.launch(CreateDocumentRequest(mimeType, suggestedName))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Legacy onActivityResult for image selection.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Codes.REQUEST_CODE_SELECT_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedUri = uri
                // Persist permission to access the selected file.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}

object Codes {
    internal const val REQUEST_CODE_SELECT_IMAGE = 1001
}

/**
 * - [selectedUri]: the original image selected by the user.
 * - [cleanedUri]: the cleaned (metadata removed) image, if available.
 * - [onCleanedUriChange]: callback to update the cleaned image.
 * - [onResetImage]: callback to clear the selection.
 * - [onSaveImage]: callback to trigger file saving.
 */
@Composable
fun MetadataRemoverScreen(
    selectedUri: Uri?,
    cleanedUri: Uri?,
    onCleanedUriChange: (Uri?) -> Unit,
    onResetImage: () -> Unit,
    onSaveImage: () -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content area.
        if (selectedUri == null) {
            // No image selected.
            Text(
                text = "No image selected",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // Show preview of either the cleaned image or the original.
            val uriToShow = cleanedUri ?: selectedUri
            Image(
                painter = rememberAsyncImagePainter(model = uriToShow),
                contentDescription = "Image Preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 100.dp), // Reserve space for the bottom controls.
                contentScale = ContentScale.Fit
            )
        }

        // Bottom control area.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            when {
                selectedUri == null -> {
                    // Show "Select Image" when nothing is selected.
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            }
                            (context as? Activity)?.startActivityForResult(
                                intent,
                                Codes.REQUEST_CODE_SELECT_IMAGE
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select Image")
                    }
                }
                cleanedUri == null -> {
                    // When an image is selected but not yet processed.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    val result = removeMetadataProcess(context, selectedUri)
                                    onCleanedUriChange(result)
                                    isProcessing = false
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isProcessing) "Processing..." else "Remove Metadata")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onResetImage()
                                onCleanedUriChange(null)
                            }
                        ) {
                            Text("X")
                        }
                    }
                }
                else -> {
                    // After processing: show "Share", "Save", and reset ("X") buttons.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // Share the cleaned image.
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(cleanedUri)
                                        ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, cleanedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share Clean Image")
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Share")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSaveImage,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onResetImage()
                                onCleanedUriChange(null)
                            }
                        ) {
                            Text("X")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simulate the metadata removal process.
 */
suspend fun removeMetadataProcess(context: android.content.Context, uri: Uri): Uri? {
    delay(2000) // Simulate processing delay
    return removeMetadata(context, uri)
}