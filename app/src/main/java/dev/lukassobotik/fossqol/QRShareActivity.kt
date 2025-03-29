package dev.lukassobotik.fossqol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

class QRShareActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var sharedText: String? = null

        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            when {
                intent.type == "text/plain" -> sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
        }

        enableEdgeToEdge()
        setContent {
            val tintColor = if (isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                var text by rememberSaveable { mutableStateOf(sharedText ?: "Hello there!") }
                Scaffold(modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@QRShareActivity,
                            label = "QR Share",
                            showBackButton = true,
                            tintColor = tintColor)
                }) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        Box(modifier = Modifier.align(Alignment.Center)) {
                            qrCode(text)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            TextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                label = { Text("Enter QR Code Data") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun qrCode(data: String) {
    Row(
        modifier = Modifier
            .padding(64.dp)
            .aspectRatio(1f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberQrCodePainter(data),
            contentDescription = "QR code of $data",
            modifier = Modifier.fillMaxSize()
                .background(Color.White)
                .padding(16.dp)
        )
    }
}