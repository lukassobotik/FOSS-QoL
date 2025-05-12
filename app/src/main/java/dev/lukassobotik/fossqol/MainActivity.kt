package dev.lukassobotik.fossqol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import dev.lukassobotik.fossqol.utils.GitHubIssueUrlBuilder

enum class ToolOption(val title: String, val description: String, val icon: IconData?, val activityClass: Class<*>) {
    QR_SHARE("QR Share", "Share text with a QR Code.", IconData.VectorIcon(Icons.Rounded.QrCode), QRShareActivity::class.java),
    CLEAN_SHARE("Clean Share", "Remove metadata from files and compress.", IconData.VectorIcon(Icons.Rounded.Share), CleanShareActivity::class.java),
}

sealed class OnToolClick {
    data class Action(val action: () -> Unit) : OnToolClick()
    data class Url(val url: String) : OnToolClick()
}

sealed class IconData {
    data class PainterIcon(val painter: Painter): IconData()
    data class VectorIcon(val imageVector: ImageVector): IconData()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationNotes(this)
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
    val tintColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    Scaffold(
        topBar = {
            topAppBar(
                context = context,
                label = "Quality of Life improvements",
                showBackButton = false,
                tintColor = tintColor)
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
                        context = context,
                        headline = tool.title,
                        description = tool.description,
                        onToolClick = OnToolClick.Action{ onToolClick(tool) },
                        icon = tool.icon
                    )
                }
            }
            item {
                settingsNewActivityCard(
                    context = context,
                    headline = "Share To Save",
                    description = "Share any image / video to save it to your device. \nTo use, share any file from your device and select \"Share To Save\".",
                    onToolClick = null,
                    icon = IconData.VectorIcon(Icons.Rounded.Save)
                )
            }
            item {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            item {
                settingsNewActivityCard(
                    context = context,
                    headline = "Github",
                    description = "Access the source code on  the GitHub repository.",
                    onToolClick = OnToolClick.Url("https://github.com/lukassobotik/foss-qol"),
                    icon = IconData.PainterIcon(painterResource(id = R.drawable.github_logo))

                )
            }
            item {
                settingsNewActivityCard(
                    context = context,
                    headline = "BuyMeACoffee",
                    description = "Support the project by contributing through BuyMeACoffee.",
                    onToolClick = OnToolClick.Url("https://www.buymeacoffee.com/lukassobotik"),
                    icon = IconData.PainterIcon(painterResource(id = R.drawable.buymeacoffee_logo))
                )
            }
        }
    }
}


@Composable
fun settingsNewActivityCard(
    context: Context,
    headline: String,
    description: String,
    onToolClick: OnToolClick?,
    icon: IconData? = null
) {
    ListItem(
        leadingContent = {
            when (icon) {
                is IconData.PainterIcon ->
                    Icon(
                        painter = icon.painter,
                        contentDescription = headline,
                        modifier = Modifier.size(24.dp),
                    )

                is IconData.VectorIcon ->
                    Icon(
                        imageVector = icon.imageVector,
                        contentDescription = headline,
                        modifier = Modifier.size(24.dp)
                    )
                null -> {}
            }
        },
        headlineContent = { Text(headline) },
        supportingContent = { Text(description) },
        overlineContent = {  },
        trailingContent = {
            if (onToolClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Navigate to $headline."
                )
            }
        },
        modifier = Modifier.clickable {
            when (onToolClick) {
                is OnToolClick.Action -> onToolClick.action()
                is OnToolClick.Url -> context.openUrlInBrowser(onToolClick.url)
                null -> { /* No action provided */ }
            }
        }
    )
}

fun Context.openUrlInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
    }
    startActivity(intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun topAppBar(context: Context, label: String, showBackButton: Boolean, tintColor: Color = Color.White) {
    CenterAlignedTopAppBar(
        title = { Text(label) },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { bugReportButtonAction(context) }) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = "GitHub",
                    tint = tintColor
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

fun bugReportButtonAction(context: Context) {
    val issueUrl = GitHubIssueUrlBuilder.build(
        bugDescription = "",
        activity = context.javaClass.simpleName,
        version = getAppVersion(context),
        stepsToReproduce = ""
    )
    context.openUrlInBrowser(issueUrl)
}

fun getAppVersion(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        "latest"
    }.toString()
}

fun createNotificationNotes(context: Context) {
    createNotificationChannel(context)
    NotificationNoteStorage.loadNotes(context).forEach {
        pushNotificationNote(context, it)
    }
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            Notifications.NOTES,
            "Notes",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notification notes"
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}