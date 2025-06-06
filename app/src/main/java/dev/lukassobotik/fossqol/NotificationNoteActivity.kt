package dev.lukassobotik.fossqol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lukassobotik.fossqol.ui.theme.FOSSQoLTheme

class NotificationNoteActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pushNotificationNote(this)
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

        val noteId = intent.getIntExtra("note_id", -1)
        val editingNote = if (noteId != -1) NotificationNoteStorage.getNoteById(this, noteId) else null

        setContent {
            FOSSQoLTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .imePadding()
                ) {
                    NoteDialog(
                        context = this@NotificationNoteActivity,
                        initialNote = editingNote,
                        onDismiss = { finish() },
                        onSave = { note ->
                            handleNoteSave(note, isEditing = editingNote != null)
                            finish()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    private fun handleNoteSave(note: NotificationNote, isEditing: Boolean) {
        val notes = NotificationNoteStorage.loadNotes(this).toMutableList()
        val finalNote = if (isEditing) {
            val index = notes.indexOfFirst { it.id == note.id }
            if (index != -1) notes[index] = note else notes.add(note)
            note
        } else {
            val newNote = note.copy(id = NotificationNoteStorage.generateNextId(this))
            notes.add(newNote)
            newNote
        }

        NotificationNoteStorage.saveNotes(this, notes)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            Toast.makeText(this, "Notification permission is not granted", Toast.LENGTH_SHORT).show()
        } else {
            pushNotificationNote(this, finalNote)
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
    initialNote: NotificationNote? = null,
    onDismiss: () -> Unit,
    onSave: (NotificationNote) -> Unit,
    modifier: Modifier = Modifier
) {
    var noteText by remember { mutableStateOf(initialNote ?: NotificationNote(0, "", "")) }
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

    Card(
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .padding(0.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            BoxWithConstraints(modifier = Modifier
                .clipToBounds()
                .padding(top = 16.dp)
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
