package dev.lukassobotik.fossqol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme

enum class ToolOption(val title: String, val description: String, val activityClass: Class<*>) {
    QR_SHARE("QR Share", "Share text with a QR Code.", QRShareActivity::class.java),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FOSSQoLTheme {
                settingsScreen(
                    context = this,
                    onToolClick = { tool -> startActivity(Intent(this, tool.activityClass)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun settingsScreen(context: Context, modifier: Modifier = Modifier, onToolClick: (ToolOption) -> Unit) {
    val isDarkTheme = isSystemInDarkTheme()
    val tintColor = if (isDarkTheme) Color.White else Color.Black
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quality of Life improvements") },
                navigationIcon = {},
                actions = {
                    IconButton(onClick = { context.openUrlInBrowser("https://www.buymeacoffee.com/lukassobotik") }) {
                        Icon(
                            painter = painterResource(R.drawable.buymeacoffee_logo),
                            contentDescription = "BuyMeACoffee Logo",
                            tint = tintColor
                        )
                    }
                    IconButton(onClick = { context.openUrlInBrowser("https://github.com/lukassobotik/foss-qol") }) {
                        Icon(
                            painter = painterResource(R.drawable.github_logo),
                            contentDescription = "GitHub Logo",
                            tint = tintColor
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            item {
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            ToolOption.entries.forEach { tool ->
                item {
                    settingsNewActivityCard(
                        headline = tool.title,
                        description = tool.description,
                        onToolClick = { onToolClick(tool) }
                    )
                }
            }
            item {
                settingsNewActivityCard(
                    headline = "Share To Save",
                    description = "Share any image / video to save it to your device. \nTo use, share any file from your device and select \"Share To Save\".",
                    onToolClick = null
                )
            }
        }
    }
}


@Composable
fun settingsNewActivityCard(headline: String, description: String, onToolClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(description) },
        trailingContent = {
            if (onToolClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Navigate to $headline."
                )
            }
        },
        modifier = Modifier
            .clickable { onToolClick?.invoke() }
    )
}

fun Context.openUrlInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
    }
    startActivity(intent)
}