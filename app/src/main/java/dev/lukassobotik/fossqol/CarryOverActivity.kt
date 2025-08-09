package dev.lukassobotik.fossqol

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme

class CarryOverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startClientExample(this@CarryOverActivity)
        }
        setContent {
            val tintColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White else Color.Black
            FOSSQoLTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        topAppBar(
                            context = this@CarryOverActivity,
                            label = "Carry Over",
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
                        activityScreen(this@CarryOverActivity)
                    }
                }
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)

    while (colonSplitter.hasNext()) {
        val componentName = ComponentName.unflattenFromString(colonSplitter.next())
        if (componentName == expectedComponentName) {
            return true
        }
    }
    return false
}

@Composable
private fun activityScreen(context: Context) {
    val scrollState = rememberScrollState()
    val packageName = context.packageName

    val isServiceEnabled = remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        isServiceEnabled.value = isAccessibilityServiceEnabled(context, CarryOverAccessibilityService::class.java)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 32.dp)) {
            Text(
                text = "Tap your camera cutout to instantly continue browsing your current page on your computer.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Text(
                text = "Required steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "The app needs these permissions to be able to detect the camera tap, determine what webpage you're currently on, and locate your scroll position on the page.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        stepRow(
            headline = "Accessibility Service",
            description = "Enable the Carry Over accessibility service.",
            clickAction = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            },
            alreadyGranted = isServiceEnabled.value
        )

        stepRow(
            headline = "Display over other apps",
            description = "Enable the \"Display over other apps\" permission in settings.",
            clickAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            alreadyGranted = Settings.canDrawOverlays(context)
        )

        stepRow(
            headline = "FOSS QoL Companion",
            description = "Install the FOSS QoL Companion extension on Firefox.",
            clickAction = {
                val url = "https://github.com/lukassobotik/foss-qol"
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        )
    }
}

@Composable
private fun stepRow(headline: String, description: String, clickAction: () -> Unit, alreadyGranted: Boolean = false) {
    ListItem(
        leadingContent = null,
        headlineContent = { Text(headline, modifier = Modifier.padding(start = 8.dp)) },
        supportingContent = { Text(description, modifier = Modifier.padding(start = 8.dp)) },
        trailingContent = {
            if (alreadyGranted) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Granted",
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        },
        overlineContent = {  },
        modifier = Modifier.clickable(onClick = clickAction)
    )
}
