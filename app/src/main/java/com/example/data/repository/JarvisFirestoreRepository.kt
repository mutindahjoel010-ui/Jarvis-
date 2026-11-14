package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.CalendarEvent
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.SmartDevice
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class JarvisFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun sanitizeId(raw: String = UUID.randomUUID().toString()): String {
        return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    // --- Conversations ---

    fun observeConversations(): Flow<List<Conversation>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("conversations")
        val path = colRef.path
        emitAll(
            colRef.orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    val cutoffMillis = System.currentTimeMillis() - SEVEN_DAYS_MILLIS
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            Conversation::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.filter { conv ->
                        val tsMillis = (conv.updatedAt ?: conv.createdAt)?.toDate()?.time
                        tsMillis == null || tsMillis >= cutoffMillis
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun purgeExpiredConversations(maxAgeMillis: Long = SEVEN_DAYS_MILLIS): Int {
        return try {
            val uid = requireUserId()
            val cutoffMillis = System.currentTimeMillis() - maxAgeMillis
            val colRef = db.collection("users").document(uid).collection("conversations")
            val snapshot = colRef.get().await()
            var deletedCount = 0
            for (doc in snapshot.documents) {
                val conv = doc.toObject(
                    Conversation::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: continue
                val tsMillis = (conv.updatedAt ?: conv.createdAt)?.toDate()?.time ?: continue
                if (tsMillis <= cutoffMillis) {
                    deleteConversation(doc.id)
                    deletedCount++
                }
            }
            deletedCount
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "users/conversations/purge")
            0
        }
    }

    companion object {
        const val SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }

    suspend fun createConversation(title: String): Result<String> {
        return try {
            val uid = requireUserId()
            val id = sanitizeId()
            val cleanTitle = title.trim().ifBlank { "New chat" }.take(120)
            val docRef = db.collection("users").document(uid)
                .collection("conversations").document(id)
            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/conversations")
            Result.failure(e)
        }
    }

    suspend fun updateConversationTitle(conversationId: String, title: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().ifBlank { "Chat" }.take(120)
            val docRef = db.collection("users").document(uid)
                .collection("conversations").document(conversationId)
            docRef.update(
                mapOf(
                    "title" to cleanTitle,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/conversations/$conversationId")
            Result.failure(e)
        }
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val convRef = db.collection("users").document(uid)
                .collection("conversations").document(conversationId)
            val msgs = convRef.collection("messages").get().await()
            for (doc in msgs.documents) {
                doc.reference.delete().await()
            }
            convRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/conversations/$conversationId")
            Result.failure(e)
        }
    }

    // --- Messages ---

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid)
            .collection("conversations").document(conversationId)
            .collection("messages")
        val path = colRef.path
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            ChatMessage::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun addMessage(conversationId: String, role: String, text: String): Result<String> {
        return try {
            val uid = requireUserId()
            val id = sanitizeId()
            val validRole = if (role == "assistant") "assistant" else "user"
            val cleanText = text.trim().ifBlank { " " }.take(20000)
            val convRef = db.collection("users").document(uid)
                .collection("conversations").document(conversationId)
            val msgRef = convRef.collection("messages").document(id)

            val payload = mapOf(
                "userId" to uid,
                "role" to validRole,
                "text" to cleanText,
                "createdAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
            // Touch parent conversation updatedAt
            convRef.update("updatedAt", FieldValue.serverTimestamp()).await()
            Result.success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/conversations/$conversationId/messages")
            Result.failure(e)
        }
    }

    // --- Calendar Events ---

    fun observeCalendarEvents(): Flow<List<CalendarEvent>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("calendar_events")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            CalendarEvent::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedBy { it.startTimeMillis }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun listCalendarEvents(startMillis: Long, endMillis: Long): Result<List<CalendarEvent>> {
        return try {
            val uid = requireUserId()
            val colRef = db.collection("users").document(uid).collection("calendar_events")
            val snapshot = colRef.get().await()
            val events = snapshot.documents.mapNotNull { doc ->
                doc.toObject(
                    CalendarEvent::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }.filter { ev ->
                ev.endTimeMillis >= startMillis && ev.startTimeMillis <= endMillis
            }.sortedBy { it.startTimeMillis }
            Result.success(events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "users/calendar_events")
            Result.failure(e)
        }
    }

    suspend fun createCalendarEvent(event: CalendarEvent): Result<String> {
        return try {
            val uid = requireUserId()
            val id = sanitizeId()
            val docRef = db.collection("users").document(uid)
                .collection("calendar_events").document(id)
            val start = event.startTimeMillis.coerceAtLeast(1L)
            val end = event.endTimeMillis.coerceAtLeast(start)
            val payload = mapOf(
                "userId" to uid,
                "title" to event.title.trim().ifBlank { "Event" }.take(200),
                "description" to event.description.trim().take(1000),
                "location" to event.location.trim().take(200),
                "startTimeMillis" to start,
                "endTimeMillis" to end,
                "deviceEventId" to event.deviceEventId.coerceAtLeast(0L),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/calendar_events")
            Result.failure(e)
        }
    }

    suspend fun deleteCalendarEvent(eventId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("calendar_events").document(eventId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/calendar_events/$eventId")
            Result.failure(e)
        }
    }

    // --- Smart Devices ---

    fun observeSmartDevices(): Flow<List<SmartDevice>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("smart_devices")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            SmartDevice::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedBy { it.name.lowercase() }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun listSmartDevices(): Result<List<SmartDevice>> {
        return try {
            val uid = requireUserId()
            val colRef = db.collection("users").document(uid).collection("smart_devices")
            val snapshot = colRef.get().await()
            val devices = snapshot.documents.mapNotNull { doc ->
                doc.toObject(
                    SmartDevice::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }.sortedBy { it.name.lowercase() }
            Result.success(devices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "users/smart_devices")
            Result.failure(e)
        }
    }

    suspend fun createSmartDevice(device: SmartDevice): Result<String> {
        return try {
            val uid = requireUserId()
            val id = sanitizeId()
            val docRef = db.collection("users").document(uid)
                .collection("smart_devices").document(id)
            val allowedTypes = setOf("light", "thermostat", "lock", "plug", "fan", "tv", "other")
            val safeType = device.deviceType.lowercase().let { if (it in allowedTypes) it else "other" }
            val payload = mapOf(
                "userId" to uid,
                "name" to device.name.trim().ifBlank { "Smart Device" }.take(100),
                "room" to device.room.trim().take(80),
                "deviceType" to safeType,
                "isOn" to device.isOn,
                "level" to device.level.coerceIn(0L, 100L),
                "targetTemp" to device.targetTemp.coerceIn(40L, 100L),
                "entityId" to device.entityId.trim().take(120),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/smart_devices")
            Result.failure(e)
        }
    }

    suspend fun updateSmartDeviceState(
        deviceId: String,
        isOn: Boolean,
        level: Long,
        targetTemp: Long
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("smart_devices").document(deviceId)
            docRef.update(
                mapOf(
                    "isOn" to isOn,
                    "level" to level.coerceIn(0L, 100L),
                    "targetTemp" to targetTemp.coerceIn(40L, 100L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/smart_devices/$deviceId")
            Result.failure(e)
        }
    }

    suspend fun deleteSmartDevice(deviceId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("smart_devices").document(deviceId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/smart_devices/$deviceId")
            Result.failure(e)
        }
    }
}
