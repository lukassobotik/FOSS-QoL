package dev.lukassobotik.fossqol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme

class NotificationNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            FOSSQoLTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                ) {
                    // Place the note dialog at the bottom center.
                    NoteDialog(
                        onDismiss = { finish() },
                        onSave = { note ->
                            val remoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
                            remoteViews.setTextViewText(R.id.notification_header, getString(R.string.notes))
                            remoteViews.setViewVisibility(R.id.notification_header, View.VISIBLE)
                            remoteViews.setTextViewText(R.id.notification_note, note)

                            val extendedRemoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
                            extendedRemoteViews.setTextViewText(R.id.notification_header, getString(R.string.notes))
                            extendedRemoteViews.setViewVisibility(R.id.notification_header, View.GONE)
                            extendedRemoteViews.setTextViewText(R.id.notification_note, note)

                            val notification = NotificationCompat.Builder(this@NotificationNoteActivity, Notifications.NOTES)
                                .setSmallIcon(R.drawable.note_stack)
                                .setSubText(getString(R.string.notes))
                                .setCustomContentView(remoteViews)
                                .setCustomBigContentView(extendedRemoteViews)
                                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

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
                                notify(0, notification.build())
                            }
                            finish()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
fun NoteDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var noteText by remember { mutableStateOf("") }

    // Center the dialog card in the screen
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .padding(8.dp)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "New Note", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text("Enter your note here") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onSave(noteText) }) {
                    Text("Save")
                }
            }
        }
    }
}
