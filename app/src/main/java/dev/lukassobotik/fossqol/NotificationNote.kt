package dev.lukassobotik.fossqol

data class NotificationNote (
    val id: Int,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
)