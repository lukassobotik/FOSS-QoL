package dev.lukassobotik.fossqol

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.lukassobotik.fossqol.NotificationNoteStorage.deleteNote
import java.io.File

const val GROUP_NOTIFICATION_NOTES = "dev.lukassobotik.fossqol.NotificationNoteGroup"
const val ACTION_NOTE_REMOVED = "dev.lukassobotik.fossqol.NoteRemoved"

data class NotificationNote (
    val id: Int,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
)

object NotificationNoteStorage {
    private const val FILE_NAME = "notification_notes.csv"

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    fun saveNotes(context: Context, notes: List<NotificationNote>) {
        val file = File(context.filesDir, FILE_NAME)
        val csvContent = notes.joinToString("\n") { note ->
            val titleEncoded = encode(note.title)
            val bodyEncoded = encode(note.body)
            "${note.id},$titleEncoded,$bodyEncoded,${note.timestamp}"
        }
        file.writeText(csvContent)
    }

    fun loadNotes(context: Context): List<NotificationNote> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return file.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null

            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val title = decode(parts[1])
            val body = decode(parts[2])
            val timestamp = parts[3].toLongOrNull() ?: return@mapNotNull null

            NotificationNote(
                id = id,
                title = title,
                body = body,
                timestamp = timestamp
            )
        }
    }

    fun deleteNote(context: Context, id: Int) {
        val notes = loadNotes(context).filterNot { it.id == id }
        saveNotes(context, notes)
    }

    fun getNoteById(context: Context, id: Int): NotificationNote? {
        return loadNotes(context).find { it.id == id }
    }

    fun generateNextId(context: Context): Int {
        val existingNotes = loadNotes(context)
        val maxId = existingNotes.maxOfOrNull { it.id } ?: 0
        return maxId + 1
    }
}

class NotificationDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getIntExtra("note_id", -1)
        if (noteId != -1) {
            deleteNote(context, noteId)

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(noteId)
        }
    }
}

class NotificationRepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTE_REMOVED) return

        val noteId = intent.getIntExtra("note_id", -1)
        if (noteId == -1) return

        val note = NotificationNoteStorage.getNoteById(context, noteId) ?: return
        pushNotificationNote(context, note)
    }
}

fun pushNotificationNote(context: Context, note: NotificationNote = NotificationNote(0, "", "")) {
    val noteTitle = note.title.ifEmpty { context.getString(R.string.notes) }

    val remoteViews = RemoteViews(context.packageName, R.layout.collapsed_notification_note)
    remoteViews.setTextViewText(R.id.notification_header, noteTitle)
    remoteViews.setTextViewText(R.id.notification_note, note.body)

    val extendedRemoteViews = RemoteViews(context.packageName, R.layout.extended_notification_note)
    extendedRemoteViews.setTextViewText(R.id.notification_header, noteTitle)
    extendedRemoteViews.setTextViewText(R.id.notification_note, note.body)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

    val deleteIntent = Intent(context, NotificationDeleteReceiver::class.java).apply {
        putExtra("note_id", note.id)
    }
    val deletePendingIntent: PendingIntent = PendingIntent.getBroadcast(context, note.id, deleteIntent, PendingIntent.FLAG_IMMUTABLE)

    val dismissIntent = PendingIntent.getBroadcast(
        context, note.id, Intent(context, NotificationRepostReceiver::class.java)
            .setAction(ACTION_NOTE_REMOVED)
            .putExtra("note_id", note.id),
        PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, Notifications.NOTES)
        .setSmallIcon(R.drawable.note_stack)
        .setSubText(context.getString(R.string.notes))
        .setCustomContentView(remoteViews)
        .setCustomBigContentView(extendedRemoteViews)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setContentIntent(pendingIntent)
        .setDeleteIntent(dismissIntent)
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setGroup(GROUP_NOTIFICATION_NOTES)
        .addAction(R.drawable.rounded_delete_forever, context.getString(R.string.delete), deletePendingIntent)

    with(NotificationManagerCompat.from(context)) {
        if (ActivityCompat.checkSelfPermission(
                context,
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

        notify(note.id, notification.build())
    }
}