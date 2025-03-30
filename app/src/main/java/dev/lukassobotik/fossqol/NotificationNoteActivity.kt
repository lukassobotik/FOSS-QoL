package dev.lukassobotik.fossqol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                        .padding(0.dp)
                        .imePadding()
                ) {
                    // Place the note dialog at the bottom center.
                    NoteDialog(
                        onDismiss = { finish() },
                        onSave = { note ->
                            var noteTitle = if (note.title !== "") {
                                note.title
                            } else {
                                getString(R.string.notes)
                            }

                            val remoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
                            remoteViews.setTextViewText(R.id.notification_header, noteTitle)
                            remoteViews.setTextViewText(R.id.notification_note, note.body)

                            val extendedRemoteViews = RemoteViews(this@NotificationNoteActivity.packageName, R.layout.notification_checklist)
                            extendedRemoteViews.setTextViewText(R.id.notification_header, noteTitle)
                            extendedRemoteViews.setTextViewText(R.id.notification_note, note.body)

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
    onSave: (NotificationNote) -> Unit,
    modifier: Modifier = Modifier
) {
    var noteText by remember { mutableStateOf(NotificationNote("", "")) }

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
                Button(onClick = { onSave(noteText) }) {
                    Text("Save")
                }
            }
        }
    }
}
