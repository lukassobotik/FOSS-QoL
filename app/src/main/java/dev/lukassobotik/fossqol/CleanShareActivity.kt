package dev.lukassobotik.fossqol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CleanShareActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            selectedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        enableEdgeToEdge()
        setContent {
            val tintColor =
                if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // Retaining your original topAppBar.
                        topAppBar(
                            context = this@CleanShareActivity,
                            label = "Clean Share",
                            showBackButton = true,
                            tintColor = tintColor
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
                            onResetImage = { selectedUri = null }
                        )
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Codes.REQUEST_CODE_SELECT_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedUri = uri
                // Persist permission to access the selected file
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

@Composable
fun MetadataRemoverScreen(
    selectedUri: Uri?,
    onResetImage: () -> Unit
) {
    val context = LocalContext.current
    var cleanUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content area
        if (selectedUri == null) {
            // No image selected: Center the message.
            Text(
                text = "No image selected",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // If an image is selected, display its preview.
            // If metadata has been removed, show the cleaned image.
            val uriToShow = cleanUri ?: selectedUri
            Image(
                painter = rememberAsyncImagePainter(model = uriToShow),
                contentDescription = "Image Preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 100.dp), // Reserve space for the bottom buttons.
                contentScale = ContentScale.Fit
            )
        }

        // Bottom control area
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            when {
                selectedUri == null -> {
                    // When no image is selected, show the "Select Image" button.
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
                cleanUri == null -> {
                    // When an image is selected but not yet processed,
                    // show the "Remove Metadata" button and a reset ("X") button.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    // Call the metadata removal process.
                                    cleanUri = removeMetadataProcess(context, selectedUri)
                                    isProcessing = false
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = if (isProcessing) "Processing..." else "Remove Metadata")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onResetImage()
                                cleanUri = null
                            }
                        ) {
                            Text("X")
                        }
                    }
                }
                else -> {
                    // After processing: show "Share", "Save" (not implemented), and reset ("X") buttons.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // Share the cleaned image.
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(cleanUri!!)
                                        ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, cleanUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Clean Image"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Share")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // TODO: Implement saving files directly.
                                Toast.makeText(context, "Not yet implemented.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onResetImage()
                                cleanUri = null
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
 * Replace this with your actual implementation.
 */
suspend fun removeMetadataProcess(context: android.content.Context, uri: Uri): Uri? {
    delay(2000) // Simulate processing delay
    return removeMetadata(context, uri) // In a real scenario, process the file and return the new URI.
}