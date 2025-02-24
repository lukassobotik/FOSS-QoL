package dev.lukassobotik.fossqol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import kotlinx.coroutines.launch

class CleanShareActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var sharedUri: Uri? = null

        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        enableEdgeToEdge()
        setContent {
            val tintColor = if (isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@CleanShareActivity,
                            label = "Clean Share",
                            showBackButton = true,
                            tintColor = tintColor)
                    }) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        MetadataRemoverScreen(
                            sourceUri = sharedUri)
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataRemoverScreen(sourceUri: Uri?) {
    if (sourceUri == null) {return}
    val context = LocalContext.current
    var cleanUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = {
            // Launch a coroutine to process the image
            scope.launch {
                cleanUri = removeMetadata(context, sourceUri)
            }
        }) {
            Text("Remove Metadata")
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