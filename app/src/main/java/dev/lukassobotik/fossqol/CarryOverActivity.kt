package dev.lukassobotik.fossqol

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.canDrawOverlays
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import dev.lukassobotik.fossqol.utils.loadFromSharedPreferences
import dev.lukassobotik.fossqol.utils.loadPairedDeviceIDs
import dev.lukassobotik.fossqol.utils.savePairedDeviceIDs
import dev.lukassobotik.fossqol.utils.saveToSharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SHARED_PREFERENCES_SERVER_IP = "server_ip"
const val SHARED_PREFERENCES_PAIRED_DEVICES = "paired_devices"
const val SHARED_PREFERENCES_PAIRED_DEVICE_IDS = "paired_device_ids"

class CarryOverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    val isServiceEnabled = remember { mutableStateOf(false) }
    val canDrawOverlays = remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled.value = isAccessibilityServiceEnabled(context, CarryOverAccessibilityService::class.java)
                canDrawOverlays.value = canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
            alreadyGranted = canDrawOverlays.value
        )

        expandableStepRow(
            headline = "FOSS QoL Companion",
            description = "Install the FOSS QoL Companion extension on Firefox."
        ) {
            Text(
                text = "Install the FOSS QoL Companion extension on the computer you want to continue on. " +
                        "You can install it from GitHub or Mozilla Add-ons.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "GitHub Repository",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable {
                    val url = "https://github.com/lukassobotik/foss-qol"
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            )
            Text(
                text = "Paired devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            dynamicTextFields(label = "Device")
        }
        expandableStepRow(
            headline = "FOSS QoL Server",
            description = "Run a server to enable communication between devices."
        ) {
            Text(
                text = "This server is needed to make sure your devices can communicate with each other. \n" +
                        "There are 2 options: \n" +
                        "1. Run the server on the receiving computer (or any other computer that would run the server constantly). \n" +
                        "2. Run the server on this phone (not recommended - it will drain your battery). \n" +
                        "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            serverIPTextField()
        }
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

@Composable
private fun expandableStepRow(
    headline: String,
    description: String,
    expandedContent: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        ListItem(
            headlineContent = { Text(headline, modifier = Modifier.padding(start = 8.dp)) },
            supportingContent = { Text(description, modifier = Modifier.padding(start = 8.dp)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .rotate(rotation)
                )
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                expandedContent()
            }
        }
    }
}

@Composable
fun serverIPTextField() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var serverIP by remember {
        mutableStateOf(loadFromSharedPreferences(context, SHARED_PREFERENCES_SERVER_IP, SHARED_PREFERENCES_SERVER_IP))
    }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = coroutineScope.launch {
            delay(500) // debounce delay
            saveToSharedPreferences(context, SHARED_PREFERENCES_SERVER_IP, SHARED_PREFERENCES_SERVER_IP, serverIP.toString())
        }
    }

    OutlinedTextField(
        value = serverIP,
        onValueChange = { newValue ->
            serverIP = newValue
            scheduleSave()
        },
        label = { Text("Server IP Address") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
fun dynamicTextFields(label: String = "Input") {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var fields by remember {
        mutableStateOf(loadPairedDeviceIDs(context))
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = coroutineScope.launch {
            delay(500) // debounce delay
            savePairedDeviceIDs(context, fields)
        }
    }

    Column {
        fields.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    fields = fields.toMutableList().apply {
                        this[index] = newValue

                        // Add empty at end if typing in the last one
                        if (index == lastIndex && newValue.isNotBlank()) add("")

                        // Remove if blank and not last
                        if (index != lastIndex && newValue.isBlank()) removeAt(index)
                    }
                    scheduleSave()
                },
                label = { Text("$label #${index + 1}") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
    }
}