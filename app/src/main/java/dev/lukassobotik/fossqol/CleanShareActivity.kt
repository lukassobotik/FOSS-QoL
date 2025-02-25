package dev.lukassobotik.fossqol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
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
            val tintColor = if (isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@CleanShareActivity,
                            label = "Clean Share",
                            showBackButton = true,
                            tintColor = tintColor
                        )
                    }
                ) { innerPadding ->
                    Column (modifier = Modifier.padding(innerPadding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        MetadataRemoverScreen(sourceUri = selectedUri)
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
fun MetadataRemoverScreen(sourceUri: Uri?) {
    val context = LocalContext.current
    var cleanUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Button to select an image
        Button(onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            (context as? Activity)?.startActivityForResult(intent, Codes.REQUEST_CODE_SELECT_IMAGE)
        }) {
            Text("Select Image")
        }

        // Show the "Remove Metadata" button only if an image is selected
        sourceUri?.let {
            Button(onClick = {
                // Launch a coroutine to process the image
                scope.launch {
                    cleanUri = removeMetadata(context, it)
                }
            }) {
                Text("Remove Metadata")
            }
        }

        cleanUri?.let { uri ->
            Text("Clean file created at: $uri")
            // Optionally, launch a share intent using the clean URI:
            Button(onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Clean Image"))
            }) {
                Text("Share Clean Image")
            }
        }
    }
}

