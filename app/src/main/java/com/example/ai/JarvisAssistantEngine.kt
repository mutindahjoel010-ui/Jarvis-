package com.example.ai

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.InlineAction
import com.example.data.repository.CalendarPrivilegeRepository
import com.example.data.repository.GmailPrivilegeRepository
import com.example.data.repository.SmartHomePrivilegeRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class JarvisReply(
    val text: String,
    val inlineAction: InlineAction? = null
)

class JarvisAssistantEngine(
    private val calendarRepo: CalendarPrivilegeRepository,
    private val gmailRepo: GmailPrivilegeRepository,
    private val smartHomeRepo: SmartHomePrivilegeRepository
) {
    private val tools: List<Tool> by lazy {
        listOf(
            Tool.functionDeclarations(
                listOf(
                    FunctionDeclaration(
                        name = "list_calendar_events",
                        description = "List upcoming or past events from the user's calendar within a date range.",
                        parameters = mapOf(
                            "startIso" to Schema.string("Start date-time in ISO-8601 local format YYYY-MM-DD'T'HH:mm:ss"),
                            "endIso" to Schema.string("End date-time in ISO-8601 local format YYYY-MM-DD'T'HH:mm:ss")
                        )
                    ),
                    FunctionDeclaration(
                        name = "create_calendar_event",
                        description = "Create a new event on the user's calendar.",
                        parameters = mapOf(
                            "title" to Schema.string("Short event title"),
                            "startIso" to Schema.string("Start date-time in ISO-8601 local format YYYY-MM-DD'T'HH:mm:ss"),
                            "endIso" to Schema.string("End date-time in ISO-8601 local format YYYY-MM-DD'T'HH:mm:ss"),
                            "location" to Schema.string("Optional event location"),
                            "description" to Schema.string("Optional event notes")
                        ),
                        optionalParameters = listOf("location", "description")
                    ),
                    FunctionDeclaration(
                        name = "delete_calendar_event",
                        description = "Remove an event from the user's calendar by its title or ID.",
                        parameters = mapOf(
                            "eventTitleOrId" to Schema.string("Exact or partial title or ID of the event to delete")
                        )
                    ),
                    FunctionDeclaration(
                        name = "read_emails",
                        description = "Search or read recent emails from the user's Gmail inbox.",
                        parameters = mapOf(
                            "query" to Schema.string("Optional Gmail search query like 'is:unread' or 'from:alex'"),
                            "maxResults" to Schema.integer("Maximum number of emails to fetch, between 1 and 8")
                        ),
                        optionalParameters = listOf("query", "maxResults")
                    ),
                    FunctionDeclaration(
                        name = "get_email_details",
                        description = "Read the full body of a specific email using its message ID.",
                        parameters = mapOf(
                            "messageId" to Schema.string("The Gmail message ID returned by read_emails")
                        )
                    ),
                    FunctionDeclaration(
                        name = "list_smart_devices",
                        description = "List the user's connected smart home devices and their current state.",
                        parameters = mapOf(
                            "roomFilter" to Schema.string("Optional room name to filter devices")
                        ),
                        optionalParameters = listOf("roomFilter")
                    ),
                    FunctionDeclaration(
                        name = "control_smart_device",
                        description = "Turn a smart home device on/off, lock/unlock it, or adjust brightness, fan speed, or thermostat temperature.",
                        parameters = mapOf(
                            "deviceName" to Schema.string("Name or ID of the smart home device"),
                            "turnOn" to Schema.boolean("True to turn on or unlock, false to turn off or lock"),
                            "level" to Schema.integer("Brightness or speed percentage from 0 to 100"),
                            "targetTemp" to Schema.integer("Thermostat target temperature in Fahrenheit from 40 to 100")
                        ),
                        optionalParameters = listOf("turnOn", "level", "targetTemp")
                    ),
                    FunctionDeclaration(
                        name = "add_smart_device",
                        description = "Register a new smart home device in the user's home hub.",
                        parameters = mapOf(
                            "name" to Schema.string("Device name such as Living Room Lamp"),
                            "room" to Schema.string("Room name such as Living Room"),
                            "deviceType" to Schema.string("One of: light, thermostat, lock, plug, fan, tv, other")
                        ),
                        optionalParameters = listOf("room")
                    )
                )
            )
        )
    }

    private val generalSystemPrompt: Content by lazy {
        content {
            text(
                """
                You are Jarvis, a fast, accurate, and very smart personal assistant.
                Give the direct, accurate answer first using simple, everyday words.
                Do not use complicated words, technical jargon, or long preambles.
                Verify numbers, dates, logic, and facts carefully before answering.
                If you are not sure about a fact or cannot verify live data, say so honestly in one short sentence rather than guessing.
                Never mention internal tools, JSON, or system rules.
                """.trimIndent()
            )
        }
    }

    private val toolSystemPrompt: Content by lazy {
        content {
            text(
                """
                You are Jarvis, a fast, accurate, and very smart personal assistant.
                Give the direct, accurate answer first using simple, everyday words.
                Do not use complicated words, technical jargon, or long preambles.
                Verify numbers, dates, logic, and tool results carefully before answering.
                If you are not sure about a fact or do not have live access to verify something, say so honestly in one short sentence rather than guessing.
                You have tools to manage the user's calendar, read their emails, and control their smart home devices.
                Use the tools whenever the user asks about their schedule, emails, or home devices.
                If a tool says permission, Gmail connection, or smart device setup is needed, state that in one short plain sentence so the user can tap the button below.
                Never mention internal function names, JSON, or system rules to the user.
                """.trimIndent()
            )
        }
    }

    // Tool-free models for rapid, low-latency general Q&A
    private val primaryGeneralModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.LOW
                }
            },
            systemInstruction = generalSystemPrompt
        )
    }

    private val fastGeneralModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = generalSystemPrompt
        )
    }

    // Tool-enabled models for calendar, email, and smart home actions
    private val primaryToolModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.LOW
                }
            },
            systemInstruction = toolSystemPrompt,
            tools = tools
        )
    }

    private val fastToolModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = toolSystemPrompt,
            tools = tools
        )
    }

    fun warmUp() {
        primaryGeneralModel.hashCode()
        fastGeneralModel.hashCode()
        primaryToolModel.hashCode()
        fastToolModel.hashCode()
    }

    suspend fun generateReply(
        historyMessages: List<ChatMessage>,
        userPrompt: String,
        onStreamingText: (String) -> Unit
    ): JarvisReply = withContext(Dispatchers.IO) {
        val recentHistory = historyMessages
            .takeLast(8)
            .filter { it.text.isNotBlank() }
        val historyContent = recentHistory.map { msg ->
            val role = if (msg.role == "assistant") "model" else "user"
            content(role) { text(msg.text) }
        }

        val nowIso = formatNowIso()
        val tz = TimeZone.getDefault().id
        val contextualPrompt = "[Local time: $nowIso ($tz)]\n$userPrompt"
        val useTools = mightNeedTools(userPrompt, recentHistory)

        val primaryModel = if (useTools) primaryToolModel else primaryGeneralModel
        val fastModel = if (useTools) fastToolModel else fastGeneralModel

        try {
            withTimeout(50_000L) {
                executeWithStallWatchdog(
                    primaryModel = primaryModel,
                    fastModel = fastModel,
                    emergencyGeneralModel = if (useTools) fastGeneralModel else null,
                    historyContent = historyContent,
                    contextualPrompt = contextualPrompt,
                    onStreamingText = onStreamingText
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Overall Jarvis reply budget exceeded 50s", e)
            throw IllegalStateException("Your connection is too slow right now. Please check your internet and try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Jarvis generation failed", e)
            throw IllegalStateException(
                e.localizedMessage ?: "Unable to reach Jarvis right now."
            )
        }
    }

    private suspend fun executeWithStallWatchdog(
        primaryModel: GenerativeModel,
        fastModel: GenerativeModel,
        emergencyGeneralModel: GenerativeModel?,
        historyContent: List<Content>,
        contextualPrompt: String,
        onStreamingText: (String) -> Unit
    ): JarvisReply = coroutineScope {
        val hasProgress = AtomicBoolean(false)
        val watchdogRunningModel = AtomicBoolean(false)
        val streamOwner = AtomicReference<String?>(null)
        val primaryPartial = AtomicReference("")
        val fallbackPartial = AtomicReference("")

        val emitChunk: (String, String) -> Unit = { ownerTag, text ->
            val currentOwner = streamOwner.get()
            if (currentOwner == ownerTag || streamOwner.compareAndSet(null, ownerTag)) {
                onStreamingText(text)
            }
        }

        val primaryDeferred = async {
            try {
                val res = runModelLoop(
                    generativeModel = primaryModel,
                    historyContent = historyContent,
                    initialPrompt = contextualPrompt,
                    timeoutMs = 35_000L,
                    onProgress = { hasProgress.set(true) },
                    onStreamingText = { partial ->
                        primaryPartial.set(partial)
                        emitChunk("primary", partial)
                    }
                )
                Result.success(res)
            } catch (e: TimeoutCancellationException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val watchdogDeferred = async {
            delay(STALL_WATCHDOG_MS)
            if (hasProgress.get() || !primaryDeferred.isActive) {
                awaitCancellation()
            }
            watchdogRunningModel.set(true)
            Log.w(TAG, "Primary model silent for ${STALL_WATCHDOG_MS}ms; hedging with fast model.")
            try {
                val res = runModelLoop(
                    generativeModel = fastModel,
                    historyContent = historyContent,
                    initialPrompt = contextualPrompt,
                    timeoutMs = 30_000L,
                    onProgress = { },
                    onStreamingText = { partial ->
                        fallbackPartial.set(partial)
                        emitChunk("watchdog", partial)
                    }
                )
                Result.success(res)
            } catch (e: TimeoutCancellationException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val raceOutcome: Result<JarvisReply> = select {
            primaryDeferred.onAwait { primaryResult ->
                if (primaryResult.isSuccess) {
                    watchdogDeferred.cancel()
                    primaryResult
                } else {
                    val savedPartial = primaryPartial.get().trim()
                    if (savedPartial.length >= 40) {
                        watchdogDeferred.cancel()
                        Result.success(JarvisReply(text = savedPartial))
                    } else if (watchdogRunningModel.get() && watchdogDeferred.isActive) {
                        watchdogDeferred.await()
                    } else {
                        watchdogDeferred.cancel()
                        Result.failure(primaryResult.exceptionOrNull() ?: IllegalStateException("Primary failed"))
                    }
                }
            }
            watchdogDeferred.onAwait { watchdogResult ->
                if (watchdogResult.isSuccess) {
                    primaryDeferred.cancel()
                    watchdogResult
                } else if (primaryDeferred.isActive) {
                    primaryDeferred.await()
                } else {
                    watchdogResult
                }
            }
        }

        raceOutcome.getOrNull()?.let { return@coroutineScope it }

        // Last-resort fast fallback if primary failed/timed out before watchdog produced a reply
        val savedPrimary = primaryPartial.get().trim()
        if (savedPrimary.length >= 40) {
            return@coroutineScope JarvisReply(text = savedPrimary)
        }

        try {
            streamOwner.set("retry")
            return@coroutineScope runModelLoop(
                generativeModel = fastModel,
                historyContent = historyContent,
                initialPrompt = contextualPrompt,
                timeoutMs = 30_000L,
                onProgress = {},
                onStreamingText = { partial ->
                    fallbackPartial.set(partial)
                    onStreamingText(partial)
                }
            )
        } catch (cancel: CancellationException) {
            if (cancel !is TimeoutCancellationException) throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "Fast model retry failed; trying emergency general model if available", e)
        }

        val savedFallback = fallbackPartial.get().trim()
        if (savedFallback.length >= 25) {
            return@coroutineScope JarvisReply(text = savedFallback)
        }

        if (emergencyGeneralModel != null) {
            streamOwner.set("emergency")
            return@coroutineScope runModelLoop(
                generativeModel = emergencyGeneralModel,
                historyContent = historyContent,
                initialPrompt = contextualPrompt,
                timeoutMs = 30_000L,
                onProgress = {},
                onStreamingText = onStreamingText
            )
        }

        val err = raceOutcome.exceptionOrNull()
        throw (err ?: IllegalStateException("Unable to reach Jarvis right now."))
    }

    private suspend fun runModelLoop(
        generativeModel: GenerativeModel,
        historyContent: List<Content>,
        initialPrompt: String,
        timeoutMs: Long,
        onProgress: () -> Unit,
        onStreamingText: (String) -> Unit
    ): JarvisReply {
        return withTimeout(timeoutMs) {
            val chat = generativeModel.startChat(history = historyContent)
            var pendingAction: InlineAction? = null
            var rounds = 0
            var currentTurnInput = content("user") { text(initialPrompt) }
            var finalAnswer = ""

            while (rounds < 5) {
                rounds++
                val turnText = StringBuilder()
                val collectedCalls = mutableListOf<FunctionCallPart>()

                chat.sendMessageStream(currentTurnInput).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        onProgress()
                        turnText.append(chunkText)
                        onStreamingText(turnText.toString())
                    }
                    if (chunk.functionCalls.isNotEmpty()) {
                        onProgress()
                        collectedCalls.addAll(chunk.functionCalls)
                    }
                }

                if (collectedCalls.isEmpty()) {
                    finalAnswer = turnText.toString().trim()
                    break
                }

                // Execute all tool calls in parallel so multi-tool queries (e.g. morning briefing) stay fast
                val outcomes = coroutineScope {
                    collectedCalls.map { call ->
                        async {
                            val toolOutcome = withTimeoutOrNull(7_000L) {
                                executeToolCall(call.name, call.args)
                            } ?: (buildJsonObject {
                                put("error", "Tool lookup timed out. Answer clearly with what you have.")
                            } to null)
                            call.name to toolOutcome
                        }
                    }.awaitAll()
                }
                onProgress()

                val toolResponses = outcomes.map { (callName, outcome) ->
                    val (resultJson, actionHint) = outcome
                    if (actionHint != null && pendingAction == null) {
                        pendingAction = actionHint
                    }
                    FunctionResponsePart(callName, resultJson)
                }

                currentTurnInput = content("user") {
                    toolResponses.forEach { part(it) }
                }
            }

            if (finalAnswer.isBlank()) {
                finalAnswer = "Done."
            }
            JarvisReply(text = finalAnswer, inlineAction = pendingAction)
        }
    }

    private fun mightNeedTools(prompt: String, recentHistory: List<ChatMessage>): Boolean {
        val textToCheck = buildString {
            append(prompt.lowercase(Locale.US))
            recentHistory.takeLast(2).forEach {
                append(' ')
                append(it.text.lowercase(Locale.US))
            }
        }
        return TOOL_HINT_KEYWORDS.any { keyword ->
            textToCheck.contains(keyword)
        }
    }

    private suspend fun executeToolCall(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>
    ): Pair<JsonObject, InlineAction?> {
        return when (name) {
            "list_calendar_events" -> {
                val now = System.currentTimeMillis()
                val startMillis = parseIsoToMillis(args.str("startIso")) ?: (now - 12L * 3600 * 1000)
                val endMillis = parseIsoToMillis(args.str("endIso")) ?: (startMillis + 7L * 24 * 3600 * 1000)
                val res = calendarRepo.listEvents(startMillis, endMillis)
                val json = buildJsonObject {
                    put("hasDeviceCalendarPermission", res.hasDevicePermission)
                    put("events", buildJsonArray {
                        res.events.forEach { ev ->
                            add(buildJsonObject {
                                put("id", ev.id)
                                put("title", ev.title)
                                put("start", formatMillisReadable(ev.startTimeMillis))
                                put("end", formatMillisReadable(ev.endTimeMillis))
                                put("location", ev.location)
                                put("description", ev.description)
                            })
                        }
                    })
                }
                val action = if (!res.hasDevicePermission && res.events.isEmpty()) {
                    InlineAction.RequestCalendarPermission
                } else {
                    null
                }
                json to action
            }

            "create_calendar_event" -> {
                val title = args.str("title").ifBlank { "Event" }
                val startMillis = parseIsoToMillis(args.str("startIso"))
                    ?: (System.currentTimeMillis() + 3600_000L)
                val endMillis = parseIsoToMillis(args.str("endIso"))
                    ?: (startMillis + 3600_000L)
                val location = args.str("location")
                val description = args.str("description")
                val res = calendarRepo.createEvent(title, startMillis, endMillis, location, description)
                val json = buildJsonObject {
                    put("success", res.success)
                    put("message", res.message)
                    put("needsDeviceCalendarPermission", res.needsDevicePermission)
                }
                val action = if (res.needsDevicePermission) InlineAction.RequestCalendarPermission else null
                json to action
            }

            "delete_calendar_event" -> {
                val target = args.str("eventTitleOrId")
                val res = calendarRepo.deleteEventByTitleOrId(target)
                val json = buildJsonObject {
                    put("success", res.success)
                    put("message", res.message)
                }
                val action = if (!res.success && res.needsDevicePermission) {
                    InlineAction.RequestCalendarPermission
                } else {
                    null
                }
                json to action
            }

            "read_emails" -> {
                val query = args.str("query")
                val max = args.int("maxResults") ?: 6
                val res = gmailRepo.listEmails(query = query, maxResults = max)
                val json = buildJsonObject {
                    put("needsGmailAuthorization", res.needsGmailAuth)
                    put("error", res.errorMessage.orEmpty())
                    put("emails", buildJsonArray {
                        res.emails.forEach { email ->
                            add(buildJsonObject {
                                put("id", email.id)
                                put("from", email.from)
                                put("subject", email.subject)
                                put("date", email.date)
                                put("unread", email.isUnread)
                                put("snippet", email.snippet)
                            })
                        }
                    })
                }
                val action = if (res.needsGmailAuth) InlineAction.ConnectGmail else null
                json to action
            }

            "get_email_details" -> {
                val id = args.str("messageId")
                val detail = gmailRepo.getEmailDetail(id)
                val json = if (detail != null) {
                    buildJsonObject {
                        put("found", true)
                        put("from", detail.from)
                        put("subject", detail.subject)
                        put("date", detail.date)
                        put("body", detail.body)
                    }
                } else {
                    buildJsonObject {
                        put("found", false)
                        put("error", "Could not read that email or Gmail is not connected.")
                    }
                }
                val action = if (detail == null) InlineAction.ConnectGmail else null
                json to action
            }

            "list_smart_devices" -> {
                val room = args.str("roomFilter")
                val devices = smartHomeRepo.listDevices(room)
                val json = buildJsonObject {
                    put("count", devices.size)
                    put("devices", buildJsonArray {
                        devices.forEach { d ->
                            add(buildJsonObject {
                                put("name", d.name)
                                put("room", d.room)
                                put("type", d.deviceType)
                                put("isOn", d.isOn)
                                put("level", d.level)
                                put("targetTemp", d.targetTemp)
                            })
                        }
                    })
                }
                val action = if (devices.isEmpty()) InlineAction.OpenSmartHomeSetup else null
                json to action
            }

            "control_smart_device" -> {
                val deviceName = args.str("deviceName")
                val turnOn = args.bool("turnOn")
                val level = args.int("level")
                val targetTemp = args.int("targetTemp")
                val res = smartHomeRepo.controlDevice(deviceName, turnOn, level, targetTemp)
                val json = buildJsonObject {
                    put("success", res.success)
                    put("message", res.message)
                    put("needsSetup", res.needsSetup)
                }
                val action = if (res.needsSetup) InlineAction.OpenSmartHomeSetup else null
                json to action
            }

            "add_smart_device" -> {
                val deviceName = args.str("name")
                val room = args.str("room")
                val type = args.str("deviceType").ifBlank { "light" }
                val res = smartHomeRepo.addDevice(deviceName, room, type)
                val json = buildJsonObject {
                    put("success", res.success)
                    put("message", res.message)
                }
                json to null
            }

            else -> {
                buildJsonObject { put("error", "Unknown tool: $name") } to null
            }
        }
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.str(key: String): String {
        return (this[key] as? JsonPrimitive)?.content ?: ""
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.int(key: String): Int? {
        return (this[key] as? JsonPrimitive)?.intOrNull
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.bool(key: String): Boolean? {
        return (this[key] as? JsonPrimitive)?.booleanOrNull
    }

    private fun formatNowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return sdf.format(Date())
    }

    private fun formatMillisReadable(millis: Long): String {
        val sdf = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.US)
        return sdf.format(Date(millis))
    }

    private fun parseIsoToMillis(iso: String): Long? {
        if (iso.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val parsed = sdf.parse(iso.trim().take(19))
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private companion object {
        const val TAG = "JarvisEngine"
        const val STALL_WATCHDOG_MS = 10_000L

        val TOOL_HINT_KEYWORDS = listOf(
            "calendar", "schedule", "event", "meeting", "appointment", "agenda",
            "remind", "busy", "free", "available", "reschedule", "book ",
            "today", "tomorrow", "tonight", "this week", "next week",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "email", "e-mail", "mail", "inbox", "gmail", "unread",
            "light", "lights", "lamp", "bulb", "thermostat", "temp", "temperature",
            "heat", "cool", "ac ", "fan", "lock", "unlock", "door", "plug",
            "outlet", "switch", "tv", "television", "device", "smart",
            "room", "bedroom", "kitchen", "living", "office", "garage", "hallway",
            "dim ", "brightness", "turn on", "turn off", "home assistant"
        )
    }
}
