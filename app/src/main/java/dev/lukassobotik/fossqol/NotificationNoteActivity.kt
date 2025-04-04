package dev.lukassobotik.fossqol

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lukassobotik.fossqol.NotificationNoteActivity
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme
import kotlinx.coroutines.launch

class NotificationNoteActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                showNotification()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        if (!isNotificationPermissionGranted(this@NotificationNoteActivity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            isNotificationPermissionGranted(this@NotificationNoteActivity)
            Toast.makeText(this@NotificationNoteActivity, "Notification permission is not granted", Toast.LENGTH_LONG).show()
        }

        setContent {
            FOSSQoLTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(0.dp)
                        .imePadding()
                ) {
                    // Place the note dialog at the bottom center.
                    NoteDialog(
                        context = this@NotificationNoteActivity,
                        onDismiss = { finish() },
                        onSave = { note ->
                            handleNoteSave(note)
                            finish()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    private fun handleNoteSave(note: NotificationNote) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            Log.w("Permissions", "Notification permission is not granted")
            Toast.makeText(this@NotificationNoteActivity, "Notification permission is not granted", Toast.LENGTH_SHORT).show()
        } else {
            showNotification(note)
        }
        finish()
    }


    private fun showNotification(note: NotificationNote = NotificationNote("", "")) {
        val noteTitle = note.title.ifEmpty { getString(R.string.notes) }

        val remoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
        remoteViews.setTextViewText(R.id.notification_header, noteTitle)
        remoteViews.setTextViewText(R.id.notification_note, note.body)

        val extendedRemoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
        extendedRemoteViews.setTextViewText(R.id.notification_header, noteTitle)
        extendedRemoteViews.setTextViewText(R.id.notification_note, note.body)

        val intent = Intent(this@NotificationNoteActivity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this@NotificationNoteActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this@NotificationNoteActivity, Notifications.NOTES)
            .setSmallIcon(R.drawable.note_stack)
            .setSubText(getString(R.string.notes))
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(extendedRemoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        with(NotificationManagerCompat.from(this@NotificationNoteActivity)) {
            if (ActivityCompat.checkSelfPermission(
                    this@NotificationNoteActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                // ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                // public fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                //                                        grantResults: IntArray)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.

                return@with
            }

            notify(Math.random().toInt(), notification.build())
        }
    }


}

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

@Composable
fun NoteDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (NotificationNote) -> Unit,
    modifier: Modifier = Modifier
) {
    var noteText by remember { mutableStateOf(NotificationNote("", "")) }
    var isPermissionGranted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionGranted = isNotificationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Center the dialog card in the screen
    Card(
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .padding(bottom = 32.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            BoxWithConstraints(modifier = Modifier
                .clipToBounds()
            ) {
                TextField(
                    value = noteText.title,
                    onValueChange = { noteText = noteText.copy(title = it) },
                    modifier = Modifier.fillMaxWidth().padding(0.dp).requiredWidth(maxWidth+16.dp)
                        .offset(x=(-8).dp),
                    placeholder = { Text(text = "New Note", style = TextStyle(fontSize = MaterialTheme.typography.titleLarge.fontSize)) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            BasicTextField(
                value = noteText.body,
                onValueChange = { noteText = noteText.copy(body = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(0.dp),
                decorationBox = { innerTextField ->
                    if (noteText.body.isEmpty()) {
                        Text(
                            text = "Enter your note here",
                            style = TextStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    innerTextField()
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (isPermissionGranted) {
                            onSave(noteText)
                        }
                    }, enabled = isPermissionGranted) {
                    Text("Save")
                }
            }
        }
    }
}
