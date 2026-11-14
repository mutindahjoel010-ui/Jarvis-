package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "user",
    val text: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CalendarEvent(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L,
    val deviceEventId: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SmartDevice(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val room: String = "",
    val deviceType: String = "light",
    @get:PropertyName("isOn")
    @set:PropertyName("isOn")
    var isOn: Boolean = false,
    val level: Long = 100L,
    val targetTemp: Long = 70L,
    val entityId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val snippet: String,
    val isUnread: Boolean = false,
    val body: String = ""
)

sealed interface InlineAction {
    data object RequestCalendarPermission : InlineAction
    data object ConnectGmail : InlineAction
    data object OpenSmartHomeSetup : InlineAction
}

data class DisplayMessage(
    val id: String,
    val role: String,
    val text: String,
    val isStreaming: Boolean = false,
    val inlineAction: InlineAction? = null
)
