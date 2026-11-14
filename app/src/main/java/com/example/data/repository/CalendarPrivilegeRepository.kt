package com.example.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

data class CalendarQueryResult(
    val events: List<CalendarEvent>,
    val hasDevicePermission: Boolean
)

data class CalendarMutationResult(
    val success: Boolean,
    val message: String,
    val eventId: String = "",
    val needsDevicePermission: Boolean = false
)

class CalendarPrivilegeRepository(
    private val context: Context,
    private val firestoreRepository: JarvisFirestoreRepository
) {
    fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    suspend fun listEvents(startMillis: Long, endMillis: Long): CalendarQueryResult =
        withContext(Dispatchers.IO) {
            val hasPerm = hasCalendarPermissions()
            val cloudEvents = firestoreRepository.listCalendarEvents(startMillis, endMillis)
                .getOrDefault(emptyList())

            val deviceEvents = if (hasPerm) {
                readDeviceCalendarEvents(startMillis, endMillis)
            } else {
                emptyList()
            }

            // Merge device and cloud events without duplicating synced entries
            val cloudDeviceIds = cloudEvents.mapNotNull {
                if (it.deviceEventId > 0L) it.deviceEventId else null
            }.toSet()

            val merged = buildList {
                addAll(cloudEvents)
                deviceEvents.forEach { devEv ->
                    if (devEv.deviceEventId !in cloudDeviceIds) {
                        add(devEv)
                    }
                }
            }.sortedBy { it.startTimeMillis }

            CalendarQueryResult(events = merged, hasDevicePermission = hasPerm)
        }

    suspend fun createEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String = "",
        description: String = ""
    ): CalendarMutationResult = withContext(Dispatchers.IO) {
        val hasPerm = hasCalendarPermissions()
        var insertedDeviceEventId = 0L

        if (hasPerm) {
            insertedDeviceEventId = insertDeviceCalendarEvent(
                title = title,
                startMillis = startMillis,
                endMillis = endMillis,
                location = location,
                description = description
            )
        }

        val cloudEvent = CalendarEvent(
            title = title,
            description = description,
            location = location,
            startTimeMillis = startMillis,
            endTimeMillis = endMillis,
            deviceEventId = insertedDeviceEventId
        )

        val result = firestoreRepository.createCalendarEvent(cloudEvent)
        if (result.isSuccess) {
            CalendarMutationResult(
                success = true,
                message = if (insertedDeviceEventId > 0L) {
                    "Added \"$title\" to your device and cloud calendar."
                } else {
                    "Added \"$title\" to your schedule."
                },
                eventId = result.getOrDefault(""),
                needsDevicePermission = !hasPerm
            )
        } else {
            CalendarMutationResult(
                success = false,
                message = result.exceptionOrNull()?.localizedMessage
                    ?: "Could not save the calendar event.",
                needsDevicePermission = !hasPerm
            )
        }
    }

    suspend fun deleteEventByTitleOrId(eventIdOrTitle: String): CalendarMutationResult =
        withContext(Dispatchers.IO) {
            val hasPerm = hasCalendarPermissions()
            val now = System.currentTimeMillis()
            val windowStart = now - 365L * 24 * 3600 * 1000
            val windowEnd = now + 365L * 24 * 3600 * 1000

            val cloudEvents = firestoreRepository.listCalendarEvents(windowStart, windowEnd)
                .getOrDefault(emptyList())
            val matchingCloud = cloudEvents.firstOrNull {
                it.id == eventIdOrTitle ||
                    it.title.equals(eventIdOrTitle, ignoreCase = true) ||
                    it.title.contains(eventIdOrTitle, ignoreCase = true)
            }

            if (matchingCloud != null) {
                if (hasPerm && matchingCloud.deviceEventId > 0L) {
                    deleteDeviceCalendarEvent(matchingCloud.deviceEventId)
                }
                firestoreRepository.deleteCalendarEvent(matchingCloud.id)
                return@withContext CalendarMutationResult(
                    success = true,
                    message = "Removed \"${matchingCloud.title}\" from your calendar.",
                    needsDevicePermission = !hasPerm
                )
            }

            if (hasPerm) {
                val deviceEvents = readDeviceCalendarEvents(windowStart, windowEnd)
                val matchingDevice = deviceEvents.firstOrNull {
                    it.deviceEventId.toString() == eventIdOrTitle ||
                        it.title.equals(eventIdOrTitle, ignoreCase = true) ||
                        it.title.contains(eventIdOrTitle, ignoreCase = true)
                }
                if (matchingDevice != null && matchingDevice.deviceEventId > 0L) {
                    val deleted = deleteDeviceCalendarEvent(matchingDevice.deviceEventId)
                    if (deleted) {
                        return@withContext CalendarMutationResult(
                            success = true,
                            message = "Removed \"${matchingDevice.title}\" from your device calendar."
                        )
                    }
                }
            }

            CalendarMutationResult(
                success = false,
                message = "I couldn't find an event matching \"$eventIdOrTitle\".",
                needsDevicePermission = !hasPerm
            )
        }

    private fun readDeviceCalendarEvents(startMillis: Long, endMillis: Long): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        try {
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, startMillis)
            ContentUris.appendId(uriBuilder, endMillis)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END
            )

            context.contentResolver.query(
                uriBuilder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val descIdx = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)

                while (cursor.moveToNext()) {
                    val devId = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
                    val desc = if (descIdx >= 0) cursor.getString(descIdx).orEmpty() else ""
                    val loc = if (locIdx >= 0) cursor.getString(locIdx).orEmpty() else ""
                    val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                    val end = if (endIdx >= 0) cursor.getLong(endIdx) else begin

                    if (title.isNotBlank()) {
                        events.add(
                            CalendarEvent(
                                id = "device_$devId",
                                title = title,
                                description = desc,
                                location = loc,
                                startTimeMillis = begin,
                                endTimeMillis = end,
                                deviceEventId = devId
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read Android CalendarContract", e)
        }
        return events
    }

    private fun insertDeviceCalendarEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String,
        description: String
    ): Long {
        return try {
            val calendarId = findPrimaryWritableCalendarId() ?: return 0L
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Unable to insert into Android CalendarContract", e)
            0L
        }
    }

    private fun deleteDeviceCalendarEvent(deviceEventId: Long): Boolean {
        return try {
            val deleteUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                deviceEventId
            )
            context.contentResolver.delete(deleteUri, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "Unable to delete Android CalendarContract event $deviceEventId", e)
            false
        }
    }

    private fun findPrimaryWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "CalendarRepo"
    }
}
