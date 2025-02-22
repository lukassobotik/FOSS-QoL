package dev.lukassobotik.fossqol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FOSSQoLTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    settingsScreen(
                        onToolClick = {
                            // Navigate to ToolsActivity when the card is clicked.
                            startActivity(Intent(this, QRShareActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun settingsScreen(onToolClick: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Category header
            item {
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            item {
                settingsNewActivityCard(
                    headline = "QR Share",
                    description = "Share text with a QR Code",
                    onToolClick = onToolClick)
            }
        }
    }
}

@Composable
fun settingsNewActivityCard(headline: String, description: String, onToolClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Navigate"
            )
        },
        modifier = Modifier
            .clickable { onToolClick() }
    )
}